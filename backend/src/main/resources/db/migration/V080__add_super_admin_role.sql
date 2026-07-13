-- =============================================================================
-- V080__add_super_admin_role.sql
-- Refaktor rol uzytkownikow: nowa rola SUPER_ADMIN – globalny administrator
-- platformy, bez przypisania do tenanta.
--
-- Migracja: Flyway V080
-- Zaleznosci: V003 (create_user), V019 (chk_app_user_role, konwersja enum -> varchar)
-- Kontekst: plan refaktoru rol SUPER_ADMIN / ADMIN / SUPERVISOR / AGENT
--           (SUPER_ADMIN przejmuje dzisiejszy, nigdy w pelni niewyegzekwowany
--           w schemacie, zakres "globalnego administratora platformy" opisany
--           w oryginalnym komentarzu app_user.tenant_id z V003).
--
-- Zmiana znaczeniowa roli ADMIN (staje sie w pelni tenant-scoped i przejmuje
-- dzisiejszy zakres uprawnien SUPERVISOR) jest wylacznie logika aplikacyjna
-- (@PreAuthorize, SecurityConfig) – NIE wymaga zmian schematu, bo ADMIN mial
-- juz tenant_id NOT NULL identycznie jak SUPERVISOR i AGENT.
--
-- Zgodnosc z danymi deweloperskimi (V999__dev_seed.sql):
--   Istniejacy wiersz role='ADMIN' (admin@contactcenter.dev @ Acme) ma
--   tenant_id NOT NULL – spelnia nowy constraint niezmienniczy (krok 3)
--   bez potrzeby jakiejkolwiek migracji danych. Nazwa roli 'ADMIN' sie nie
--   zmienia, wiec chk_app_user_role (krok 2) rowniez nie wymaga UPDATE na
--   istniejacych wierszach.
--
-- Analiza wplywu na obiekty zalezne od app_user (przed napisaniem tej migracji
-- zweryfikowano cala tresc kazdego z ponizszych plikow):
--   - v_tenant_stats (V005, odtworzony w V019): LEFT JOIN app_user u
--     ON u.tenant_id = t.tenant_id – wiersz SUPER_ADMIN (tenant_id IS NULL)
--     nigdy nie dopasuje sie do zadnego t.tenant_id (NULL = X jest zawsze
--     NULL/false w SQL), wiec SUPER_ADMIN nie pojawi sie w zadnym agregacie
--     per-tenant. To jest oczekiwane zachowanie – widok nie wymaga zmian.
--   - v_queue_available_agents (V008/V019) i v_queue_realtime_stats
--     (V008/V016/V019): dolaczaja app_user wylacznie przez queue_agent
--     (przypisanie agenta do kolejki, operacja tenant-scoped) – SUPER_ADMIN
--     nigdy nie bedzie miec wiersza w queue_agent. Bez zmian.
--   - v_active_contacts (V016): LEFT JOIN app_user u ON u.user_id = c.agent_id
--     – SUPER_ADMIN nie obsluguje kontaktow. Bez zmian.
--   - check_tenant_limit(p_tenant_id, 'agents') (V005): liczy
--     WHERE tenant_id = p_tenant_id AND role = 'AGENT' – nie dotyczy
--     SUPER_ADMIN (inna rola, inny tenant_id). Bez zmian.
--   - fn_contact_ref_integrity() (V016): waliduje agent_id wzgledem
--     app_user.tenant_id = NEW.tenant_id – SUPER_ADMIN nigdy nie bedzie
--     agent_id kontaktu (brak tenant_id do dopasowania). Bez zmian.
--   - chk_app_user_role jest jedynym miejscem w migracjach odwolujacym sie
--     do zamknietej listy 3 wartosci roli (potwierdzone przez grep po
--     "chk_app_user_role" i po literalach 'ADMIN'/'SUPERVISOR'/'AGENT' w
--     katalogu migracji) – jedyne miejsce do aktualizacji jest w kroku 2.
--   - pol_app_user_select (V012, RLS): USING (tenant_id = current_setting(...)::UUID).
--     Dla SUPER_ADMIN (tenant_id NULL) warunek jest zawsze NULL/false, wiec
--     row nigdy nie bedzie widoczny przez role `app_user` (RLS-restricted)
--     w sesji z ustawionym app.current_tenant_id – to jest pozadane: SUPER_ADMIN
--     nie powinien "wyciekac" do zapytan tenant-scoped. AppUserRepository
--     (JpaRepository, nie TenantAwareRepository) nie wywoluje
--     set_tenant_context()/SET ROLE app_user, a produkcyjna rola polaczenia
--     `ccapp` ma BYPASSRLS = TRUE (zweryfikowane w pg_roles) – RLS na app_user
--     nie ogranicza wiec w praktyce odczytu SUPER_ADMIN przy logowaniu
--     globalnym. Nowy indeks unikalny w kroku 4 rowniez nie koliduje z RLS:
--     unique/partial indexes w PostgreSQL sa egzekwowane na CALEJ tabeli,
--     niezaleznie od widocznosci wierszy przez polityki RLS.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. app_user.tenant_id: NOT NULL -> nullable
--    (dozwolone NULL wylacznie dla SUPER_ADMIN, patrz constraint w kroku 3)
-- ---------------------------------------------------------------------------

ALTER TABLE app_user
    ALTER COLUMN tenant_id DROP NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. chk_app_user_role: dodanie SUPER_ADMIN do dozwolonych wartosci
-- ---------------------------------------------------------------------------

ALTER TABLE app_user
    DROP CONSTRAINT chk_app_user_role;

ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_role
        CHECK (role IN ('SUPER_ADMIN', 'ADMIN', 'SUPERVISOR', 'AGENT'));

-- ---------------------------------------------------------------------------
-- 3. Constraint niezmienniczy: SUPER_ADMIN <=> tenant_id IS NULL
-- ---------------------------------------------------------------------------
-- Gwarantuje, ze:
--   - SUPER_ADMIN NIGDY nie ma tenant_id (globalny, cross-tenant)
--   - ADMIN / SUPERVISOR / AGENT ZAWSZE maja tenant_id (tenant-scoped)
-- Bez tego constraintu, DROP NOT NULL z kroku 1 pozwolilby ustawic
-- tenant_id = NULL dla dowolnej roli, co lamie zalozenia RLS i logiki
-- aplikacyjnej wielodostepnosci.

ALTER TABLE app_user
    ADD CONSTRAINT chk_super_admin_tenant_invariant
        CHECK (
            (role = 'SUPER_ADMIN' AND tenant_id IS NULL)
            OR (role <> 'SUPER_ADMIN' AND tenant_id IS NOT NULL)
        );

-- ---------------------------------------------------------------------------
-- 4. Globalna unikalnosc emaila kont SUPER_ADMIN (case-insensitive)
-- ---------------------------------------------------------------------------
-- Istniejacy uq_user_tenant_email ON (tenant_id, email) WHERE is_deleted = FALSE
-- (V003) NIE chroni SUPER_ADMIN: PostgreSQL (bez NULLS NOT DISTINCT, dostepnego
-- dopiero od PG 15 i tu nieuzytego) traktuje kazdy NULL w kolumnach indeksu
-- unikalnego jako rozny od kazdego innego NULL – dwa wiersze SUPER_ADMIN
-- z tenant_id = NULL i identycznym email NIE naruszylyby tego indeksu.
-- Nowy indeks czesciowy wymusza globalna unikalnosc emaila wylacznie w
-- obrebie roli SUPER_ADMIN (case-insensitive, spojnie z logowaniem po emailu).

CREATE UNIQUE INDEX uq_super_admin_email
    ON app_user (LOWER(email))
    WHERE role = 'SUPER_ADMIN' AND is_deleted = FALSE;

-- ---------------------------------------------------------------------------
-- 5. Aktualizacja komentarzy kolumn
-- ---------------------------------------------------------------------------
-- Oryginalny komentarz z V003 mowil o NULL dla roli ADMIN – bylo to
-- zalozenie teoretyczne, nigdy nie wyegzekwowane w schemacie (tenant_id byl
-- NOT NULL dla kazdej roli). Od tej migracji NULL dotyczy wylacznie
-- SUPER_ADMIN i jest wyegzekwowane przez chk_super_admin_tenant_invariant.

COMMENT ON COLUMN app_user.tenant_id IS
    'FK do tenant. NULL wylacznie dla roli SUPER_ADMIN (globalny administrator '
    'platformy, bez przypisania do tenanta) – wymuszone przez '
    'chk_super_admin_tenant_invariant. Dla ADMIN/SUPERVISOR/AGENT zawsze '
    'NOT NULL (tenant-scoped).';

COMMENT ON COLUMN app_user.role IS
    'Rola biznesowa: SUPER_ADMIN (platforma, tenant_id IS NULL) – zarzadzanie '
    'tenantami, cross-tenant uzytkownicy, globalne metryki; ADMIN (tenant) – '
    'pelna konfiguracja techniczna i biznesowa w obrebie tenanta; SUPERVISOR '
    '(tenant) – konfiguracja biznesowa bez technicznej (email/social/Twilio/AI/ '
    'pluginy); AGENT (tenant) – obsluga klienta.';
