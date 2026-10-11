# DB-084 WP-1 — model danych okresu archiwizacji kontaktów kampanii per tenant + data odniesienia

Data analizy: 2026-10-11. Tryb: read-only (żywa baza `cc-postgres`/`contact_center`, rola `ccapp`, wyłącznie SELECT i transakcje z ROLLBACK; po eksperymentach sprawdzono, że baza jest nietknięta). Brak zmian w migracjach, kodzie, compose, `.env*`, `TASKS-*.md`, `PROGRESS.md`.
Oznaczenia: **[F]** fakt zweryfikowany (kod/baza/eksperyment), **[W]** wniosek/rekomendacja, **[Z]** niezweryfikowane.

---

## 0. Streszczenie decyzji

| Pytanie | Rekomendacja |
|---|---|
| Model danych | **(B) dedykowana tabela `tenant_campaign_archive_setting`**, PK = `tenant_id`, `archive_after_days INT NOT NULL DEFAULT 180 CHECK 30..730`, brak wiersza = 180 (`LEFT JOIN` + `COALESCE`), bez backfillu wierszy. Odrzucić A, C, D. |
| Data odniesienia | **Dodać `campaign.ended_at`** (trigger DB `BEFORE UPDATE OF status`, zero zmian w Javie), funkcja używa `COALESCE(ended_at, updated_at)`. Alternatywa akceptowalna: zostać przy `updated_at` z udokumentowanym ryzykiem. **Decyzja właściciela (pyt. P2).** |
| Funkcja | `CREATE OR REPLACE` w NOWEJ migracji, sygnatura bez parametrów, `RETURNS void`, JOIN po `tenant_id` (bez GUC), ten sam kontrakt RAISE/`cron_log`. |
| Kolejność z DB-083 | **DB-084 pierwszy** (DB-083 jest zablokowany przez BE-139/BE-148). DB-083 MUSI użyć `ALTER FUNCTION` (nie `CREATE OR REPLACE` z kopią ciała). `CREATE OR REPLACE` zeruje `SECURITY DEFINER`/`search_path` [F, eksperyment E4]. |
| Guard/trigger w DB | Nie dodawać triggera ostrzegającego o skracaniu okresu; `CHECK` 30–730 wystarcza. Nie dodawać `CHECK` wymuszającego `ended_at` dla statusów terminalnych (zepsułby seedy i testy). |
| Ukryty koszt | Istniejące testy (`CampaignArchiveJobIntegrationTest` 7 testów z kampaniami 31–60 dni, `Db082LogCronFailureMigrationTest` 40 dni) przestaną przechodzić przy domyślnych 180 dniach — muszą zostać poprawione W DB-084, nie w BE-151. |

---

## 1. Stan faktyczny

### 1.1 Funkcja i dane
- [F] Jedyna definicja funkcji to `V015` (grep po `db/migration`; V135 tylko `COMMENT ON FUNCTION`). Żywa definicja = V015 (`SECURITY INVOKER`, `proconfig` NULL, `proacl` NULL, właściciel `ccapp`). Próg: `v_cutoff_date := NOW() - INTERVAL '30 days'`, `WHERE status IN ('COMPLETED','STOPPED') AND updated_at < v_cutoff_date`.
- [F] Java: `CampaignArchiveJobRepository#archiveCompletedCampaigns` robi `jdbc.execute("SELECT archive_completed_campaign_contacts()")` i czyta `cron_log.rows_affected` ostatniego wpisu `SUCCESS` (`ORDER BY log_id DESC LIMIT 1`). Zależy więc od: braku parametrów, `RETURNS void`, zapisu `cron_log` ze statusem `SUCCESS` i niepustym `rows_affected`. Nie zależy od treści `message`.
- [F] Funkcja NIGDY nie została uruchomiona: `scheduled_job.last_run_at` = NULL, `campaign_contact_archive` = 0 wierszy, `cron_log` bez wpisów tego joba; flaga `retention.campaign-archive.enabled=false`. W związku z tym zmiana 30 → 180 dni nie zmienia żadnego zaobserwowanego zachowania w demo. Produkcja: [Z] (brak dostępu; dane z repo wskazują to samo — pg_cron wyłączony V014, job za flagą).
- [F] Demo: 2 tenanty (`Kampania Handlowa` — 0 kampanii; `KMN Software` — 30 kampanii: 26 STOPPED + 4 COMPLETED, 37 kontaktów). `updated_at` kampanii od 2026-05-21 do 2026-07-12 (najmłodsza ma 91 dni).
- [F] Liczba kampanii kwalifikujących się (żywa baza, 2026-10-11) przy progu N dni: 30 → 30; 60 → 30; 90 → 30; 120 → 29; 150 → 0; 180 → 0; 210 → 0. Czyli po wdrożeniu DB-084 (domyślnie 180) w demo NIC nie kwalifikuje się do archiwizacji; skrócenie do ≤ 90 dni zarchiwizuje wszystkie 30 kampanii / 37 kontaktów (flaga i tak wyłączona).

### 1.2 `tenant_retention_policy` (do oceny wariantu A)
- [F] Klucz naturalny `(tenant_id, data_category)` (`uq_tenant_retention_policy`), PK `policy_id`; 4 wiersze na tenant (CONTACT_INTERACTIONS, RECORDINGS, TRANSCRIPTS, CAMPAIGN_DATA — CHECK na kategorię). `retention_months INT NOT NULL CHECK 1..120`, `auto_purge_enabled`, `updated_by` (FK `app_user` bez ON DELETE), timestamps. Brak triggera `updated_at` (ustawiane ręcznie w upsercie).
- [F] RLS: jedna polityka `tenant_retention_policy_isolation` (ALL, USING + WITH CHECK na `app.current_tenant_id`), `FORCE` = true. GRANT: `app_user` SELECT/INSERT/UPDATE/DELETE (z `ALTER DEFAULT PRIVILEGES` właściciela `ccapp`).
- [F] Kod: `TenantRetentionPolicyRepository#upsert` robi `INSERT … ON CONFLICT (tenant_id, data_category) DO UPDATE SET retention_months = EXCLUDED…, auto_purge_enabled = EXCLUDED…, updated_by = EXCLUDED…` — NADPISUJE wszystkie pola z żądania. `RetentionPolicyDto`/`UpdateRetentionPolicyRequest` to rekordy 1:1 z kolumnami; `retention.model.ts` odwzorowuje je 1:1; `listPolicies` zwraca 4 wiersze (`ORDER BY data_category`).
- [F] `updatePolicy` NIE jest `@Audited` i nie publikuje zdarzenia audytu (grep `audit` w `domain/retention` i `RetentionController`: tylko `RetentionPurgeServiceImpl` publikuje `AuditLogEvent`). **BE-151 pkt 3 powołuje się na „istniejący mechanizm audytu zmian polityk retencji" — taki mechanizm nie istnieje.**
- [F] Precedens dla ustawień per tenant w osobnych tabelach: `tenant_ai_config`, `tenant_twilio_config` (surogatowy PK + `UNIQUE (tenant_id)`, polityka RLS na GUC, FORCE). Precedens przeciwko (C): BE-116 wprost usunął `recording_retention_days` z `tenant.config`, bo „jedynym źródłem prawdy = `tenant_retention_policy`" (komentarz w `RetentionPolicyServiceImpl`).
- [F] `tenant` NIE ma RLS (`relrowsecurity = f`) — to tabela globalna zarządzana przez SUPER_ADMIN; kolumna tam byłaby poza ochroną RLS.

---

## 2. Model danych — porównanie

### (A) Nullowalna kolumna w `tenant_retention_policy`
```sql
ALTER TABLE tenant_retention_policy ADD COLUMN campaign_archive_after_days INT
  CONSTRAINT chk_trp_archive_days CHECK (campaign_archive_after_days BETWEEN 30 AND 730);
ALTER TABLE tenant_retention_policy ADD CONSTRAINT chk_trp_archive_days_category
  CHECK (campaign_archive_after_days IS NULL OR data_category = 'CAMPAIGN_DATA');
```
- Zalety [W]: zero nowych tabel/GRANT/RLS; wiersz `CAMPAIGN_DATA` istnieje u wszystkich tenantów; jedno miejsce w UI.
- Wady [F/W]: (1) kolumna sensowna dla 1 z 4 wierszy, wymaga CHECK krzyżowego (sparse column) — semantycznie bliskie „overloaded", bo wiersz-per-kategoria przestaje być jednorodny; (2) `NOT NULL DEFAULT 180` niemożliwe → NULL musi znaczyć „domyślne", czyli logika 180 w trzech miejscach; (3) **upsert nadpisuje wszystko z `EXCLUDED`** — stary klient/wywołanie `PUT /policies/CAMPAIGN_DATA` bez nowego pola po cichu wyzeruje okres (albo wymaga przepisania upsertu na częściowy); (4) `RetentionPolicyDto` dostaje pole `null` dla 3 kategorii, `retention.model.ts` zmienia się, `listPolicies`/`updatePolicy` kontrakt się rozszerza; (5) `seedDefaultPolicies` (BE-111) musi wypełniać nowe pole; (6) kategorie TRANSCRIPTS/RECORDINGS/CONTACT_INTERACTIONS niosą martwą kolumnę na zawsze; (7) funkcja archiwizacji musiałaby filtrować `data_category = 'CAMPAIGN_DATA'` w joinie.

### (B) Dedykowana tabela — REKOMENDOWANE
```sql
CREATE TABLE tenant_campaign_archive_setting (
    tenant_id          UUID PRIMARY KEY REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    archive_after_days INT NOT NULL DEFAULT 180
        CONSTRAINT chk_tcas_archive_after_days CHECK (archive_after_days BETWEEN 30 AND 730),
    updated_by         UUID REFERENCES app_user(user_id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE tenant_campaign_archive_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_campaign_archive_setting FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_campaign_archive_setting_isolation ON tenant_campaign_archive_setting
    USING      (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);
COMMENT ON TABLE  ... 'Ustawienie PER TENANT: po ilu DNIACH od zakończenia kampanii COMPLETED/STOPPED jej kontakty są przenoszone do campaign_contact_archive. Brak wiersza = 180. NIE jest to retencja CAMPAIGN_DATA (miesiące od archived_at, tenant_retention_policy).';
COMMENT ON COLUMN ...archive_after_days IS 'JEDNOSTKA: DNI (30..730, domyślnie 180). Nie mylić z tenant_retention_policy.retention_months (MIESIĄCE).';
```
- Zalety [W]: czysty model (jedna kolumna = jedno znaczenie, jednostka w nazwie i komentarzu), `NOT NULL` + `DEFAULT 180` możliwe, brak ryzyka nadpisania przez istniejący upsert, istniejący kontrakt `/policies` nietknięty (zero ryzyka dla klientów), prosty `ON CONFLICT (tenant_id)` w BE-151, własna polityka RLS wg wzorca V082/V125 (nie `app.tenant_id`), tabela ~1 wiersz/tenant (indeks poza PK zbędny — PK pokrywa JOIN).
- Wady [W]: nowa encja + repozytorium (`TenantAwareRepository`) + serwis/DTO w BE-151; osobny zasób API; FE-113 wymaga nowej metody w `RetentionService` (ale NIE zmienia istniejących modeli).
- Wybór PK = `tenant_id` zamiast surogatu (precedens `tenant_ai_config`: `id` + UNIQUE): nikt nie referencjonuje wiersza, relacja 1:1, `@Id` = `tenant_id` upraszcza encję; zgodne z konwencją, że nowe tabele mają PK nazwany jednoznacznie (`tenant_id` wskazuje relację). [W] Jeśli zespół woli spójność z `tenant_ai_config`, wariant `id UUID PK` + `UNIQUE (tenant_id)` jest równoważny funkcjonalnie.
- GRANT: `app_user` dostaje SELECT/INSERT/UPDATE/DELETE automatycznie z `pg_default_acl` właściciela `ccapp` [F, eksperyment E1 — `app_user: DELETE,INSERT,SELECT,UPDATE`]. Jawny `GRANT` w migracji niepotrzebny; test na `has_table_privilege` jest zalecany (DB-083 zmienia `ALTER DEFAULT PRIVILEGES`).
- Brak wiersza = domyślne 180: bez backfillu (patrz 2.1) — efektywny okres wszystkich istniejących tenantów to 180 od momentu wdrożenia funkcji, bez zapisywania wierszy. Domyślne 180 żyje w 3 miejscach (DDL `DEFAULT`, `COALESCE` w funkcji, stała w Javie BE-151) — [W] test migracyjny sprawdza spójność DDL ↔ funkcja (odczyt `pg_attrdef` i wynik funkcji na tenancie bez wiersza).

### (C) Kolumna w `tenant`
[W] Odrzucić: tabela globalna bez RLS, konfiguracja platformy (limity, strefa) zarządzana przez SUPER_ADMIN, ADMIN tenanta nie ma jej edytować; BE-116 świadomie usunął retencję z `tenant.config`; trigger `trg_tenant_updated_at` i `chk_tenant_config_*` to inna domena; `UPDATE tenant` wymagałby uprawnień poza zakresem endpointu ADMIN.

### (D) Inne
- Klucz–wartość `tenant_setting(tenant_id, key, value jsonb)` [W]: odrzucić — brak typowanego `CHECK`, over-engineering dla jednego ustawienia.
- Tabela ogólna `tenant_retention_setting` z wieloma ustawieniami [W]: przedwczesne uogólnienie; jeśli pojawi się drugie ustawienie tego typu, można dodać kolumnę do B.

**REKOMENDACJA: (B).** Uzasadnienie: koszt (1 tabela + 1 encja) jest jednorazowy i niski, a (A) wprowadza ryzyko cichego zerowania przez upsert oraz kolumnę o znaczeniu zależnym od wartości innej kolumny; (C) łamie granicę tenant/platforma.

### 2.1 Backfill (kryterium DB-084)
[W] Zamiast `INSERT … SELECT` dla wszystkich tenantów (wiersze, które tylko powielają domyślne i „zamrażają" 180) zaproponować: brak wiersza = 180; test „backfill" sprowadza się do: tenant sprzed migracji bez wiersza → funkcja stosuje 180. Kryterium DB-084 „Backfill: każdy istniejący tenant ma efektywny okres 180 dni" pozostaje spełnione. Jeśli właściciel woli jawne wiersze: `INSERT INTO … SELECT tenant_id FROM tenant ON CONFLICT DO NOTHING` (idempotentne) + seed w `TenantServiceImpl` — wtedy funkcja i tak musi mieć `COALESCE` (wyścig przy tworzeniu tenanta), więc korzyść jest kosmetyczna.

### 2.2 Wpływ na kod (wariant B)
- Backend: nowa encja/repo/serwis + `GET/PUT /api/tenants/{tenantId}/retention/campaign-archive` (propozycja nazwy zasobu) w `RetentionController`; istniejące `RetentionPolicyDto`, `UpdateRetentionPolicyRequest`, `TenantRetentionPolicyRepository#upsert`, `seedDefaultPolicies` BEZ zmian. [W]
- Frontend: nowy interfejs w `retention.model.ts` (np. `CampaignArchiveSettingDto { archiveAfterDays; isDefault; defaultDays; minDays; maxDays }`) i metody w `RetentionService`; istniejące interfejsy bez zmian.
- Pod `app_user`: BE-151 zapisuje przez `TenantAwareRepository` (`setTenantContextInDb`), więc `WITH CHECK` jest spełnione; test E2 potwierdza izolację (rozdz. 6).

---

## 3. Data odniesienia: `updated_at` vs `ended_at`

### 3.1 Fakty
- [F] `campaign.updated_at` nullowalne, ustawiane przez DWA mechanizmy: JPA `@PreUpdate` (`Campaign#onUpdate`) oraz trigger DB `trg_campaign_updated_at` (`BEFORE UPDATE`, `fn_set_updated_at` = `NEW.updated_at := NOW()`) — **każdy UPDATE wiersza `campaign`, także natywny SQL, także z migracji**.
- [F] Status COMPLETED/STOPPED zapisuje się BEZ osobnej daty; `campaign` nie ma `ended_at`/`completed_at`/`stopped_at`. Brak tabeli historii statusów (`\dt *status_hist*` puste), `audit_log` nie ma żadnych wpisów o kampaniach (0 wierszy; metody `CampaignServiceImpl` nie są `@Audited`). **Daty zakończenia nie da się wyprowadzić z istniejących danych** poza proxy `updated_at`.
- [F] Ścieżki zapisu `campaign` w Javie: `createCampaign`; `updateCampaign` (TYLKO status DRAFT — `InvalidOperationException` w pozostałych); `startCampaign` (dozwolone ze statusów DRAFT, SCHEDULED, PAUSED); `pauseCampaign` (RUNNING); `stopCampaign` (RUNNING/PAUSED/SCHEDULED → STOPPED); `revertToDraft` (SCHEDULED); `CampaignWindowActivator` (SCHEDULED → RUNNING oraz RUNNING/PAUSED → COMPLETED po `end_date`); natywny `UPDATE campaign SET all_agents = …, updated_at = NOW()` w `CampaignAssignmentRepository#setAllAgents` wołany z `PUT` przypisania agentów (`CampaignAssignmentServiceImpl#updateAssignment`).
- [F] **STOPPED i COMPLETED są w obecnym kodzie stanami terminalnymi** — `startCampaign` nie przyjmuje STOPPED, żadna inna ścieżka nie wychodzi z tych statusów. Zdanie w DB-084 („`STOPPED` może być wznawiana", pkt 4 i R3) jest **nieprawdziwe wobec kodu** [W: poprawić]; CHECK w bazie niczego nie zabrania, ale Java tak.
- [F] **Realna ścieżka fałszywego restartu zegara:** `updateAssignment` sprawdza tylko istnienie kampanii (`findCampaignOrThrow`), NIE status — edycja przypisania agentów kampanii STOPPED/COMPLETED wykonuje `UPDATE campaign … updated_at = NOW()` i restartuje zegar. Eksperyment E3: po przejściu RUNNING → STOPPED `ended_at = updated_at`; późniejszy UPDATE `all_agents` zrestartował `updated_at` (≈ now), `ended_at` pozostał nietknięty.
- [F] Druga ścieżka (strukturalna): każde masowe `UPDATE campaign` w przyszłej migracji lub ręczny SQL restartuje zegar WSZYSTKICH zakończonych kampanii (trigger). Eksperyment E3a: naiwny backfill `UPDATE campaign SET ended_at = updated_at …` ustawił `updated_at` wszystkich 30 kampanii na „teraz" (0 starszych niż 1 dzień po UPDATE vs 30 przed) — pułapka przy własnym backfillu.
- [W] Kierunek błędu `updated_at`: zawsze w stronę PÓŹNIEJSZEJ archiwizacji (dane zostają dłużej w tabeli operacyjnej) — bez ryzyka przedwczesnego usunięcia/ukrycia; cena: nieprzewidywalność i niezgodność z opisem w GUI („dni od zakończenia kampanii").
- [F] DB-075 (N/A, D6 = `archived_at`) planował `campaign_ended_at` z backfillem z `campaign.updated_at` — istnienie `campaign.ended_at` ułatwiłoby ten ticket, gdyby decyzja D6 kiedyś wróciła (nieistotne dla DB-084, tylko spójność nazewnictwa).

### 3.2 Opcja `ended_at` — szkic i koszt
```sql
ALTER TABLE campaign ADD COLUMN ended_at TIMESTAMPTZ;            -- nullable, brak DEFAULT = zmiana metadanych, brak przepisywania tabeli
COMMENT ON COLUMN campaign.ended_at IS 'Moment przejscia do COMPLETED/STOPPED (trigger fn_campaign_set_ended_at); NULL dla statusow nieterminalnych i dla wierszy sprzed migracji bez backfillu. Podstawa okresu archiwizacji kontaktow.';

CREATE FUNCTION fn_campaign_set_ended_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status IN ('COMPLETED','STOPPED') AND OLD.status NOT IN ('COMPLETED','STOPPED') THEN
    NEW.ended_at := NOW();
  ELSIF NEW.status NOT IN ('COMPLETED','STOPPED') AND OLD.status IN ('COMPLETED','STOPPED') THEN
    NEW.ended_at := NULL;          -- gdyby w przyszlosci dodano wznowienie
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_campaign_ended_at BEFORE UPDATE OF status ON campaign
  FOR EACH ROW EXECUTE FUNCTION fn_campaign_set_ended_at();

-- backfill: TYLKO z wylaczonym triggerem updated_at (inaczej resetuje updated_at wszystkich kampanii)
ALTER TABLE campaign DISABLE TRIGGER trg_campaign_updated_at;
UPDATE campaign SET ended_at = updated_at WHERE status IN ('COMPLETED','STOPPED') AND ended_at IS NULL;
ALTER TABLE campaign ENABLE TRIGGER trg_campaign_updated_at;
```
- [F] Zweryfikowane w E3 (ROLLBACK): trigger ustawia `ended_at` przy RUNNING → STOPPED (równe `updated_at`); backfill z `DISABLE TRIGGER` zachował `updated_at` (30/30 starszych niż 1 dzień) i dał `ended_at = updated_at` dla 30/30.
- [W] Trigger zamiast zmian w Javie: obejmuje WSZYSTKIE ścieżki (`stopCampaign`, `CampaignWindowActivator`, natywny SQL) bez dotykania `CampaignServiceImpl`/encji (Hibernate ignoruje nieznaną kolumnę; encja nie musi jej mapować — jeśli BE chce ją pokazywać w API, to osobna, opcjonalna zmiana). Hibernate zapisuje wszystkie kolumny w `UPDATE`, więc trigger celowo porównuje `OLD.status` z `NEW.status` (reaguje tylko na przejście).
- [W] NIE dodawać triggera na `INSERT` ani `CHECK (status terminalny → ended_at NOT NULL)`: testy i seedy wstawiają kampanie od razu ze statusem COMPLETED i ustawionym `updated_at` (np. `CampaignArchiveJobIntegrationTest#campaign`); funkcja używa `COALESCE(ended_at, updated_at)`, więc takie wiersze działają jak dziś.
- Koszt: 1 migracja (kolumna + funkcja + trigger + backfill), ~15 linii SQL, test migracyjny (przejście statusu, brak resetu przy `all_agents`, backfill nie rusza `updated_at`, `COALESCE` dla NULL); locki: `ADD COLUMN` nullable bez DEFAULT w PG16 = chwilowy ACCESS EXCLUSIVE, `DISABLE TRIGGER` = SHARE ROW EXCLUSIVE na krótko (tabela `campaign` mała) — `SET LOCAL lock_timeout`. Zero zmian w Javie. Korzyść dla ISTNIEJĄCYCH wierszy: żadna (backfill = proxy `updated_at`, może zawierać już restart) — korzyść dotyczy kampanii kończonych po wdrożeniu.
- Alternatywa „zostać przy `updated_at`": koszt 0; wymaga zapisania w DB-084/BE-151/FE-113, że okres liczy się „od ostatniej modyfikacji kampanii (zwykle zakończenia)" oraz poprawienia etykiety w GUI; ryzyko R3 realne (edycja przypisań po zakończeniu). [W] Akceptowalne, ale etykieta „od zakończenia kampanii" byłaby wtedy nieprawdziwa.

**Rekomendacja [W]:** `ended_at` przez trigger, jako OSOBNA migracja w zakresie DB-084 (zmiana poza funkcją, ale bez Javy, więc nie wymusza osobnego ticketu BE), pod warunkiem akceptacji właściciela (ticket wymaga uzgodnienia). Złożoność DB-084: M zostaje (górna granica), jeśli `ended_at` wchodzi.

---

## 4. Zmiana funkcji `archive_completed_campaign_contacts()`

### 4.1 Szkic (zweryfikowany w E5 na żywej bazie w transakcji z ROLLBACK)
- Pętla: `SELECT c.campaign_id, c.tenant_id, c.name, c.status, COALESCE(s.archive_after_days,180) AS archive_after_days FROM campaign c LEFT JOIN tenant_campaign_archive_setting s ON s.tenant_id = c.tenant_id WHERE c.status IN ('COMPLETED','STOPPED') AND COALESCE(c.ended_at, c.updated_at) < NOW() - make_interval(days => COALESCE(s.archive_after_days,180))`. Brak `current_setting` — join po `tenant_id` (spełnia DB-084 pkt 5/R5).
- Reszta bez zmian: `INSERT … ON CONFLICT (record_id, campaign_id) DO NOTHING`, warunek `IF v_batch_count > 0 OR NOT EXISTS …` przed `DELETE`, próba `DROP` partycji, `cron_log` (`rows_affected` = suma skopiowanych), `UPDATE scheduled_job`. `RETURNS void`, brak parametrów. Blok `EXCEPTION WHEN OTHERS … RAISE` zachować (martwy, ale kontrakt; DB-082) — po przepisaniu ciała nie ruszać jego treści.
- Komunikat `cron_log.message`: zamiast `Prog: > <jedna data>` np. `Kampanie: n, Rekordy: m, Okres archiwizacji (dni, min/max z zarchiwizowanych): a/b` (zmienne `v_min_days`/`v_max_days`); `Java` czyta tylko `rows_affected`.
- [F] Znana, odziedziczona cecha: pętla iteruje po WSZYSTKICH kwalifikujących się kampaniach w każdym przebiegu (kampania COMPLETED/STOPPED nie znika z `campaign`), więc „Kampanie: N" w `cron_log` liczy też kampanie już opróżnione (E5: 2. przebieg `Kampanie: 6, Rekordy: 0`). [W] Można dodać `AND EXISTS (SELECT 1 FROM campaign_contact …)` — poza zakresem, nie zmieniać bez potrzeby (zmienia semantykę licznika).
- `COMMENT ON FUNCTION` zaktualizować (próg per tenant, domyślne 180, 30–730, `COALESCE(ended_at, updated_at)`, różnica względem retencji `CAMPAIGN_DATA`; zachować ostrzeżenie DB-082 o martwym bloku `EXCEPTION`).
- [F] `CREATE OR REPLACE` zachowuje właściciela, ACL i komentarz, ale RESETUJE `SECURITY DEFINER` i `SET search_path` (E4: `prosecdef t → f`, `proconfig {search_path=…} → NULL`).

### 4.2 Wyniki E5 (2 tenanty; B = KMN Software bez wiersza ustawienia = 180; A ma ustawienie 30)
- A: kampania STOPPED zakończona 100 dni temu (2 kontakty) → zarchiwizowana przy 30; COMPLETED 20 dni temu (1 kontakt) i RUNNING (1) → nietknięte.
- B: 5 kampanii „zakończonych 200 dni temu" (6 kontaktów) → zarchiwizowane przy domyślnych 180; pozostałe 25 (91–143 dni) → nietknięte.
- Jeden przebieg: `Kampanie: 6, Rekordy: 8` (zgodne: 2 + 6); archiwum: A=2, B=6; operacyjne po: A=2, B=31. Drugi przebieg: `Rekordy: 0` (idempotencja).
- Skrócenie B 180 → 30: kolejny przebieg przeniósł pozostałe 31 kontaktów wszystkich kampanii B naraz (ryzyko R2 potwierdzone: bez ostrzeżenia to jedna transakcja na całą zaległość; pomiar czasu/WAL dla dużych wolumenów — BE-120 progi 30 s — [Z], tu tylko 37 rekordów).

### 4.3 Kompatybilność i kolejność względem DB-083
- [F] DB-083 pkt 3(v) planuje `archive_completed_campaign_contacts()` jako `SECURITY DEFINER` (+ funkcja-czujnik). Konflikt faktyczny jest jeden: **`CREATE OR REPLACE` w drugiej z migracji cofnie atrybuty/treść pierwszej.** Przypadki:
  1. DB-084 pierwszy, DB-083 drugi: DB-083 MUSI użyć `ALTER FUNCTION archive_completed_campaign_contacts() SECURITY DEFINER SET search_path = public, pg_temp OWNER TO …` (ticket DB-083 pkt 3(iii) już używa `ALTER FUNCTION` dla innych funkcji) i NIE kopiować ciała z V015/`pg_get_functiondef` sprzed DB-084; w przeciwnym razie wróci próg 30 dni — cicha regresja.
  2. DB-083 pierwszy, DB-084 drugi: `CREATE OR REPLACE` w DB-084 MUSI jawnie powtórzyć `SECURITY DEFINER SET search_path = public, pg_temp` (odczytać atrybuty z `pg_proc` w dniu implementacji), inaczej po cichu zdegraduje definera do invokera (test wykrywa: asercja `prosecdef`).
- [W] **Kolejność: DB-084 przed DB-083** (DB-083 zależy od ⬜ BE-139 i BE-148; DB-084 nie ma zależności blokujących; numery V136+ są wolne na wszystkich gałęziach lokalnych — sprawdzone `git ls-tree` po gałęziach, ostatnia = V135 — ale ponownie sprawdzić tuż przed startem, `flyway_schema_history` max = 135).
- [W] Testy krzyżowe (dopisać do obu ticketów): (a) w DB-084 test sprawdza, że ciało funkcji używa ustawienia per tenant (zachowanie dwóch tenantów z różnymi progami) oraz zapisuje stan `prosecdef`/`proconfig` (jeśli istnieje) i go zachowuje; (b) w DB-083 test czujnika kandydatów ma dane z DWÓCH tenantów o RÓŻNYCH progach i sprawdza parytet: liczba kandydatów czujnika = liczba kampanii zarchiwizowanych w jednym przebiegu; predykat czujnika identyczny z pętlą (per tenant, `COALESCE(ended_at, updated_at)`). Nie wystawiać predykatu jako VIEW: widok właściciela z BYPASSRLS czytany przez `app_user` omija RLS (domyślnie nie jest `security_invoker`) [W, wiedza ogólna PG, niezweryfikowane eksperymentem].
- [W] Pod rolą `NOBYPASSRLS` bez GUC: `campaign` widoczne = 0 → pętla pusta (jak dziś, BE-120). LEFT JOIN do ustawienia przy częściowym kontekście (GUC jednego tenanta) widzi i kampanie, i ustawienie tego samego tenanta (ta sama polityka) — spójnie; jeśli ustawienie byłoby niewidoczne, `COALESCE` daje 180 = bezpieczniejsza (dłuższa) strona.

### 4.4 Ryzyka zmiany zachowania
- 30 → 180 dla istniejących: [F] brak efektu zaobserwowanego (funkcja nigdy nie uruchomiona; w demo flaga OFF). Zmiana to decyzja R1 właściciela.
- **Regresja testów [F]:** `CampaignArchiveJobIntegrationTest` tworzy kampanie 31/40/45/46/50/60 dni (przy 180 nic się nie zarchiwizuje) i `Db082LogCronFailureMigrationTest` kampanię sprzed 40 dni (wymuszona awaria triggerem — przy 180 pętla nie wejdzie i test nie zobaczy awarii). Do poprawy w DB-084: wstawić wiersz ustawienia 30 dla tenantów testowych albo postarzyć kampanie > 180 dni. BE-151 AC „CampaignArchiveJobIntegrationTest (7) zielone" przenieść do DB-084 jako „po jawnej poprawce testów". Też `Db076ScheduledJobReconciliationMigrationTest` — sprawdzić (porównuje definicje `pg_get_functiondef` w V133 — [Z], nie otwierano).
- Wpływ na demo: flaga OFF; funkcja nie wykona się sama; ręcznie: przy 180 → 0 kwalifikujących się (rozdz. 1.1).

---

## 5. Guard/ostrzeżenie w DB — ocena

- `CHECK` 30–730 + `NOT NULL`: wystarczy [F, E1: 29 i 731 odrzucone, NULL odrzucony, 30/180/730 przyjęte].
- Trigger ostrzegający przy skracaniu okresu: [W] NIE — baza nie zna intencji, `RAISE NOTICE` nikt nie zobaczy; ostrzeżenie i potwierdzenie to FE-113, audyt zmian BE-151.
- „Limit kampanii na przebieg"/bezpiecznik masowej archiwizacji: [W] nie w tym ticketcie; jeśli pomiar BE-120 (> 30 s) wykaże problem, osobny wariant per kampania.
- Trigger `fn_campaign_set_ended_at` (3.2) to nie guard, tylko źródło prawdy dla daty — decyzja właściciela.
- Wniosek: żadnego dodatkowego guardu poza `CHECK`/RLS.

---

## 6. Eksperymenty (żywa baza, `docker exec cc-postgres psql -U ccapp -d contact_center`, wszystko w `BEGIN … ROLLBACK`)

Pliki robocze (nie w repo): `/tmp/claude-1000/-home-pawelm-contact-center/135444aa-5bd7-4fd4-87e6-79fc5557dd79/scratchpad/exp1.sql … exp4.sql`; uruchomienie: `docker exec -i cc-postgres psql -U ccapp -d contact_center -f - < expN.sql`.
- **E1 (tabela B, CHECK, GRANT):** `CREATE TABLE tenant_campaign_archive_setting …` + ENABLE/FORCE RLS + polityka. Wynik: `relrowsecurity=t, relforcerowsecurity=t`; `app_user` SELECT/INSERT/UPDATE/DELETE z default privileges; INSERT 29 → naruszenie `chk_tcas_archive_after_days`, 731 → naruszenie, NULL → naruszenie NOT NULL; 30, 180 (UPDATE), 730 (UPDATE) przyjęte; `INSERT (tenant_id)` bez wartości → 180.
- **E2 (RLS pod `SET LOCAL ROLE app_user`):** przy GUC = tenant A: SELECT widzi tylko wiersz A; INSERT wiersza B → `new row violates row-level security policy` (42501); UPDATE wiersza B → `UPDATE 0`; upsert własnego wiersza A przeszedł. Uwaga [F]: odczyt `current_setting('app.current_tenant_id',true)::uuid` na połączeniu, które wcześniej ustawiało GUC, daje `invalid input syntax for type uuid: ""` (22P02) — znane zachowanie placeholdera GUC na połączeniach z puli, nieszkodliwe dla nowej tabeli (ten sam wzorzec co wszystkie tabele tenantowe; zob. BE-139).
- **E3 (ended_at):** ADD COLUMN, trigger, backfill z/bez `DISABLE TRIGGER`, scenariusz RUNNING → STOPPED → `UPDATE … all_agents` (wyniki w rozdz. 3).
- **E4 (CREATE OR REPLACE vs atrybuty):** funkcja testowa `SECURITY DEFINER SET search_path`, właściciel zmieniony, `REVOKE PUBLIC`, `GRANT app_user`, komentarz → po `CREATE OR REPLACE` bez klauzul: właściciel, ACL, komentarz zachowane; `prosecdef=f`, `proconfig=NULL`.
- **E5 (funkcja per tenant):** nowa definicja przez `CREATE OR REPLACE` w transakcji + dane testowe (wyniki w 4.2); po ROLLBACK: brak tabeli/kolumny/funkcji zmienionej (`campaign_contact`=37, `campaign_contact_archive`=0, `campaign`=30; `pg_get_functiondef` = V015 niezmienione).
- **Skutki uboczne nieodwracalne [F]:** sekwencja `cron_log.log_id` przesunęła się o 3 (wywołania funkcji w E5; `nextval` nie podlega ROLLBACK) — wpisów brak; role/tabele/funkcje testowe wycofane. Eksperymenty DDL na `campaign` (E3/E5) wymagały krótkiego ACCESS EXCLUSIVE na `campaign` w czasie trwania transakcji (sekundy, `lock_timeout` 3 s).
- **Niezweryfikowane [Z]:** pełny łańcuch Flyway na pustej bazie (Testcontainers), zachowanie przy dużych wolumenach (czas/WAL), produkcja, `Db076ScheduledJobReconciliationMigrationTest` po zmianie funkcji, zachowanie pod rolą `cc_app` (DB-083/BE-139).

---

## 7. Pytania do właściciela (z opcjami)

- **P1 — model danych:** (B) osobna tabela `tenant_campaign_archive_setting` [rekomendowane]; (A) kolumna w `tenant_retention_policy` (mniej kodu, ryzyko cichego zerowania przez upsert, kolumna sensowna dla 1 z 4 wierszy). (C) odpada.
- **P2 — data odniesienia:** (1) dodać `campaign.ended_at` przez trigger DB (osobna migracja, bez zmian w Javie, backfill = `updated_at` dla istniejących) [rekomendowane]; (2) zostać przy `updated_at` i zmienić etykietę w GUI na „od ostatniej modyfikacji kampanii" (edycja przypisań agentów po zakończeniu restartuje zegar).
- **P3 — backfill:** brak wiersza = 180 (bez zapisu wierszy) [rekomendowane]; albo jawne wiersze 180 dla wszystkich tenantów + seed dla nowych.
- **P4 — wznawianie STOPPED:** czy planowane jest wznawianie kampanii STOPPED/COMPLETED? Dziś kod tego nie pozwala. Jeśli tak, trigger `ended_at` już czyści datę przy wyjściu ze stanu terminalnego, ale reguła „wznowiona kampania nie jest archiwizowana" zostaje.
- **P5 — podgląd skutków skrócenia:** czy dodać w BE-151 `GET` zwracający liczbę kampanii/kontaktów, które zostałyby zarchiwizowane przy nowej wartości (potwierdzenie w FE-113 z konkretną liczbą zamiast ogólnego ostrzeżenia)? Rekomendacja: tak, jeśli rozmiar BE-151 pozostanie M.
- **P6 — kolejność:** DB-084 przed DB-083 (rekomendowane) — potwierdzenie, że DB-083 zmienia atrybuty funkcji przez `ALTER FUNCTION`.

---

## 8. Proponowane poprawki treści ticketów

**DB-084**
- Kontekst i pkt 4/R3: skorygować „`STOPPED` może być wznawiana" i „zmienia KAŻDA edycja (nazwa, harmonogram…)" — w kodzie STOPPED/COMPLETED są terminalne, edycja nazwy/harmonogramu tylko w DRAFT; rzeczywiste źródła restartu zegara: `PUT` przypisania agentów (`setAllAgents`, bez sprawdzenia statusu) i masowe `UPDATE campaign` w migracjach/ręcznym SQL (trigger `trg_campaign_updated_at`).
- Pkt 1: zapisać wynik WP-1 (rekomendacja B; nazwa `tenant_campaign_archive_setting`, kolumna `archive_after_days`, PK `tenant_id`, `ON DELETE SET NULL` dla `updated_by`).
- Pkt 2 / AC „Backfill": „każdy istniejący tenant ma efektywny okres 180 dni" — doprecyzować: brak wiersza = 180 (test: tenant bez wiersza); dodać test spójności `DEFAULT` w DDL ↔ `COALESCE` w funkcji.
- Dodać do zakresu poprawkę istniejących testów (`CampaignArchiveJobIntegrationTest`, `Db082LogCronFailureMigrationTest`, sprawdzić `Db076…`) — przy domyślnych 180 dniach kampanie 31–60-dniowe przestają się kwalifikować.
- Pkt 3: dodać wymóg zachowania atrybutów funkcji (`SECURITY DEFINER`/`search_path` jeśli DB-083 wszedł pierwszy) i test; komentarz `COMMENT ON FUNCTION` z zachowaniem ostrzeżenia DB-082.
- Jeśli właściciel wybierze `ended_at`: dodać do zakresu osobną migrację (kolumna + trigger + backfill z `DISABLE TRIGGER`), AC: test przejścia statusu, test braku resetu przy `all_agents`, test backfillu niezmieniającego `updated_at`, `COALESCE` dla NULL. Złożoność: M (górna granica). Kolejność migracji: tabela → `ended_at` → funkcja (jedna zmiana = jedna migracja).
- R4: dopisać rozstrzygnięcie kolejności (DB-084 pierwszy; DB-083 przez `ALTER FUNCTION`; testy krzyżowe z rozdz. 4.3).

**BE-151**
- Pkt 3 „wg istniejącego mechanizmu audytu zmian polityk retencji": taki mechanizm nie istnieje (`updatePolicy` bez `@Audited`/zdarzenia) — zapisać, że audyt trzeba DODAĆ (`AuditLogService#publishAuditEvent` jak w `RetentionPurgeServiceImpl`, albo `@Audited` z wpisem w `ENTITY_CLASS_MAP`), z polami stara → nowa wartość i flagą „skrócenie okresu". Może to podnieść złożoność z M do M+ (nadal M).
- Pkt 1: przy wyborze B kształt = osobny zasób `GET/PUT /api/tenants/{tenantId}/retention/campaign-archive` (propozycja), odpowiedź: `{ archiveAfterDays, isDefault, defaultDays: 180, minDays: 30, maxDays: 730 }`; istniejący `PUT /policies/{category}` i `RetentionPolicyDto` bez zmian (usunąć z pkt 1 wariant „pole przy `CAMPAIGN_DATA`").
- Pkt 6 (seedowanie) — nieaktualny przy B (brak wiersza = domyślne); usunąć.
- AC „CampaignArchiveJobIntegrationTest (7) zielone" — przenieść odpowiedzialność za poprawkę testów do DB-084; w BE-151 dodać test integracyjny „zmiana okresu przez API → inny wynik joba" (już jest).
- Pkt 4: Javadoc `CampaignArchiveJob` (linie z „30 dni") oraz opis `scheduled_job.description` w V133 („> 30 dni") — ten drugi to dane, nie kod; opis w DB zdryfuje (DB-R10 w CR-DATABASE.md) — osobna migracja `UPDATE scheduled_job SET description` w DB-084 (opcjonalnie).
- Opcjonalnie P5: endpoint podglądu skutków.

**FE-113**
- Rozmiar S → S (z potwierdzeniem i podglądem) lub S/M, jeśli wejdzie P5. Zależność od BE-151 bez zmian.
- Przy B pole to osobna sekcja/karta (wizualnie przy „Dane kampanii"), nowe typy w `retention.model.ts` i nowe metody w `RetentionService`; istniejące `RetentionPolicyDto`/`UpdateRetentionPolicyRequest` nietknięte — dopisać do zakresu.
- Etykieta zależna od P2: „od zakończenia kampanii" (jeśli `ended_at`) albo „od ostatniej modyfikacji kampanii" (jeśli `updated_at`).
