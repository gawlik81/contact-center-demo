# Projekt: Retencja treści wiadomości, domknięcie martwych harmonogramów i partycjonowanie tabel wiadomości (EPIC-30)

Status: **projekt do akceptacji** (nie wdrożone). Decyzje D1–D10 (§3) czekają na właściciela produktu — tickety mają działać przy
**założeniach domyślnych** oznaczonych „ZAŁOŻENIE DO POTWIERDZENIA"; przy alternatywie zmienia się wskazany zakres, nie kolejność prac.
**D1: FORMALNIE POTWIERDZONE przez właściciela produktu 2026-09-30 (opcja A)** — wcześniej, 2026-09-20, przyjęte do realizacji jedynie jako
założenie robocze bez wyraźnego potwierdzenia, na którym działał już zamknięty łańcuch ticketów BE-124→BE-125→BE-126→BE-127→BE-128; stan i
ścieżka zmiany w §3, ADR w `TASKS-BACKEND.md` BE-124.
**D9: FORMALNIE POTWIERDZONE przez właściciela produktu 2026-09-30 (opcja A, z podglądem)** — kod realizujący wariant A jest już zmergowany do
`develop` (DB-061 ✅, DB-062 ✅, BE-129 ✅); czysto formalne domknięcie already-shipped behavior. Tryb podglądu w UI administratora (FE-112)
pozostaje ⬜ Nie rozpoczęte; stan i ścieżka zmiany w §3.
**D10: FORMALNIE POTWIERDZONE (prawnie) przez właściciela produktu 2026-09-30 (maskowanie PII w `audit_log`, wiersze zostają)** — w odróżnieniu
od D1/D9 implementacja (`TASKS-BACKEND.md` BE-142) **jeszcze NIE ISTNIEJE**; to potwierdzenie decyzji/kierunku, nie stanu wdrożenia; stan i
ścieżka zmiany w §3.
Analiza: 2026-09-20 (PostgreSQL 16.13, schemat po V093, baza demo 28 MB; tylko odczyt, bez zmian w bazie i repo).
Powiązane: `DESIGN-data-retention-partitioning.md` (EPIC-29 — silnik retencji), `PRD.md` §6.5 (NFR-RODO01/02/03), `ARCHITECTURE.md` §4/§6.6,
`documentation/tech/06-database.md`. Tickety: `TASKS-DATABASE.md` DB-056…079, `TASKS-BACKEND.md` BE-120…143 (+ BE-144 poza epikiem: porządki infrastruktury MinIO), `TASKS-FRONTEND.md` FE-110…112.

## 1. Cel

EPIC-29 dał silnik retencji dla `contact*`, ale: (a) treść wiadomości e-mail/social **nigdy** nie jest usuwana (luka RODO), (b) kilka
harmonogramów istnieje wyłącznie jako funkcje SQL/metody bez wykonawcy, (c) jedyni realni kandydaci do partycjonowania czasowego
(`email_message`, `social_message`) mają klucze uniemożliwiające partycjonowanie, (d) RLS/rola połączenia nigdy nie były sprawdzone
pod rolą bez BYPASSRLS. Kolejność: **Poziom 1 (usuwanie wierszy i obiektów S3 per tenant) przed Poziomem 2 (DROP partycji)**.

## 2. Ustalenia z analizy (z dowodami)

Oznaczenia: „live" = zapytanie tylko-do-odczytu na `cc-postgres` (2026-09-20); „kod" = grep/odczyt repo na gałęzi `partycjonowanie-2`.

| # | Ustalenie | Dowód | Skutek |
|---|---|---|---|
| U1 | Purge kontaktu **odcina**, a nie usuwa wiadomości | kod: `RetentionPurgeServiceImpl#purgeContactInteractions` → `EmailMessageService/SocialMessageService#detachContactReferences` = `UPDATE … SET contact_id = NULL`; live: 23 z 55 maili ma `contact_id IS NULL` (analiza: 18/18 sprzed cutoffu 2026-05-13, 10 z `body_html`) | PII (`subject`, `body_*`, adresy, `content`) zostaje na stałe; wiadomości osierocone nie mają żadnej ścieżki usunięcia; 14 z 23 osieroconych e-maili ma adres zgodny z e-mailem klienta (DB-060 F2), więc są przypisywalne po identyfikatorze, choć nie po `contact_id` (D9) |
| U2 | Przepływ RODO w aplikacji jest w **Javie** i nie obejmuje wiadomości | kod: `GdprController` → `GdprServiceImpl`; `anonymizeCustomer` = S3 nagrania + `CustomerRepository#anonymize` (UPDATE wyłącznie `customer`); eksport = `customer.json` + `contacts.json` (≤ 1000). Funkcje SQL `anonymize_customer`/`export_customer_data` (V013/V017): **0 wywołań** w `backend/`, `frontend/`, `voicebot/`. **Druga ścieżka anonimizacji (DB-060 F8):** `DELETE /api/customers/{id}` (`CustomerController:255` → `CustomerServiceImpl#anonymizeCustomer` :301 z `@Audited(action = "CUSTOMER_ANONYMIZED")` → `CustomerRepository#anonymize`; UI listy klientów: `customer-list.component.ts:134` → `customer.service.ts:84 deleteCustomer`, modal `customer-delete-modal`) — aktualizuje tylko `customer` i nie kasuje nawet nagrań S3. **Trzy źródła wpisu audytu:** Java `GDPR_ANONYMIZE` (`GdprServiceImpl:112`, fire-and-forget), `@Audited` `CUSTOMER_ANONYMIZED` i funkcja SQL `CUSTOMER_ANONYMIZED`; eksport: Java `GDPR_EXPORT` vs SQL `CUSTOMER_DATA_EXPORTED` | D3 dotyczy przepływu Java, nie tylko funkcji SQL; Art. 17/15 nie dotyka wiadomości, `scheduled_callback`, `campaign_contact*`, `contact.notes`, transkrypcji; **BE-129 obejmuje obie ścieżki REST i wybiera jedno źródło wpisu audytu** (inaczej po podłączeniu funkcji: podwójne wpisy) |
| U3 | `export_customer_data` jest `STABLE` i robi `INSERT INTO audit_log` | live: `pg_proc.provolatile='s'`; kod V017; zachowanie PG „INSERT is not allowed in a non-volatile function" zreprodukowane na funkcji sondującej w `pg_temp` (właściwa funkcja **nie** została wywołana) | eksport SQL prawie na pewno nie działa; do potwierdzenia w DB-061 |
| U4 | `anonymize_customer` szuka wiadomości wyłącznie przez `contact_id IN (SELECT contact_id FROM contact WHERE customer_id = …)` | kod V013; **funkcja nie działa dla klienta z kontaktami:** V013 najpierw ustawia `customer.is_deleted = TRUE` (l. 48–58), potem robi `UPDATE contact` (l. 80), a `trg_contact_ref_integrity` (V016 l. 36–107: BEFORE INSERT OR UPDATE bez listy kolumn, 12 kopii — rodzic + 11 partycji, live) wymaga klienta z `is_deleted = FALSE` → wyjątek, a `EXCEPTION WHEN OTHERS` (l. 121) cofa całość; dowód statyczny (DB-060 F1) — funkcji nie uruchamiano | po U1 wiadomości są nieosiągalne; brak: `scheduled_callback` (phone, first_name, last_name, notes), `campaign_contact`/`_archive` (phone, first_name, last_name, email, custom_fields), `contact.notes`, `contact_transcription.content`, `contact_ai_summary`, `cc_address`/`bcc_address`, `customer.external_id`; klucze S3 przepadają przy zerowaniu `attachments`. **Skutek uboczny (do potwierdzenia testem w DB-079):** każdy `UPDATE contact` klienta zanonimizowanego przez Javę (`is_deleted = TRUE`), np. `RecordingRetentionJob` → `ContactRepository#clearRecordingUrl`, też rzuca — DB-062 (kolejność instrukcji), DB-079 (zawężenie triggera) |
| U5 | Załączniki e-mail leżą w S3, a **nic ich nie usuwa** | kod: `EmailAttachmentStorageService` (klucze `email-attachments/{tenantId}/{messageId}/…`, pending: `…/{tenantId}/pending/{uuid}/…`) — brak metody delete; klucz w JSONB `attachments` to `s3_key` (komentarz V010 i javadoc `EmailMessage` mówią `s3_url` — nieaktualne); EML wiadomości = `contact.recording_url` (kategoria RECORDINGS); social: URL-e platform, bez S3 | `DROP` partycji nie usuwa S3 → Poziom 1 musi sprzątać S3 przed dropem; pending-uploady porzucone przez agenta nie mają TTL. **Sprostowanie (BE-124, 2026-09-20):** `pending/` NIE jest tymczasowe — po wysłaniu ten sam klucz jest zapisany w `attachments` wiadomości OUTBOUND (live: 8 z 9 obiektów `pending/`), więc TTL/lifecycle na prefiksie skasowałby załączniki wysłanych wiadomości (BE-131 skorygowany, R7) |
| U6 | Partycjonowanie czasowe blokują klucze | live: `pk_email_message (message_id)`, `uq_email_message_id_header (tenant_id, message_id_header)` (DEFERRABLE), `pk_social_message`, `uq_social_message_external_id (tenant_id, external_message_id)`; scratch: „unique constraint on partitioned table must include all partitioning columns". `email_message`: `received_at` NULL dla OUTBOUND (live: 25 OUT z `sent_at`, 30 IN z `received_at`), `created_at` niedeterministyczne; IMAP: `parseMessage` bierze `Message#getReceivedDate()` z fallbackiem `Instant.now()`. `social_message.sent_at` NOT NULL; `SocialMessageServiceImpl`: `incoming.sentAt()` lub `now()` | potrzebna kolumna czasowa w PK i w unikalnościach (D4); `email_message` wymaga nowej `message_at NOT NULL` |
| U7 | Encje JPA i zdarzenia są oparte o samo `message_id` | kod: `EmailMessage`/`SocialMessage` `@Id message_id`, `em.merge/find`; `EmailContactCreator` 3× `findById(messageId)`; `EmailEvent` (RabbitMQ) niesie `messageId`, bez daty wiadomości; 20 plików main odwołuje się do `EmailMessage`/`email_message` (11 w `domain/email`), 14 do `SocialMessage`/`social_message` (8 w `domain/social`); PR #44 (WhatsApp) dodał zapis do `social_message` | potrzebny `@IdClass` + natywny INSERT (wzorzec BE-117: `ContactEvent`/`ContactEventId`), `messageAt` w zdarzeniach; tickety social zaczynają od ponownego przeczytania kodu |
| U8 | RLS wiadomości jest niepełny, a **cały RLS nigdy nie działał pod rolą bez BYPASSRLS** | live: `pol_email_message_select`/`pol_social_message_select` = tylko `FOR SELECT`, bez FORCE; SELECT-only także `audit_log`, `ivr_tree`, `queue`, `app_user`; `customer`/`campaign` mają INSERT+UPDATE bez DELETE, `contact` tylko INSERT (bez UPDATE i DELETE); `ccapp` = superuser+BYPASSRLS (demo), `app_user` = `NOLOGIN` (V012). Brak polityki = deny → pod rolą ograniczoną INSERT/DELETE byłyby odrzucane | łańcuch purge nigdy nie był sprawdzony pod taką rolą; RLS to defense-in-depth, główną ochroną jest `assertSameTenant` |
| U9 | Tabele z `tenant_id` bez RLS | live: `campaign_contact`, `campaign_contact_archive` (PII: phone, imię, nazwisko, e-mail), `contacts_dw`, `email_routing_rule`, `email_template`, `gdpr_processing_register`, `ivr_audio`, `plugin_version`, `refresh_token` (37 wierszy z `tenant_id` NULL = SUPER_ADMIN). **PG `contacts_dw` niesie PII:** 130/130 wierszy z `remote_address` (numer CLI/e-mail klienta), wszystkie bez kontaktu źródłowego (przeżyły retencję EPIC-29; tabela nie ma retencji); `PostgresDwWriter` zapisuje `remote_address`, `ClickHouseDwWriter` nie, ClickHouse jest czysty z PII (DB-060 F6); `etl.dw.type`: dev `postgres`, prod i local-demo `clickhouse` | D7; nie każda jest tenantowa — najpierw klasyfikacja (DB-071); PG-DW: DB-078 + BE-141 |
| U10 | Martwe harmonogramy | kod: 0 wywołań `archive_completed_campaign_contacts()`, `RefreshTokenRepository#deleteExpiredAndRevoked`, `drop_old_*`/`rotate_*`; live: brak `pg_cron` (rozszerzenia: pg_trgm, pgcrypto, plpgsql, uuid-ossp); 30/30 kampanii COMPLETED/STOPPED > 30 dni (37 wierszy `campaign_contact`), `campaign_contact_archive` = 0; `refresh_token` 1333 wiersze, 1331 wygasłych, 1298 unieważnionych; `scheduled_job` (11 wpisów) to dokumentacja — `cleanup_expired_refresh_tokens`, `refresh_materialized_views` mają `last_run_at` NULL; `PartitionReclaimJob.TABLE_CATEGORIES` = 4 tabele `contact*`, `PartitionMaintenanceJob.PARTITIONED_TABLES` = 6; najstarsza partycja `audit_log` = 2026_03 (`ARCHITECTURE.md:848` obiecuje 2 lata przez pg_cron) | `audit_log`/`plugin_invocation_log` rosną bez końca; PII w `campaign_contact` i `refresh_token` bez wykonawcy |
| U11 | Włączenie archiwizacji zmienia to, co widzi użytkownik | kod: czytelnicy `campaign_contact` (`CampaignContactRepository`, `CampaignContactHistoryController`, `DialerController`, statystyki po `(campaign_id, status)`) nie czytają archiwum; 0 czytelników `campaign_contact_archive` poza SQL/retencją | po archiwizacji kontakty zakończonych kampanii > 30 dni znikają z UI/statystyk (D8) |
| U12 | `campaign_contact_archive`: obecny stan wystarcza | pomiar agenta (scratch, 1,2 mln wierszy): purge per tenant ~250 ms w obu wariantach; globalny „ogon": DELETE 0,42 s + VACUUM vs DROP 28 ms; PK musi zawierać `archived_at`; `ON CONFLICT (record_id, campaign_id)` w funkcji archiwizującej przestaje działać. `purge_campaign_contact_archive` (V091) = pojedynczy DELETE | Could, bramkowane (warunek wejścia w DB-069: 50–100 mln wierszy lub problem z WAL/lagiem); zbatchować purge (DB-056/BE-121) |
| U13 | `campaign_contact` = dekoracyjne LIST(campaign_id) | live: jedyna partycja `campaign_contact_default`; kod: 0 × `PARTITION OF` (tylko komentarz w V009); `06-database.md:282` i `ARCHITECTURE.md:503` twierdzą, że aplikacja tworzy partycje dynamicznie; pomiar: `CREATE … PARTITION OF` przy istniejącym DEFAULT bierze ACCESS EXCLUSIVE; alternatywa HASH(campaign_id) zgodna z PK i `UNIQUE(campaign_id, phone)` | DB-070 (decyzja) + korekta dokumentacji (DB-077) |
| U14 | Duplikaty/nieużywane indeksy | live: `idx_callback_ready` = `idx_scheduled_callback_due`; `idx_agent_group_member_lookup` = `idx_campaign_agent_member_lookup` (dodatkowo `idx_agent_group_member_agent` to prefiks covering-indeksu, `idx_agent_group_member_group` prefiks PK — do oceny); archiwum: `idx_cca_campaign`, `idx_cca_archived_at` bez czytelników (Java, `pg_proc`; `idx_scan` = 0 na pustej tabeli nic nie dowodzi); widoki `mv_agent_daily_stats`/`mv_campaign_stats`: 0 referencji w kodzie, 0 skanów | DB-057, DB-058 |
| U15 | Wolumeny są bardzo małe, a PRD nie podaje liczb | live: `email_message` 55 wierszy, 216 kB, średni wiersz 1049 B (max 14,5 kB); `social_message` 0 wierszy; NFR-S03: 50 tenantów × 100 agentów, nic o kontaktach/dzień | próg partycjonowania to szacunek (D2) |
| U16 | Pełna kopia treści e-mail leży w S3 poza tabelą wiadomości: EML pod `contact.recording_url` | kod: `EmailContactCreator#generateAndStoreEml`, `EmailEmlService#buildEmlS3Key` (`{tenantId}/{yyyy}/{MM}/{contactId}.eml`, treść + załączniki base64); live: 14 EML, 61 mp3, 75 wskaźników = 75 obiektów, 0 osieroconych (BE-124) | kategoria RECORDINGS, nie CONTACT_INTERACTIONS: purge kontaktu bez sprzątania `recording_url` osierocia obiekt z pełną treścią (R6) |
| U17 | `RecordingService#deleteFromS3` połyka `S3Exception` | kod: `RecordingServiceImpl:307–322`; skutki: `RecordingRetentionJob#deleteRecording` czyści `recording_url` mimo błędu S3, `GdprServiceImpl#deleteCustomerRecordingsFromS3` liczy `failed`, który nie rośnie | Poziom 1 („S3 przed wierszem") potrzebuje metody usuwania zwracającej wynik (BE-125: `EmailAttachmentStorageService#delete`); BE-129 nie może polegać na `deleteFromS3` |
| U18 | Klucze S3 w `attachments` wiadomości OUTBOUND pochodzą od klienta | kod: `EmailReplyRequest.PendingAttachment#s3Key` → `EmailSendServiceImpl#buildAttachmentsJson` bez walidacji prefiksu (kontrola IDOR jest tylko w `EmailAttachmentController#downloadAttachment`) | purge musi kasować wyłącznie klucze z prefiksem `email-attachments/{tenantId}/` (BE-125); możliwość dołączenia obiektu spoza tenanta do wysyłanego maila — luka istniejąca przed EPIC-30, potwierdzona w code review BE-125 (BE125-01: `EmailSendServiceImpl#buildAttachmentPart` pobiera obiekt po `s3Key` z żądania bez walidacji prefiksu, `EmailAttachmentController#downloadAttachment` ma `startsWith` bez odrzutu `..`) — ticket **BE-143** (Must, S; niezależny od BE-126) |
| U19 | Po PR #44 `social_message.sent_at` jest deterministyczny tylko dla WhatsApp; `attachments` zawsze `[]` | kod: `SocialWebhookController:406–409` (WhatsApp: czas platformy), `:312`, `:350` (FB/IG: `Instant.now()` webhooka), `:311/:349/:417` (`attachments = null`); `SocialMessageServiceImpl:226–229` | unikalność `(tenant_id, external_message_id, sent_at)` (DB-065) nie deduplikuje redelivery FB/IG — BE-132 najpierw ujednolica źródło `sent_at`; social nie ma obiektów S3 |

## 3. Decyzje otwarte (właściciel produktu nie odpowiedział)

Każda: opcje → **ZAŁOŻENIE DO POTWIERDZENIA** (domyślne, na nim działają tickety) → wpływ alternatywy.

**D1 — semantyka retencji treści wiadomości.** Opcje: (A) DELETE wierszy i załączników S3 razem z purge kontaktu, w istniejącej kategorii
`CONTACT_INTERACTIONS`; (B) anonimizacja (zostają metadane); (C) osobna kategoria `MESSAGE_CONTENT`.
**STAN: D1 FORMALNIE POTWIERDZONE przez właściciela produktu 2026-09-30 — opcja A.** Dwa momenty: **(a) 2026-09-20 (BE-124)** — A przyjęte do
realizacji na podstawie polecenia realizacji po przedstawieniu założenia domyślnego, **bez wyraźnego potwierdzenia właściciela**; na tym założeniu
roboczym zaimplementowano i zmergowano do `develop` cały łańcuch BE-124 → BE-125 → BE-126 → BE-127 → BE-128 (wszystkie ✅). **(b) 2026-09-30** —
właściciel produktu w rozmowie z asystentem formalnie potwierdził: „Dla D1 wybieram opcję A". To **jest** zatwierdzenie decyzji (nie tylko
kontynuacja założenia): tickety warunkowe dla wariantu C — DB-063, BE-130, FE-111 — są **zamknięte jako N/A** 2026-09-30 (patrz `**Status:**` w
TASKS-DATABASE.md/TASKS-BACKEND.md/TASKS-FRONTEND.md). Potwierdzenie **nie zmienia stanu wdrożenia** — BE-124…128 były już zaimplementowane pod
założeniem A i kod pozostaje bez zmian; to czysto formalne domknięcie decyzji produktowej. Pytania do właściciela dot. **pozostałych decyzji
(D2–D10, wciąż otwartych)** czekają nadal na przekazanie/potwierdzenie — D1 nie jest już jedną z nich.
**DECYZJA: A** (bez zmian CHECK/enuma/UI). ADR z uzasadnieniem i dowodami: `TASKS-BACKEND.md` BE-124 (notatka z realizacji, sekcja 1; dopisek o
potwierdzeniu 2026-09-30). Skutki A: usunięcie jest nieodwracalne
(bucket S3 niewersjonowany, bez lifecycle), ten sam przycisk „Usuń teraz" dla `CONTACT_INTERACTIONS` zyskuje szerszy skutek (FE-110 ma wejść
razem z BE-126), wiadomość dziedziczy wiek kontaktu (`started_at`).
**Wpływ alternatyw (ścieżka zmiany — zapis historyczny uzasadnienia decyzji; zob. zdanie zamykające poniżej: B i C są zamknięte, D1 = A jest ostateczne):**
- **(B)** — BE-125: `purgeByContactIds` → UPDATE PII (`from/to/cc/bcc`, `subject`, `body_*`, `attachments = '[]'`; social: `content`, `sender_external_id`),
  obiekty S3 nadal usuwane, potrzebny znacznik „zanonimizowano" (nowy ticket DB), inaczej sweep nie jest idempotentny; BE-126: `detachContactReferences` zostaje
  + wołanie anonimizacji; BE-127/128: UPDATE i liczenie wierszy niezanonimizowanych; DB-059: predykat indeksu + kolumna; DB-063/BE-130/FE-111 nie wchodzą;
  DB-065/067, BE-132…135: sens Poziomu 2 znika (wiersze nie znikają) — ponowna ocena; tabele rosną bez końca; FE-110: „zanonimizowane" zamiast „usunięte".
- **(C)** — wchodzą DB-063 (3 CHECK-i: `tenant_retention_policy_data_category_check`, `tenant_retention_pending_summary_data_category_check`,
  `retention_purge_log_data_category_check`, backfill = wartość `CONTACT_INTERACTIONS`), BE-130 (enum, `seedDefaultPolicies`, ewaluacja, purge, `ReclaimTarget`, kontroler),
  FE-111 (UI, 4× i18n); BE-126 wraca do odcinania referencji, a usuwanie wiadomości i sweep (BE-127) oraz liczenie (BE-128) przechodzą pod `MESSAGE_CONTENT`;
  BE-125 zyskuje wariant po wieku; DB-059: indeks bez `WHERE contact_id IS NULL`; DB-065/067 bez zmian (próg z `MESSAGE_CONTENT`); FE-110 ogranicza się do dryfu `CAMPAIGN_DATA`.
- **Zmiana decyzji po starcie prac (zapis historyczny):** przed merge BE-125/126 zmieniałyby się wyłącznie tickety; po uruchomieniu purge na realnych danych usunięcie
  jest nieodwracalne, więc B/C dotyczyłyby tylko danych jeszcze nieusuniętych w chwili ewentualnej zmiany. Opcja zabezpieczająca, przyjęta i pozostająca w mocy: flaga
  `retention.purge.delete-messages` (domyślnie `false` = dotychczasowe odcinanie referencji). **Bramka produkcyjna (stan 2026-09-30, zaktualizowana):** D1, D9
  i D10 jako DECYZJE PRODUKTOWE są teraz WSZYSTKIE formalnie potwierdzone (nic już nie czeka na odpowiedź właściciela w tym sensie) — ale **potwierdzenie
  decyzji ≠ kompletna techniczna realizacja**, i to jest teraz jedyny powód, dla którego bramka zostaje zamknięta. `auto_purge_enabled` dla
  `CONTACT_INTERACTIONS` pozostaje zablokowany na produkcji: **D9** — kod (DB-061 ✅/DB-062 ✅/BE-129 ✅) istnieje i działa, ale tryb podglądu w UI
  administratora (**FE-112**, dziś `⬜ Nie rozpoczęte`) — część mitygacji ryzyka fałszywych trafień R9 (licznik + wymagane potwierdzenie w UI) — nie jest w
  pełni operacyjna bez niego; **D10** — decyzja (maskowanie) potwierdzona prawnie, ale **BE-142 (maskowanie `audit_log`) jeszcze niezaimplementowane** —
  `audit_log` dziś fizycznie NIE maskuje PII. Bramka odblokuje się po **wdrożeniu BE-142** (wymagane) i **zalecanym** FE-112 (nie twardo wymaganym, ale
  usuwa ostatnią lukę mitygacji R9). Rozróżnienie „decyzja potwierdzona" ≠ „wdrożenie kompletne" jest kluczowe — nie mylić tych dwóch stanów.
- **B i C zamknięte (2026-09-30):** obie ścieżki analizy powyżej — (B) anonimizacja i (C) kategoria `MESSAGE_CONTENT` — są od tej daty **zamknięte i nieaktualne**:
  D1 = A jest ostateczną, formalnie potwierdzoną decyzją (tickety warunkowe DB-063/BE-130/FE-111 zamknięte jako N/A, patrz wyżej). Analiza zostaje w dokumencie
  wyłącznie jako historyczne uzasadnienie wyboru A, nie jako otwarta opcja.

**D2 — wolumen i próg partycjonowania `email_message`.** Brak liczb w PRD. Opcje progu: niski (≈ 2 GB / 500 tys. wierszy), średni, wysoki.
**STATUS: ZAMKNIĘTE decyzją właściciela 2026-10-04 (partycjonować przed wdrożeniem); progi bez zmian.** Produkcji jeszcze nie ma, więc bramka pomiarowa (DB-066) nie jest wymagana; progi G1–G4 poniżej pozostają bez zmian i nie są już warunkiem wejścia w konwersję.
**ZAŁOŻENIE: wchodzimy w konwersję, gdy spełnione jest którekolwiek z:** G1 `pg_total_relation_size('email_message')` ≥ 10 GB (tabela + TOAST +
indeksy); G2 ≥ 2 mln wierszy; G3 prognoza z tempa ostatnich 3 miesięcy przekracza G1 lub G2 w ≤ 12 miesięcy; G4 objaw operacyjny — p95 batcha
DELETE w purge > 5 s albo udział martwych krotek > 20 % po VACUUM przez > 7 dni. Konwersja to L (klucz złożony, dedup, ~20 plików), więc
potrzebuje 12 miesięcy zapasu. Próg jest **szacunkiem** (jak próg 50–100 mln dla archiwum), kalibrowanym przez DB-066 na danych środowiska
docelowego. Wpływ: próg niższy → konwersja wcześniej (większy koszt, mniejszy zysk); wyższy → konwersja na produkcyjnej tabeli pod presją
(backfill + blokady). Decyzja 2026-10-04: DB-067/BE-134/BE-135 nie czekają na bramkę (partycjonowanie przed wdrożeniem produkcyjnym).

**D3 — zakres RODO Art. 17/15.** Opcje: (A) rozszerzyć funkcje SQL i podłączyć je do `GdprServiceImpl`; (B) rozszerzyć tylko Javę;
(C) zostawić stan obecny. **ZAŁOŻENIE: A** — jedna transakcyjna implementacja DB (`anonymize_customer`, `export_customer_data`) obejmująca
wiadomości, `scheduled_callback`, `campaign_contact`, `campaign_contact_archive`, `contact.notes` oraz — po ocenie w DB-060 — transkrypcje i
`contact_ai_summary` (uznane za PII: treść rozmowy), a `GdprServiceImpl` sprząta S3 (nagrania, EML, załączniki) i woła funkcje.
Wpływ: (B) — DB-061/062 sprowadzają się do audytu i naprawy STABLE, logika trafia do BE-129 (wiele repozytoriów, brak jednej transakcji);
(C) — U2/U4 pozostają otwartą luką prawną, EPIC-30 nie domyka NFR-RODO01/02.
**Korekty z DB-060/BE-124 (2026-09-20):** funkcja `anonymize_customer` jest dziś niedziałająca dla klienta z kontaktami (trigger V016, U4) — DB-062 zmienia kolejność instrukcji, DB-079 zawęża trigger; przepływ ma DWIE ścieżki REST (U2), więc BE-129 przekierowuje `DELETE /api/customers/{id}` na `GdprService`; zbiór danych podmiotu wyznacza **D9** (przy D9 = B luka z DB-060 F2 zostaje); PG `contacts_dw` (U9) i `audit_log` (D10) leżą poza funkcją — DB-078/BE-141 i BE-142.

**D4 — deduplikacja e-mail po partycjonowaniu.** Opcje: (A) `message_at TIMESTAMPTZ NOT NULL` + unikalność `(tenant_id, message_id_header,
message_at)`; (B) osobna niepartycjonowana tabela `email_message_dedup`. **ZATWIERDZONE przez właściciela 2026-10-07: A** (formalne potwierdzenie; wcześniej założenie robocze), z `message_at` = **czas zaobserwowany przez system**: INBOUND =
`Message#getReceivedDate()` (INTERNALDATE serwera IMAP — dokładnie dzisiejsze źródło `received_at`, zgodne z backfillem DB-067 `COALESCE(received_at, sent_at, created_at)`), `now()` tylko gdy brak INTERNALDATE;
OUTBOUND = `sentAt` ustawiane raz, nigdy nie zmieniane. **Korekta (BE-124 §7, 2026-09-20):** wcześniejsze założenie „`getSentDate()` (nagłówek Date) przed `getReceivedDate()`" odrzucone — nagłówek `Date` jest kontrolowany
przez nadawcę (data z przeszłości = natychmiastowa kwalifikacja do purge, z przyszłości = wiadomość nie wygasa) i rozjeżdża się z backfillem.
Pozostały problem: fallback `now()` (brak INTERNALDATE) jest niedeterministyczny — ta sama wiadomość pobrana ponownie (awaria przed flagą SEEN) dostanie inne `message_at`
(a na granicy miesiąca inną partycję), więc unikalność złożona jej nie wykryje; jedyną obroną zostaje `findByMessageIdHeader` bez daty, który nie
jest wspierany globalnym constraintem (wyścig dwóch instancji pollujących = możliwy duplikat). Skrzynka zmigrowana z historycznym INTERNALDATE daje „stare" świeżo zapisane wiadomości — obrona w BE-127 (`created_at < now() − 1 dzień`). Wpływ (B, N/A od 2026-10-07 — D4 = A zatwierdzone; DB-068/BE-136 → 🚫): warunkowe DB-068/BE-136 — globalna unikalność w jednej transakcji z INSERT (`ON CONFLICT DO NOTHING`), ale tabela dedup **też potrzebuje retencji** (własny purge).

**D5 — horyzont platformowy `audit_log`/`plugin_invocation_log`.** **ZAŁOŻENIE: 24 mies., konfigurowalny** (`retention.platform.audit-log-months`,
`retention.platform.plugin-invocation-log-months`; zgodnie z `ARCHITECTURE.md:848` i DESIGN EPIC-29 §12.1: log platformowy, nie per-tenant).
Wpływ: inna wartość = zmiana konfiguracji, bez zmiany kodu; wymaganie per-tenant retencji audytu = osobny epik (kolumna `tenant_id` bywa NULL).
Wymaga potwierdzenia prawnego (BE-123).

**D6 — baza czasowa `CAMPAIGN_DATA`.** Opcje: `archived_at` vs koniec kampanii. **ZAŁOŻENIE: `archived_at`** (jak dziś). Skutek uboczny: gdy
BE-120 zarchiwizuje zaległość (30 kampanii), jej zegar retencji startuje od dnia pierwszego uruchomienia — efektywna retencja PII wydłuża się
o wiek kampanii. Wpływ alternatywy: warunkowe DB-075 (kolumna `campaign_ended_at` w archiwum, backfill z `campaign.updated_at`, zmiana
`purge_campaign_contact_archive` i indeksu) + BE-140 (`countEligible`/`purgeEligible`).

**D7 — RLS dla `campaign_contact*` (PII).** **ZATWIERDZONE przez właściciela 2026-10-08: Opcja 1 — pełne RLS dla obu tabel**
(ALL+WITH CHECK+FORCE, symetrycznie z `contact`/`email_message`), odrzucone: Opcja 2 (węższa polityka bez DELETE na `campaign_contact`) i
Opcja 3 (odłożyć obie). Odblokowuje DB-072, DB-073. Znana, udokumentowana (nie blokująca) zależność: `archive_completed_campaign_contacts()`
wstawia wiersze wielu tenantów w jednym wywołaniu — pod rolą bez BYPASSRLS klauzula `WITH CHECK` odrzuciłaby wiersze innych tenantów niż GUC;
funkcja jest dziś martwa (pg_cron wyłączony, 0 wywołań z Javy, DB-070) i połączenie aplikacji (`ccapp`) ma BYPASSRLS, więc polityka nie zmienia
dziś niczego w działającej aplikacji — ryzyko aktywuje się tylko gdy BE-120 przywróci funkcję I zapadnie decyzja o przełączeniu roli połączenia
(odłożona, patrz §2). `campaign_contact` jest na ścieżce gorącej dialera (scheduler bez tenanta) — DB-073 wymaga przeglądu wszystkich ścieżek
`@Scheduled`/`@Async`/RabbitMQ (lista kandydatów już zebrana w DB-071 §2).

**D8 — archiwizacja kampanii zmienia widoczność (nowa, wynikła z weryfikacji U11).** **ZAŁOŻENIE: BE-120 dostarcza job za flagą
`retention.campaign-archive.enabled` (domyślnie `false`)**; włączenie po potwierdzeniu, że UI/raporty nie potrzebują kontaktów kampanii > 30
dni, albo po dodaniu czytelników archiwum (poza zakresem EPIC-30). Wpływ: domyślnie `true` = czyści `campaign_contact` od razu (zgodnie z zamiarem
V015), ale kontakty zakończonych kampanii znikają z UI i statystyk. **Stan 2026-10-09 (BE-120 ✅):** job dostarczony za flagą `false`; zgoda właściciela na włączenie NIE uzyskana; inwentaryzacja czytelników w notatce BE-120 (`TASKS-BACKEND.md`).

**D9 — zbiór danych podmiotu (Art. 17/15) (nowa, z DB-060 F2/F5).** Opcje: (A) dopasowanie także po identyfikatorze (telefon/e-mail klienta, znormalizowane: E.164, `lower()`, w obrębie tenanta) — kontakty, callbacki, rekordy kampanii i e-maile bez powiązania kluczowego —
z licznikami `matched_by_link`/`matched_by_identifier` w wyniku i **trybem podglądu (dry-run)** przed anonimizacją; (B) tylko powiązania kluczowe (`customer_id`, `last_contact_id`, `contact.campaign_contact_record_id`, `origin_contact_id`).
Dowody (DB-060 F2/F5; demo ma 2 klientów, więc liczby ilustrują mechanizm): `campaign_contact.customer_id` NULL w 37/37 (import go nie ustawia), 7/37 rekordów kampanii nieosiągalnych żadnym powiązaniem; 56 z 64 kontaktów bez `customer_id` niesie telefon/e-mail klienta w `remote_address`;
20 z 55 callbacków bez `customer_id` (wszystkie zgodne telefonem); 14 z 23 osieroconych e-maili ma adres zgodny z e-mailem klienta.
**STAN: D9 POTWIERDZONE przez właściciela produktu 2026-09-30 — opcja A (z podglądem).** Dwa momenty: **(a) 2026-09-20** — A przyjęte jako
założenie robocze, na którym zaimplementowano i zmergowano do `develop` **DB-061 ✅, DB-062 ✅, BE-129 ✅** (wspólna funkcja `fn_customer_subject_ids`,
liczniki `matched_by_link`/`matched_by_identifier`, endpoint podglądu `GET .../gdpr/anonymize/preview`). **(b) 2026-09-30** — właściciel produktu
w rozmowie z asystentem formalnie potwierdził opcję A. Ponieważ kod realizujący wariant A już istnieje i jest zmergowany, to potwierdzenie jest
**czystym formalnym domknięciem already-shipped behavior** — D9, w odróżnieniu od D1, nigdy nie miał osobnych ticketów warunkowych wariantu B do
zamknięcia jako N/A (różnica zakresu przy B żyła WEWNĄTRZ DB-061/DB-062/BE-129, patrz „Wpływ B" niżej — teraz zapis historyczny). Jedyny element
mitygacji ryzyka fałszywych trafień (R9) wciąż niekompletny: **tryb podglądu w UI administratora (FE-112) pozostaje ⬜ Nie rozpoczęte.**
**DECYZJA: A (z podglądem).** Ryzyko A: fałszywe trafienia (numer/adres wspólny dla rodziny, centrali, skrzynki zbiorczej) przy nieodwracalnej operacji, a w Art. 15 ujawnienie danych osób trzecich — mitygacja: podgląd z licznikami i wymagane potwierdzenie w UI
(BE-129 ✅, FE-112 ⬜), normalizacja i test wspólnego numeru (DB-062 ✅), jedna funkcja pomocnicza dla eksportu i anonimizacji (DB-061 ✅ → DB-062 ✅).
**Wpływ B (zamknięty i nieaktualny od 2026-09-30 — zapis historyczny uzasadnienia wyboru A):** DB-062 bez ścieżki po identyfikatorze (predykat `customer_id` = no-op dla kampanii), bez liczników `matched_by_*` i trybu podglądu, AC o wiadomościach osieroconych sprowadza się do „osierocone nietknięte"; DB-061 bez `matched_by_*` i bez funkcji pomocniczej D9;
BE-129 bez endpointu podglądu (wraca do M); FE-112 bez podglądu (zostają teksty i przepięcie listy klientów). Luka prawna, którą wariant B zostawiłby otwartą — w demo pomijane: 56 kontaktów (z transkryptami, podsumowaniami i nagraniami), 30 rekordów kampanii, 20 callbacków, 14 e-maili.

**D10 — PII w `audit_log` (nowa, z DB-060 F7).** `audit_log` (1227 wierszy live) trzyma pełne snapshoty klienta (`firstName`, `lastName`, `phone[]`, `email[]`, `customFields`, `gdprConsent`, `externalId`, także w `old_value`) i kontaktu (`remoteAddress`, `channelMetadata`, `notes`, `recordingUrl`);
`@Audited` serializuje całą encję (`AuditAspect#serializeToJson` usuwa dziś tylko hasła i tokeny).
**STAN: D10 POTWIERDZONE PRAWNIE przez właściciela produktu 2026-09-30 — maskowanie kluczy PII w snapshotach wierszy podmiotu przy anonimizacji,
wiersze zostają** (rozliczalność Art. 5(2)/30; wyjątek Art. 17(3)(b)/(e) — potwierdzenie prawne uzyskane 2026-09-30), oraz (Could) zatrzymanie
zapisu PII u źródła (BE-142). **WAŻNE — decyzja ≠ wdrożenie:** w odróżnieniu od D1 i D9, kod realizujący D10 **jeszcze NIE ISTNIEJE**. Ticket
`TASKS-BACKEND.md` BE-142 pozostaje `⬜ Nie rozpoczęte` — potwierdzenie zdejmuje z niego warunkowość (był „[WARUNKOWY: D10]", teraz jest zwykłą
pozycją backlogu, Priorytet Could Have bez zmian) i odblokowuje go do realizacji, ale nie wykonuje go automatycznie. **Do czasu ukończenia BE-142
`audit_log` nadal zawiera niezamaskowane PII — zarówno historyczne, jak i nowo zapisywane.**
**DECYZJA: maskowanie (wiersze zostają).** Alternatywa (`audit_log` bez zmian — świadoma decyzja prawna) jest od 2026-09-30 **zamknięta i
nieaktualna** — zapis niżej zostaje wyłącznie jako historyczne uzasadnienie: przy tej alternatywie BE-142 nie wchodziłby, maskowanie w DB-062 (Could) odpadałoby; ekspozycję ograniczałby wyłącznie horyzont 24 mies. (D5/BE-123), a snapshoty PII przeżywałyby anonimizację.

## 4. Fazy i fale

Graf (A → B = kolejność wykonania, B zależy od A; ‖ = równolegle; ✅ = zamknięte: 2026-09-20 BE-124, DB-060; 2026-09-21 BE-125, DB-079 (V094 w kodzie, niezastosowana na żywej bazie); 2026-09-22 BE-126, BE-143; 2026-09-24 DB-061, DB-062 (V095/V096 w kodzie, niezastosowane na żywej bazie), BE-129 (integracja Javy, bez nowej migracji); 2026-09-25 DB-059 (V097 w kodzie, niezastosowana na żywej bazie); 2026-09-26 BE-127 (ostatni Must Have EPIC-30), BE-145 (poza epikiem, naprawa BE127-01), BE-128 (dashboard/badge liczą wiadomości); 2026-10-08 DB-071 (raport klasyfikacji RLS), BE-123 (horyzont platformowy, WP-4 local-demo otwarte), DB-072, DB-073 (D7 ZATWIERDZONE: Opcja 1, pełne RLS `campaign_contact*`, V111/V112), DB-074 (RLS pozostałych tabel tenantowych, V113–V124), BE-138 (generalizacja `RlsValidationService`), DB-081 (NOWY ticket, FORCE RLS na 7 tabel klasy A bez FORCE od V012, V125); 2026-10-09 BE-122 (`RefreshTokenCleanupJob`, WP-4 local-demo otwarte), FE-110 (opisy kategorii retencji, WP-4 local-demo otwarte); 2026-10-09 (tura 20) BE-120 (`CampaignArchiveJob` za flagą `retention.campaign-archive.enabled=false`; zgoda właściciela i WP-4 otwarte), DB-056 (V126, zbatchowany `purge_campaign_contact_archive`; EXPLAIN na scratch i wydanie razem z BE-121 otwarte) — **uwaga:** ta lista nie odnotowuje zamknięć z tury 2026-10-01..2026-10-07 (DB-064/065/066/067/070/080, BE-131/132/133/134/135/136/141/142/146, FE-112) — zob. PROGRESS.md/TASKS-*.md dla pełnego stanu, poza zakresem tej aktualizacji):

```
Fala 0  BE-120 ✅, BE-122 ✅, BE-123 ✅, DB-057, DB-058 (niezależne)      DB-056 ✅ → BE-121      BE-144 (poza epikiem: obrazy MinIO, niezależne)      BE-145 ✅ (poza epikiem: PartitionReclaimJob nie DROP-uje już niepustej partycji contact*, ukończone 2026-09-26)
Fala 1  BE-124 ✅ (ADR D1) → BE-125 ✅ → BE-126 ✅ → BE-127 ✅ → BE-128 ✅ → FE-110 ✅ (też BE-126 ✅ → FE-110 ✅)      BE-124 ✅ → DB-059 ✅ → BE-127 ✅
        DB-060 ✅ (audyt PII) → DB-061 ✅ (+ wspólna reguła D9) → DB-062 ✅ → BE-129 ✅ → FE-112      DB-079 ✅ (trigger V016) → DB-062 ✅, BE-129 ✅      BE-125 ✅ → BE-129 ✅
        BE-141 ✅ → DB-078 (`contacts_dw`)      BE-125 ✅ → BE-143 ✅ (walidacja `s3Key`; niezależne od BE-126)      BE-142 ✅ (D10 potwierdzone 2026-09-30, ukończone 2026-09-30)      [D1=C: BE-124 ✅ → DB-063 🚫 → BE-130 🚫 → FE-111 🚫 (zamknięte 2026-09-30, D1=A potwierdzone)]
Fala 2  DB-064 (RLS wiadomości) → DB-065 (social) → BE-132 → BE-133      BE-126 ✅ → DB-065      DB-071 ✅ → DB-072 ✅ ‖ DB-073 ✅ ‖ DB-074 ✅, BE-138 ✅, BE-139      DB-071 ✅ → DB-081 ✅ (FORCE RLS 7 tabel klasy A, odkrycie BE-138)
Fala 3  DB-066 (BRAMKA D2/D4) → [go] DB-067 (email) → BE-134 → BE-135      [D4=B: DB-068 → BE-136]
Fala 4  DB-069 → BE-137 (bramkowane)   DB-070   [D6≠archived_at: DB-075 → BE-140]   DB-076   DB-077 (dokumentacja, po falach 0–1)
```

| Fala | Cel | Priorytet | Złożoność (vs ocena zlecenia) |
|---|---|---|---|
| 0 | Martwe harmonogramy i porządki (bez decyzji PO, z wyjątkiem D5 i D8) | Should (DB-058 Could) | S; **BE-123 = M** (osobna ścieżka horyzontu, rozszerzenie `PartitionScanner`, wiersze z `tenant_id` NULL) |
| 1 | Retencja treści wiadomości + RODO (+ `contacts_dw`, trigger V016) | Must (DB-078, DB-079, BE-141 Should; BE-142 Could) | S–M; **BE-129 = L** i **DB-062 = L** (po korektach z DB-060: dwie ścieżki REST, zbiór podmiotu D9 z podglądem, stany operacyjne, klucze S3), DB-061 = M; FE-112 = M; BE-143 = S (Must, bezpieczeństwo) |
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
(kontakt usunięty, plik zostaje) — opisana w BE-124 (ryzyko (a)) wraz z rekomendacją; sama walidacja nie wystarcza (R6).

## 7. Ryzyka

- **R1 RLS pod rolą ograniczoną** (U8): łańcuch purge, GDPR i joby nigdy nie były uruchamiane pod rolą bez BYPASSRLS; DELETE bez polityki = 0 wierszy
  po cichu. Mitygacja: DB-064, BE-138, BE-139, testy `SET ROLE app_user` w każdym tickecie DDL/DML. **Doprecyzowanie z DB-062 (2026-09-24, potwierdzone empirycznie na scratch DB):** to dotyczy DELETE/UPDATE — dla INSERT bez pasującej polityki (np. `audit_log`, brak polityki INSERT od V012) PostgreSQL rzuca twardy błąd zamiast cichego pominięcia wiersza; `anonymize_customer` (V096) pod `app_user` kończy się w trybie rzeczywistym pełnym ROLLBACK-iem na kroku `INSERT INTO audit_log`, nie częściowym sukcesem.
- **R2 Destrukcyjne joby na danych demo** (archiwizacja 37 wierszy, purge 18–23 wiadomości, S3): WP-4, zgoda właściciela, dry-run liczący kandydatów.
- **R3 Kolejność Poziom 1/Poziom 2** dla S3: awaria między usunięciem wiersza a obiektu osierocia plik; przyjęta kolejność „dzieci przed rodzicem,
  S3 przed wierszem, brak postępu = koniec pętli" (BE-125/126). Ocena w BE-124 (sekcja 6): poprawna i konieczna dla D1 = A — dzisiejsza pętla
  (`RetentionPurgeServiceImpl#purgeContactInteractions` :171–192, `DELETE … RETURNING` przed sprzątaniem dzieci) byłaby po BE-126 błędna; „brak postępu" =
  0 faktycznie usuniętych kontaktów w iteracji; kontakty usuwane tylko spoza `contactIdsBlocked`.
- **R4 Numeracja migracji** (kolizje z gałęziami feature) — WP-3; wdrożenie z brakującym plikiem V092 nie przechodzi walidacji Flyway (DB-055).
- **R5 Ścieżki `@Scheduled` z RLS**: `archive_completed_campaign_contacts()` iteruje po `campaign` (FORCE RLS); pod rolą bez BYPASSRLS i bez GUC zwróci
  zero kampanii — BE-120 musi to przetestować i wybrać rozwiązanie. **ZMIERZONE 2026-10-09 (BE-120, `SET ROLE app_user`):** bez GUC 0 kampanii i fałszywy `SUCCESS` w `cron_log`; GUC `''` → błąd uuid; GUC = tenant A → tylko A; BYPASSRLS → wszyscy tenanci. Rozwiązanie (wariant per tenant / `SECURITY DEFINER`) nadal nierozstrzygnięte — dormant do przełączenia roli połączenia; propozycje w notatce BE-120.
- **R6 Osierocone obiekty S3 spod `contact.recording_url` (EML kontaktów e-mail, nagrania)** (BE-124, ryzyko (a)): nic nie wymusza `RECORDINGS ≤ CONTACT_INTERACTIONS`
  (`RetentionPolicyServiceImpl#updatePolicy` :100–114), a `RecordingRetentionJob` szuka obiektów wyłącznie po `contact.recording_url` i przetwarza jedną paczkę 100 rekordów
  na tenanta na dobę — purge kontaktu osierocia obiekt (w przypadku EML: pełną kopię treści wiadomości). Sama walidacja nie wystarcza (zaległość, 30-dniowe vs kalendarzowe
  miesiące, `ended_at` vs `started_at`, zmiany polityk wstecz). Mitygacja: purge sprząta obiekt z `recording_url` w fazie „S3 przed wierszem" (BE-126, zakres do potwierdzenia — **stan 2026-09-22: świadomie ODŁOŻONE w BE-126 (kolizja z równoległym BE-143), z gotowym API `EmailAttachmentKeys#isRecordingKeyOwnedByTenant` do użycia — czeka na jawne podjęcie jako follow-up, nowego ticketu celowo nie założono**)
  + walidacja jako osobny follow-up po zgodzie PO. Stan live: 0 osieroconych (75/75), brak naruszeń polityk — ryzyko utajone.
- **R7 TTL/lifecycle na `pending/` skasowałby załączniki wysłanych wiadomości** (BE-124): klucze wysłanych załączników OUTBOUND wskazują na `email-attachments/{tenantId}/pending/…`
  (live 8 z 9). Mitygacja: BE-131 skorygowany (kandydat = niewskazywany przez żaden wiersz, albo najpierw „promocja" do `{messageId}/` przy wysyłce); reguła lifecycle bucketu dopiero po promocji.
- **R8 Kolejność S3 → DB w `GdprServiceImpl`** (DB-060 F8.3, BE-124 §3): dziś nagrania są kasowane w S3 PRZED zmianą w bazie — błąd DB po usunięciu nagrań zostawia klienta niezanonimizowanego z nieodwracalnie usuniętymi plikami;
  `RecordingService#deleteFromS3` połyka `S3Exception`, więc licznik `failed` nigdy nie rośnie, a log „failed=0" jest nieprawdziwy (U17). Mitygacja: BE-129 — DB twardo w jednej transakcji (`anonymize_customer`), S3 dopiero po commit metodą zwracającą wynik per klucz, niepowodzenia do audytu (klucze nie znikają).
- **R9 Fałszywe trafienia przy dopasowaniu po identyfikatorze (D9 = A):** numer/adres wspólny dla wielu osób → nieodwracalna anonimizacja cudzych danych (Art. 17) albo ujawnienie ich w eksporcie (Art. 15). Mitygacja: tryb podglądu z licznikami `matched_by_link`/`matched_by_identifier` i wymagane potwierdzenie (BE-129, FE-112), normalizacja i test wspólnego numeru (DB-062).
- **R10 Wersjonowanie bucketu / Object Lock / globalny lifecycle vs `DeleteObject`** (H-2 z code review BE-125; hipoteza do potwierdzenia w środowisku docelowym): `DeleteObject` bez `versionId` w buckecie z wersjonowaniem tylko dodaje delete marker (dane, w tym PII załączników, zostają), a przy Object Lock zwraca błąd (→ `s3Failures`, kontakt zablokowany na stałe).
  `DEPLOYMENT.md` §21 (≈ l. 1209–1211) opisuje prod MinIO bez wersjonowania, ale z globalnym `mc ilm add … --expiry-days 365` na całym buckecie `contact-center-recordings` — tym samym, do którego trafia `email-attachments/`. Skutki do potwierdzenia z właścicielem: (1) w produkcyjnym buckecie nie może być włączone wersjonowanie ani Object Lock,
  inaczej purge nie usuwa PII z załączników; (2) czas życia obiektów (365 dni, globalnie) jest niezależny od retencji tenanta (`CONTACT_INTERACTIONS`, np. 24 mies.) — załączniki znikną, zanim purge usunie wiersze (martwe linki; `delete` traktuje brak obiektu jako sukces, więc purge na tym nie cierpi). Uwaga: „bucket S3 niewersjonowany, bez lifecycle" w §3 (D1)
  opisuje stan zmierzony na local-demo (BE-131: `mc ilm rule ls`), nie prod. Mitygacja: pytanie do właściciela o konfigurację prod S3 (wersjonowanie, Object Lock, lifecycle); rozstrzygnięcie w BE-131 i BE-126 (uwagi z code review BE-125); BE-144 dotyczy wyłącznie obrazów, nie polityk bucketu.
- **R11 `PartitionReclaimJob` usuwa (`DROP TABLE`) niepustą partycję `contact*` — dangling PII bez ścieżki sprzątania** (BE127-01, code review BE-127, `CR-BACKEND.md`): `warnIfStillHasRows` loguje WARN, gdy partycja-kandydat do `DROP` wciąż ma wiersze, ale **kontynuuje `DROP` mimo to** (`PartitionReclaimJob.java` ≈ 184–194); `email_message`/`social_message` nie mają FK do `contact`, więc `DROP` nie kaskaduje — wiadomości z `contact_id` wskazującym na usunięty wiersz stają się dangling, których sweep sierot BE-127 (kryterium `contact_id IS NULL`) nigdy nie złapie. Wyzwalacz: wieloletnia, niezauważona awaria Poziomu 1 purge dla JEDNEGO tenanta (próg DROP liczony po najdłuższej retencji ze WSZYSTKICH tenantów — dziś 60 mies., demo: 0 incydentów, 60/60 mies. bufor). **Nie jest to regresja BE-127** — job istnieje od BE-115 (EPIC-29), nie zmieniony w BE-125/126/127. Mitygacja: **BE-145 ✅ (ukończone 2026-09-26, poza epikiem — dotyczy silnika partycji EPIC-29, nie tylko wiadomości)** — zgodnie z WP-5 („niepusta → WARN i pominięcie", nie „WARN i kontynuuj") dla tabel per-tenant: `warnIfStillHasRows` zmienione z `void` na `boolean` (`true` = partycja wciąż ma wiersze), `reclaimTable` wykonuje `continue` i pomija `DROP`, z wyjątkiem horyzontu platformowego (`audit_log`/`plugin_invocation_log`), gdzie niepusta partycja po horyzoncie wciąż jest oczekiwana (bez zmiany). Code review 4/5: BE145-01 (major, nienaprawiony świadomie — brak eskalacji przy partycji pomijanej tydzień po tygodniu bez końca) i BE145-02 (minor, nienaprawiony). Powiązanie z BE-123 (✅, EPIC-30, ukończone 2026-10-08): **BE-145 wdrożone jako pierwsze** — wykonawca BE-123 przyjął `contact`/`contact_event`/`contact_transcription`/`contact_ai_summary` jako `DropMode.ONLY_IF_EMPTY` (nie `AFTER_CUTOFF` jak zakładał opis ticketu przed korektą), zgodnie ze skorygowaną uwagą w `TASKS-BACKEND.md` (BE-123) — potwierdzone w notatce z wykonania BE-123.
