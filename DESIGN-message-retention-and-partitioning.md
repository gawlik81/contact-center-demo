# Projekt: Retencja treści wiadomości, domknięcie martwych harmonogramów i partycjonowanie tabel wiadomości (EPIC-30)

Status: **projekt do akceptacji** (nie wdrożone). Decyzje D1–D8 (§3) czekają na właściciela produktu — tickety mają działać przy
**założeniach domyślnych** oznaczonych „ZAŁOŻENIE DO POTWIERDZENIA"; przy alternatywie zmienia się wskazany zakres, nie kolejność prac.
Analiza: 2026-09-20 (PostgreSQL 16.13, schemat po V093, baza demo 28 MB; tylko odczyt, bez zmian w bazie i repo).
Powiązane: `DESIGN-data-retention-partitioning.md` (EPIC-29 — silnik retencji), `PRD.md` §6.5 (NFR-RODO01/02/03), `ARCHITECTURE.md` §4/§6.6,
`documentation/tech/06-database.md`. Tickety: `TASKS-DATABASE.md` DB-056…077, `TASKS-BACKEND.md` BE-120…140, `TASKS-FRONTEND.md` FE-110…112.

## 1. Cel

EPIC-29 dał silnik retencji dla `contact*`, ale: (a) treść wiadomości e-mail/social **nigdy** nie jest usuwana (luka RODO), (b) kilka
harmonogramów istnieje wyłącznie jako funkcje SQL/metody bez wykonawcy, (c) jedyni realni kandydaci do partycjonowania czasowego
(`email_message`, `social_message`) mają klucze uniemożliwiające partycjonowanie, (d) RLS/rola połączenia nigdy nie były sprawdzone
pod rolą bez BYPASSRLS. Kolejność: **Poziom 1 (usuwanie wierszy i obiektów S3 per tenant) przed Poziomem 2 (DROP partycji)**.

## 2. Ustalenia z analizy (z dowodami)

Oznaczenia: „live" = zapytanie tylko-do-odczytu na `cc-postgres` (2026-09-20); „kod" = grep/odczyt repo na gałęzi `partycjonowanie-2`.

| # | Ustalenie | Dowód | Skutek |
|---|---|---|---|
| U1 | Purge kontaktu **odcina**, a nie usuwa wiadomości | kod: `RetentionPurgeServiceImpl#purgeContactInteractions` → `EmailMessageService/SocialMessageService#detachContactReferences` = `UPDATE … SET contact_id = NULL`; live: 23 z 55 maili ma `contact_id IS NULL` (analiza: 18/18 sprzed cutoffu 2026-05-13, 10 z `body_html`) | PII (`subject`, `body_*`, adresy, `content`) zostaje na stałe; wiadomości osierocone nie mają żadnej ścieżki usunięcia |
| U2 | Przepływ RODO w aplikacji jest w **Javie** i nie obejmuje wiadomości | kod: `GdprController` → `GdprServiceImpl`; `anonymizeCustomer` = S3 nagrania + `CustomerRepository#anonymize` (UPDATE wyłącznie `customer`); eksport = `customer.json` + `contacts.json` (≤ 1000). Funkcje SQL `anonymize_customer`/`export_customer_data` (V013/V017): **0 wywołań** w `backend/`, `frontend/`, `voicebot/` | D3 dotyczy przepływu Java, nie tylko funkcji SQL; Art. 17/15 nie dotyka wiadomości, `scheduled_callback`, `campaign_contact*`, `contact.notes`, transkrypcji |
| U3 | `export_customer_data` jest `STABLE` i robi `INSERT INTO audit_log` | live: `pg_proc.provolatile='s'`; kod V017; zachowanie PG „INSERT is not allowed in a non-volatile function" zreprodukowane na funkcji sondującej w `pg_temp` (właściwa funkcja **nie** została wywołana) | eksport SQL prawie na pewno nie działa; do potwierdzenia w DB-061 |
| U4 | `anonymize_customer` szuka wiadomości wyłącznie przez `contact_id IN (SELECT contact_id FROM contact WHERE customer_id = …)` | kod V013 | po U1 wiadomości są nieosiągalne; brak: `scheduled_callback` (phone, first_name, last_name, notes), `campaign_contact`/`_archive` (phone, first_name, last_name, email, custom_fields), `contact.notes`, `contact_transcription.content`, `contact_ai_summary` |
| U5 | Załączniki e-mail leżą w S3, a **nic ich nie usuwa** | kod: `EmailAttachmentStorageService` (klucze `email-attachments/{tenantId}/{messageId}/…`, pending: `…/{tenantId}/pending/{uuid}/…`) — brak metody delete; klucz w JSONB `attachments` to `s3_key` (komentarz V010 i javadoc `EmailMessage` mówią `s3_url` — nieaktualne); EML wiadomości = `contact.recording_url` (kategoria RECORDINGS); social: URL-e platform, bez S3 | `DROP` partycji nie usuwa S3 → Poziom 1 musi sprzątać S3 przed dropem; pending-uploady porzucone przez agenta nie mają TTL |
| U6 | Partycjonowanie czasowe blokują klucze | live: `pk_email_message (message_id)`, `uq_email_message_id_header (tenant_id, message_id_header)` (DEFERRABLE), `pk_social_message`, `uq_social_message_external_id (tenant_id, external_message_id)`; scratch: „unique constraint on partitioned table must include all partitioning columns". `email_message`: `received_at` NULL dla OUTBOUND (live: 25 OUT z `sent_at`, 30 IN z `received_at`), `created_at` niedeterministyczne; IMAP: `parseMessage` bierze `Message#getReceivedDate()` z fallbackiem `Instant.now()`. `social_message.sent_at` NOT NULL; `SocialMessageServiceImpl`: `incoming.sentAt()` lub `now()` | potrzebna kolumna czasowa w PK i w unikalnościach (D4); `email_message` wymaga nowej `message_at NOT NULL` |
| U7 | Encje JPA i zdarzenia są oparte o samo `message_id` | kod: `EmailMessage`/`SocialMessage` `@Id message_id`, `em.merge/find`; `EmailContactCreator` 3× `findById(messageId)`; `EmailEvent` (RabbitMQ) niesie `messageId`, bez daty wiadomości; 20 plików main odwołuje się do `EmailMessage`/`email_message` (11 w `domain/email`), 14 do `SocialMessage`/`social_message` (8 w `domain/social`); PR #44 (WhatsApp) dodał zapis do `social_message` | potrzebny `@IdClass` + natywny INSERT (wzorzec BE-117: `ContactEvent`/`ContactEventId`), `messageAt` w zdarzeniach; tickety social zaczynają od ponownego przeczytania kodu |
| U8 | RLS wiadomości jest niepełny, a **cały RLS nigdy nie działał pod rolą bez BYPASSRLS** | live: `pol_email_message_select`/`pol_social_message_select` = tylko `FOR SELECT`, bez FORCE; SELECT-only także `audit_log`, `ivr_tree`, `queue`, `app_user`; `customer`/`campaign` mają INSERT+UPDATE bez DELETE, `contact` tylko INSERT (bez UPDATE i DELETE); `ccapp` = superuser+BYPASSRLS (demo), `app_user` = `NOLOGIN` (V012). Brak polityki = deny → pod rolą ograniczoną INSERT/DELETE byłyby odrzucane | łańcuch purge nigdy nie był sprawdzony pod taką rolą; RLS to defense-in-depth, główną ochroną jest `assertSameTenant` |
| U9 | Tabele z `tenant_id` bez RLS | live: `campaign_contact`, `campaign_contact_archive` (PII: phone, imię, nazwisko, e-mail), `contacts_dw`, `email_routing_rule`, `email_template`, `gdpr_processing_register`, `ivr_audio`, `plugin_version`, `refresh_token` (37 wierszy z `tenant_id` NULL = SUPER_ADMIN) | D7; nie każda jest tenantowa — najpierw klasyfikacja (DB-071) |
| U10 | Martwe harmonogramy | kod: 0 wywołań `archive_completed_campaign_contacts()`, `RefreshTokenRepository#deleteExpiredAndRevoked`, `drop_old_*`/`rotate_*`; live: brak `pg_cron` (rozszerzenia: pg_trgm, pgcrypto, plpgsql, uuid-ossp); 30/30 kampanii COMPLETED/STOPPED > 30 dni (37 wierszy `campaign_contact`), `campaign_contact_archive` = 0; `refresh_token` 1333 wiersze, 1331 wygasłych, 1298 unieważnionych; `scheduled_job` (11 wpisów) to dokumentacja — `cleanup_expired_refresh_tokens`, `refresh_materialized_views` mają `last_run_at` NULL; `PartitionReclaimJob.TABLE_CATEGORIES` = 4 tabele `contact*`, `PartitionMaintenanceJob.PARTITIONED_TABLES` = 6; najstarsza partycja `audit_log` = 2026_03 (`ARCHITECTURE.md:848` obiecuje 2 lata przez pg_cron) | `audit_log`/`plugin_invocation_log` rosną bez końca; PII w `campaign_contact` i `refresh_token` bez wykonawcy |
| U11 | Włączenie archiwizacji zmienia to, co widzi użytkownik | kod: czytelnicy `campaign_contact` (`CampaignContactRepository`, `CampaignContactHistoryController`, `DialerController`, statystyki po `(campaign_id, status)`) nie czytają archiwum; 0 czytelników `campaign_contact_archive` poza SQL/retencją | po archiwizacji kontakty zakończonych kampanii > 30 dni znikają z UI/statystyk (D8) |
| U12 | `campaign_contact_archive`: obecny stan wystarcza | pomiar agenta (scratch, 1,2 mln wierszy): purge per tenant ~250 ms w obu wariantach; globalny „ogon": DELETE 0,42 s + VACUUM vs DROP 28 ms; PK musi zawierać `archived_at`; `ON CONFLICT (record_id, campaign_id)` w funkcji archiwizującej przestaje działać. `purge_campaign_contact_archive` (V091) = pojedynczy DELETE | Could, bramkowane (warunek wejścia w DB-069: 50–100 mln wierszy lub problem z WAL/lagiem); zbatchować purge (DB-056/BE-121) |
| U13 | `campaign_contact` = dekoracyjne LIST(campaign_id) | live: jedyna partycja `campaign_contact_default`; kod: 0 × `PARTITION OF` (tylko komentarz w V009); `06-database.md:282` i `ARCHITECTURE.md:503` twierdzą, że aplikacja tworzy partycje dynamicznie; pomiar: `CREATE … PARTITION OF` przy istniejącym DEFAULT bierze ACCESS EXCLUSIVE; alternatywa HASH(campaign_id) zgodna z PK i `UNIQUE(campaign_id, phone)` | DB-070 (decyzja) + korekta dokumentacji (DB-077) |
| U14 | Duplikaty/nieużywane indeksy | live: `idx_callback_ready` = `idx_scheduled_callback_due`; `idx_agent_group_member_lookup` = `idx_campaign_agent_member_lookup` (dodatkowo `idx_agent_group_member_agent` to prefiks covering-indeksu, `idx_agent_group_member_group` prefiks PK — do oceny); archiwum: `idx_cca_campaign`, `idx_cca_archived_at` bez czytelników (Java, `pg_proc`; `idx_scan` = 0 na pustej tabeli nic nie dowodzi); widoki `mv_agent_daily_stats`/`mv_campaign_stats`: 0 referencji w kodzie, 0 skanów | DB-057, DB-058 |
| U15 | Wolumeny są bardzo małe, a PRD nie podaje liczb | live: `email_message` 55 wierszy, 216 kB, średni wiersz 1049 B (max 14,5 kB); `social_message` 0 wierszy; NFR-S03: 50 tenantów × 100 agentów, nic o kontaktach/dzień | próg partycjonowania to szacunek (D2) |

## 3. Decyzje otwarte (właściciel produktu nie odpowiedział)

Każda: opcje → **ZAŁOŻENIE DO POTWIERDZENIA** (domyślne, na nim działają tickety) → wpływ alternatywy.

**D1 — semantyka retencji treści wiadomości.** Opcje: (A) DELETE wierszy i załączników S3 razem z purge kontaktu, w istniejącej kategorii
`CONTACT_INTERACTIONS`; (B) anonimizacja (zostają metadane); (C) osobna kategoria `MESSAGE_CONTENT`.
**ZAŁOŻENIE: A** (bez zmian CHECK/enuma/UI). Wpływ: (B) — BE-125/126/127 zamiast DELETE robią UPDATE PII, tabele rosną bez końca, więc
sens Poziomu 2 (DB-065/067) znika; (C) — wchodzą warunkowe DB-063, BE-130, FE-111 (CHECK w `tenant_retention_policy` i
`tenant_retention_pending_summary`, seed dla istniejących tenantów, enum Java, UI, 4× i18n), a BE-126/127/128 wołane są pod nową kategorią.

**D2 — wolumen i próg partycjonowania `email_message`.** Brak liczb w PRD. Opcje progu: niski (≈ 2 GB / 500 tys. wierszy), średni, wysoki.
**ZAŁOŻENIE: wchodzimy w konwersję, gdy spełnione jest którekolwiek z:** G1 `pg_total_relation_size('email_message')` ≥ 10 GB (tabela + TOAST +
indeksy); G2 ≥ 2 mln wierszy; G3 prognoza z tempa ostatnich 3 miesięcy przekracza G1 lub G2 w ≤ 12 miesięcy; G4 objaw operacyjny — p95 batcha
DELETE w purge > 5 s albo udział martwych krotek > 20 % po VACUUM przez > 7 dni. Konwersja to L (klucz złożony, dedup, ~20 plików), więc
potrzebuje 12 miesięcy zapasu. Próg jest **szacunkiem** (jak próg 50–100 mln dla archiwum), kalibrowanym przez DB-066 na danych środowiska
docelowego. Wpływ: próg niższy → konwersja wcześniej (większy koszt, mniejszy zysk); wyższy → konwersja na produkcyjnej tabeli pod presją
(backfill + blokady). Bez decyzji DB-067/BE-134/BE-135 pozostają w stanie „czeka na bramkę".

**D3 — zakres RODO Art. 17/15.** Opcje: (A) rozszerzyć funkcje SQL i podłączyć je do `GdprServiceImpl`; (B) rozszerzyć tylko Javę;
(C) zostawić stan obecny. **ZAŁOŻENIE: A** — jedna transakcyjna implementacja DB (`anonymize_customer`, `export_customer_data`) obejmująca
wiadomości, `scheduled_callback`, `campaign_contact`, `campaign_contact_archive`, `contact.notes` oraz — po ocenie w DB-060 — transkrypcje i
`contact_ai_summary` (uznane za PII: treść rozmowy), a `GdprServiceImpl` sprząta S3 (nagrania, EML, załączniki) i woła funkcje.
Wpływ: (B) — DB-061/062 sprowadzają się do audytu i naprawy STABLE, logika trafia do BE-129 (wiele repozytoriów, brak jednej transakcji);
(C) — U2/U4 pozostają otwartą luką prawną, EPIC-30 nie domyka NFR-RODO01/02.

**D4 — deduplikacja e-mail po partycjonowaniu.** Opcje: (A) `message_at TIMESTAMPTZ NOT NULL` + unikalność `(tenant_id, message_id_header,
message_at)`; (B) osobna niepartycjonowana tabela `email_message_dedup`. **ZAŁOŻENIE: A**, z `message_at` deterministycznym: INBOUND =
`Message#getSentDate()` (nagłówek Date) → `getReceivedDate()` → dopiero na końcu `now()`; OUTBOUND = `sentAt` ustawiane raz, nigdy nie zmieniane.
Problem: fallback bez Date jest niedeterministyczny — ta sama wiadomość pobrana ponownie (awaria przed flagą SEEN) dostanie inne `message_at`
(a na granicy miesiąca inną partycję), więc unikalność złożona jej nie wykryje; jedyną obroną zostaje `findByMessageIdHeader` bez daty, który nie
jest wspierany globalnym constraintem (wyścig dwóch instancji pollujących = możliwy duplikat). Wpływ (B): warunkowe DB-068/BE-136 — globalna
unikalność w jednej transakcji z INSERT (`ON CONFLICT DO NOTHING`), ale tabela dedup **też potrzebuje retencji** (własny purge).

**D5 — horyzont platformowy `audit_log`/`plugin_invocation_log`.** **ZAŁOŻENIE: 24 mies., konfigurowalny** (`retention.platform.audit-log-months`,
`retention.platform.plugin-invocation-log-months`; zgodnie z `ARCHITECTURE.md:848` i DESIGN EPIC-29 §12.1: log platformowy, nie per-tenant).
Wpływ: inna wartość = zmiana konfiguracji, bez zmiany kodu; wymaganie per-tenant retencji audytu = osobny epik (kolumna `tenant_id` bywa NULL).
Wymaga potwierdzenia prawnego (BE-123).

**D6 — baza czasowa `CAMPAIGN_DATA`.** Opcje: `archived_at` vs koniec kampanii. **ZAŁOŻENIE: `archived_at`** (jak dziś). Skutek uboczny: gdy
BE-120 zarchiwizuje zaległość (30 kampanii), jej zegar retencji startuje od dnia pierwszego uruchomienia — efektywna retencja PII wydłuża się
o wiek kampanii. Wpływ alternatywy: warunkowe DB-075 (kolumna `campaign_ended_at` w archiwum, backfill z `campaign.updated_at`, zmiana
`purge_campaign_contact_archive` i indeksu) + BE-140 (`countEligible`/`purgeEligible`).

**D7 — RLS dla `campaign_contact*` (PII).** **ZAŁOŻENIE: TAK** (DB-072, DB-073). Wpływ NIE: DB-072/073 znikają, izolację utrzymuje wyłącznie
filtr `tenant_id` w kodzie/funkcjach (jak dziś, patrz V091) — rośnie znaczenie testów izolacji (WP-1). Uwaga: `campaign_contact` jest na ścieżce
gorącej dialera (scheduler bez tenanta) — DB-073 wymaga przeglądu wszystkich ścieżek `@Scheduled`/`@Async`/RabbitMQ.

**D8 — archiwizacja kampanii zmienia widoczność (nowa, wynikła z weryfikacji U11).** **ZAŁOŻENIE: BE-120 dostarcza job za flagą
`retention.campaign-archive.enabled` (domyślnie `false`)**; włączenie po potwierdzeniu, że UI/raporty nie potrzebują kontaktów kampanii > 30
dni, albo po dodaniu czytelników archiwum (poza zakresem EPIC-30). Wpływ: domyślnie `true` = czyści `campaign_contact` od razu (zgodnie z zamiarem
V015), ale kontakty zakończonych kampanii znikają z UI i statystyk.

## 4. Fazy i fale

Graf (A → B = kolejność wykonania, B zależy od A; ‖ = równolegle):

```
Fala 0  BE-120, BE-122, BE-123, DB-057, DB-058 (niezależne)      DB-056 → BE-121
Fala 1  BE-124 (ADR D1) → BE-125 → BE-126 → BE-127 → BE-128 → FE-110 (też BE-126 → FE-110)      BE-124 → DB-059 → BE-127
        DB-060 (audyt PII) → DB-061 ‖ DB-062 → BE-129 → FE-112      [D1=C: BE-124 → DB-063 → BE-130 → FE-111]
Fala 2  DB-064 (RLS wiadomości) → DB-065 (social) → BE-132 → BE-133      BE-126 → DB-065      DB-071 → DB-072 ‖ DB-073 ‖ DB-074, BE-138, BE-139
Fala 3  DB-066 (BRAMKA D2/D4) → [go] DB-067 (email) → BE-134 → BE-135      [D4=B: DB-068 → BE-136]
Fala 4  DB-069 → BE-137 (bramkowane)   DB-070   [D6≠archived_at: DB-075 → BE-140]   DB-076   DB-077 (dokumentacja, po falach 0–1)
```

| Fala | Cel | Priorytet | Złożoność (vs ocena zlecenia) |
|---|---|---|---|
| 0 | Martwe harmonogramy i porządki (bez decyzji PO, z wyjątkiem D5 i D8) | Should (DB-058 Could) | S; **BE-123 = M** (osobna ścieżka horyzontu, rozszerzenie `PartitionScanner`, wiersze z `tenant_id` NULL) |
| 1 | Retencja treści wiadomości + RODO | Must | S–M; **BE-129 = M** (przepływ Java + S3), DB-061/062 = M |
| 2 | Hardening RLS + partycjonowanie `social_message` | Should | social M (0 wierszy = najtańsze okno); **DB-073 = M** (ścieżka gorąca dialera) |
| 3 | Partycjonowanie `email_message` | Should, **bramkowane D2** | **L** (DB-067, BE-134) |
| 4 | Archiwum, `campaign_contact`, `scheduled_job`, dokumentacja | Could (DB-077 Should) | S–L |

Przełączniki: DB-065 startuje po BE-126 (Poziom 1 usuwa wiadomości, więc konwersja nie miesza się z semantyką), DB-067 po „go" z DB-066.

## 5. Wymagania przekrojowe (lekcje EPIC-29, zapłacone realnymi błędami)

Tickety powtarzają je jako konkretne kryteria akceptacji tam, gdzie mają zastosowanie (oznaczenie WP-n).

- **WP-1 Testy na prawdziwej bazie.** Każda zmiana natywnego SQL/JPA/funkcji SQL ma test Testcontainers na pełnym łańcuchu Flyway
  (precedens: `CampaignContactArchivePurgeTenantIsolationTest`, `postgres:16-alpine`). Mocki `EntityManager`/repozytoriów nie złapały:
  błędu mapowania `resultClass`+enum, braku `TenantContext` w wątku schedulera, `Map.of().get(null)`.
- **WP-2 Joby `@Scheduled` z pętlą per tenant.** Jawne `TenantContext.setTenantId` na początku iteracji i `clear()` w `finally`; **nigdy**
  `clear()` na ścieżce z żądania HTTP (scheduler i REST = wspólny rdzeń bez zarządzania kontekstem + jawny prekontrakt w javadoc); test z
  PRAWDZIWYM repozytorium i pustym `TenantContext`. Wzorce: `SocialIntegrationServiceImpl#refreshToken`, `RetentionEvaluationServiceImpl`.
- **WP-3 Migracje.** Numer nadawany przy implementacji: „następna wolna wersja — sprawdź `develop`, otwarte gałęzie (`git ls-tree`) ORAZ
  `flyway_schema_history` żywej bazy" (precedens: V092 zajęte przez `feature-socialmedia`, DB-055). Jedna migracja na jedną zmianę; nigdy edycja
  zastosowanej; idempotentna (`IF EXISTS`), guard chroniący jedyny indeks, `SET LOCAL lock_timeout` przy DDL z ACCESS EXCLUSIVE na tabeli
  partycjonowanej; weryfikacja na bazie scratch (`pg_dump -s`, `SET max_parallel_maintenance_workers = 0` przed VACUUM/CREATE INDEX) i w pełnym łańcuchu Flyway.
- **WP-4 Weryfikacja na żywo** na stosie local-demo po przebudowie obrazów. Joby destrukcyjne (purge/DELETE/DROP/archiwizacja): przed uruchomieniem
  policz kwalifikujące się wiersze i uzyskaj zgodę właściciela (precedens: 74 nagrania S3). Test RLS wyłącznie pod `SET ROLE app_user`.
- **WP-5 Poziom 1 przed Poziomem 2.** Wiersze i obiekty S3 sprzątane przez purge per tenant; `DROP` partycji dotyczy tylko partycji pustych
  (niepusta → WARN i pominięcie) dla tabel z obiektami zewnętrznymi; partycja `_default` nigdy nie kandyduje, a niepusta `_default` = WARN (sygnał
  awarii rotacji). Wyjątek: horyzont platformowy (`audit_log`, `plugin_invocation_log`) — brak Poziomu 1, niepusta partycja po horyzoncie jest oczekiwana (INFO).
- **WP-6 Maszyneria partycji.** Nowa tabela partycjonowana wymaga: `create_<t>_partition(y,m)` (+ `drop_old_…`/rotacja tylko jeśli bezpieczne), wpisu w
  `create_next_month_partitions()`, `PartitionMaintenanceJob.PARTITIONED_TABLES`, mapowania w `PartitionReclaimJob`/`RetentionEvaluationServiceImpl`/
  `RetentionPurgeServiceImpl`; nazwy `<tabela>_YYYY_MM` (`PartitionScanner`). Dla wiadomości **nie** tworzymy wykonywalnego `drop_old_*` (SQL nie zna S3).
- **WP-7 DoD.** Status i notatki w pliku zadań w stylu EPIC-29; pamięć agentów (`.claude/agent-memory/`) commitowana razem ze zmianą (jest śledzona);
  FE: `npm run lint`, `npm run build`, komplet kluczy i18n w 4 językach (`frontend/public/i18n/{pl,en,de,uk}.json`).
- **WP-8 Struktura ticketu.** Kontekst (odwołanie do §2/§3), zakres z zweryfikowanymi nazwami, mierzalne AC, ryzyka, wykonawca wg routingu z
  `CLAUDE.md`, oraz „zakłada Dn = …; przy alternatywie zmienia się …".

## 6. Poza zakresem i antykandydaci

`agent_break` (mutowalny klucz `start_time`, UPDATE po samym id), `scheduled_callback` (`em.find` po id, mutowalne `scheduled_at`, 12 indeksów —
tylko PII w D3 i dedup indeksu), `refresh_token` (UNIQUE `token_hash` — tylko czyszczenie), logi (`retention_purge_log`, `cron_log`) i cała
konfiguracja/referencja (wg analizy: 33 z 49 tabel ≤ 30 wierszy). Retencja per tenant dla `audit_log`, legal hold, czytelnicy archiwum kampanii i retencja
`scheduled_callback` — osobne epiki. Obserwacja poza zakresem: brak walidacji `RECORDINGS ≤ CONTACT_INTERACTIONS` może osierocać obiekty S3
(kontakt usunięty, plik zostaje) — odnotowana w BE-124.

## 7. Ryzyka

- **R1 RLS pod rolą ograniczoną** (U8): łańcuch purge, GDPR i joby nigdy nie były uruchamiane pod rolą bez BYPASSRLS; DELETE bez polityki = 0 wierszy
  po cichu. Mitygacja: DB-064, BE-138, BE-139, testy `SET ROLE app_user` w każdym tickecie DDL/DML.
- **R2 Destrukcyjne joby na danych demo** (archiwizacja 37 wierszy, purge 18–23 wiadomości, S3): WP-4, zgoda właściciela, dry-run liczący kandydatów.
- **R3 Kolejność Poziom 1/Poziom 2** dla S3: awaria między usunięciem wiersza a obiektu osierocia plik; przyjęta kolejność „dzieci przed rodzicem,
  S3 przed wierszem, brak postępu = koniec pętli" (BE-125/126).
- **R4 Numeracja migracji** (kolizje z gałęziami feature) — WP-3; wdrożenie z brakującym plikiem V092 nie przechodzi walidacji Flyway (DB-055).
- **R5 Ścieżki `@Scheduled` z RLS**: `archive_completed_campaign_contacts()` iteruje po `campaign` (FORCE RLS); pod rolą bez BYPASSRLS i bez GUC zwróci
  zero kampanii — BE-120 musi to przetestować i wybrać rozwiązanie.
