-- =============================================================================
-- V090__fix_rls_guc_naming_inconsistency.sql
-- DB-054 [OPCJONALNY, POBOCZNY]: Naprawa niespojnosci nazwy GUC w politykach RLS
-- na 4 tabelach, ktore blednie uzywaja current_setting('app.tenant_id', TRUE)
-- zamiast poprawnej current_setting('app.current_tenant_id', TRUE) -- jedynej
-- nazwy faktycznie ustawianej przez set_tenant_context()/
-- TenantAwareRepository.setTenantContextInDb() (EPIC-29 Partycjonowanie i
-- retencja danych z obslugi kontaktow).
--
-- Migracja: Flyway V090
-- Zaleznosci: DB-049/V085 (contact_event), DB-050/V086 (contact_transcription),
--             DB-051/V087 (contact_ai_summary) -- te 3 tabele przeszly online
--             partition-swap i odtworzyly RLS z tym samym (blednym) GUC co
--             oryginal, swiadomie nie naprawiajac go w tamtych tickietach.
--             tenant_ai_config (V064) nie byla dotad dotykana w EPIC-29.
-- Blokuje: brak -- ostatni ticket warstwy DB w EPIC-29 (DB-046..054, 9/9).
--
-- KONTEKST: cztery migracje historyczne (V059 contact_event, V064
-- tenant_ai_config, V067 contact_transcription, V068 contact_ai_summary)
-- ustawily polityke RLS z GUC "app.tenant_id" zamiast "app.current_tenant_id".
-- Poniewaz aplikacja nigdy nie ustawia GUC o nazwie "app.tenant_id"
-- (current_setting(..., TRUE) zwraca NULL, wiec porownanie tenant_id = NULL
-- jest zawsze NULL/false), polityka RLS na tych 4 tabelach nigdy sie nie
-- dopasowywala -- w praktyce RLS byl na nich martwym kodem. Warstwa aplikacji
-- (assertSameTenant(...) w repozytoriach) nadal chroni przed cross-tenant
-- dostepem, wiec to nie byla aktywnie eksploatowalna luka, ale osłabiala
-- defense-in-depth (zob. tez notatka w [[contact_center_project]]:
-- "Znana niekonsekwencja RLS").
--
-- Stan przed ta migracja (zweryfikowany \d + pg_policies przed
-- implementacja):
-- | Tabela                 | relforcerowsecurity | Polityka                          |
-- |-------------------------|----------------------|------------------------------------|
-- | contact_event           | t (od DB-049/V085)   | contact_event_tenant_isolation     |
-- | tenant_ai_config        | f (BRAK)              | tenant_ai_config_isolation         |
-- | contact_transcription   | t (od DB-050/V086)   | contact_transcription_isolation    |
-- | contact_ai_summary      | t (od DB-051/V087)   | contact_ai_summary_isolation       |
--
-- Wszystkie 4 polityki maja dzis identyczna strukture: sam USING, bez WITH
-- CHECK (styl starszych migracji V059/V064/V067/V068).
--
-- DECYZJA (uzasadnienie zapisane tutaj, do przeczytania jesli przyszly ticket
-- zakwestionuje ten wybor): ten ticket jest EXPLICITE oznaczony jako
-- poboczny/opcjonalny wzgledem glownego epiku, celowo wydzielony do wlasnego
-- pliku migracji zeby dalo sie go latwo wycofac. Zakres ograniczony
-- WYLACZNIE do (a) naprawy nazwy GUC i (b) FORCE ROW LEVEL SECURITY dla
-- tenant_ai_config (jedynej z czterech, ktora go dzis nie ma). SWIADOMIE NIE
-- dodano WITH CHECK, mimo ze nowsze tabele EPIC-29 (np.
-- tenant_retention_policy z DB-046) juz go maja -- dodanie WITH CHECK
-- rozszerzyloby diff i zakres zmiany (nowe zachowanie przy INSERT/UPDATE, nie
-- tylko naprawa istniejacego mechanizmu SELECT/DELETE), co jest sprzeczne z
-- celem tego ticketu (maly, przewidywalny, latwy do wycofania diff). Brak
-- jawnego WITH CHECK nie oznacza braku ochrony przy zapisie -- dla polityki
-- domyslnej ALL bez jawnego WITH CHECK, PostgreSQL uzywa klauzuli USING
-- rowniez jako check przy zapisie (potwierdzone empirycznie w testach
-- DB-049/DB-050/DB-051 dla tego samego wzorca -- cross-tenant INSERT byl
-- poprawnie odrzucany mimo braku WITH CHECK). Ujednolicenie stylu polityk RLS
-- (dodanie jawnego WITH CHECK wszedzie) pozostaje otwarte jako osobna,
-- przyszla porzadkowa migracja, poza zakresem DB-054.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. contact_event (V059 -> odtworzona w DB-049/V085) -- FORCE juz ustawione
--    w V085, tylko naprawa GUC.
-- ---------------------------------------------------------------------------
DROP POLICY IF EXISTS contact_event_tenant_isolation ON contact_event;
CREATE POLICY contact_event_tenant_isolation ON contact_event
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

-- ---------------------------------------------------------------------------
-- 2. tenant_ai_config (V064) -- jedyna z czterech bez FORCE ROW LEVEL
--    SECURITY dotad; dodajemy tutaj razem z naprawa GUC.
-- ---------------------------------------------------------------------------
DROP POLICY IF EXISTS tenant_ai_config_isolation ON tenant_ai_config;
CREATE POLICY tenant_ai_config_isolation ON tenant_ai_config
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

ALTER TABLE tenant_ai_config FORCE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 3. contact_transcription (V067 -> odtworzona w DB-050/V086) -- FORCE juz
--    ustawione w V086, tylko naprawa GUC.
-- ---------------------------------------------------------------------------
DROP POLICY IF EXISTS contact_transcription_isolation ON contact_transcription;
CREATE POLICY contact_transcription_isolation ON contact_transcription
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

-- ---------------------------------------------------------------------------
-- 4. contact_ai_summary (V068 -> odtworzona w DB-051/V087) -- FORCE juz
--    ustawione w V087, tylko naprawa GUC.
-- ---------------------------------------------------------------------------
DROP POLICY IF EXISTS contact_ai_summary_isolation ON contact_ai_summary;
CREATE POLICY contact_ai_summary_isolation ON contact_ai_summary
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

-- ---------------------------------------------------------------------------
-- Weryfikacja koncowa: wszystkie 4 polityki musza teraz uzywac
-- app.current_tenant_id, a wszystkie 4 tabele musza miec FORCE ROW LEVEL
-- SECURITY. Blad tutaj powoduje ROLLBACK calej migracji (Flyway na
-- PostgreSQL domyslnie jedna transakcja per migracja).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_bad_guc_count INT;
    v_missing_force_count INT;
BEGIN
    SELECT COUNT(*) INTO v_bad_guc_count
    FROM pg_policies
    WHERE tablename IN ('contact_event', 'tenant_ai_config', 'contact_transcription', 'contact_ai_summary')
      AND qual NOT LIKE '%app.current_tenant_id%';

    IF v_bad_guc_count > 0 THEN
        RAISE EXCEPTION 'V090: % polityk RLS nadal nie uzywa app.current_tenant_id', v_bad_guc_count;
    END IF;

    SELECT COUNT(*) INTO v_missing_force_count
    FROM pg_class
    WHERE relname IN ('contact_event', 'tenant_ai_config', 'contact_transcription', 'contact_ai_summary')
      AND relforcerowsecurity = FALSE;

    IF v_missing_force_count > 0 THEN
        RAISE EXCEPTION 'V090: % z 4 tabel nadal nie ma FORCE ROW LEVEL SECURITY', v_missing_force_count;
    END IF;

    RAISE NOTICE 'V090: OK -- 4/4 polityki uzywaja app.current_tenant_id, 4/4 tabele maja FORCE ROW LEVEL SECURITY';
END $$;
