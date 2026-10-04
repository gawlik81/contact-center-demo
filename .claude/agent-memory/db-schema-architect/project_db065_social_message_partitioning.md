---
name: project_db065_social_message_partitioning
description: DB-065/V100 (2026-10-01) -- partycjonowanie RANGE social_message po sent_at; odkrycie zaleznosci v_customer_timeline (DROP VIEW/CREATE OR REPLACE przy online-swap); PartitionReclaimJob juz istnieje i jest generyczny (BE-133 = jedna linia); ostrzezenie FB/IG sent_at niedeterministyczny dla BE-132; regresja 6 istniejacych testow DB-059 po partycjonowaniu (nazwy indeksow potomnych + COMMENT nieodtworzony) i nowy reuzywalny helper PostgresTestDatabase#explainUsesIndexOrItsPartitionChildren
metadata:
  type: project
---

**DB-065/V100 (2026-10-01) -- trzeci ticket lancucha EPIC-30 DB-064 -> DB-065 -> BE-132 -> BE-133.**
Plik: `V100__partition_social_message.sql`. `social_message` (zwykla tabela od V010, 0 wierszy
live) -> RANGE-partycjonowana po `sent_at`, PK zlozony `(message_id, sent_at)`, UNIQUE zlozony
`(tenant_id, external_message_id, sent_at)`. Wzorzec online-swap 1:1 z V085 (`contact_event`,
DB-049) -- guard COUNT(*)>10000 RAISE EXCEPTION, `SET LOCAL lock_timeout='10s'` (wzorzec V093, NIE
V085 ktore go nie mialo), tabela blizniacza `_new` -> INSERT SELECT -> RENAME -> odtworzenie
indeksow/RLS/comments -> weryfikacja COUNT -> DROP starej -> rename PK/FK/UNIQUE/indeksow z `_new`
do finalnych nazw. Partycje: `social_message_2026_10/11/12` + `social_message_default` (0 wierszy
=> "zakres danych" = biezacy miesiac wzgledem daty napisania migracji, 2026-10-01; HARDCODOWANE,
nie `now()` -- test musi uzywac tych samych literalnych miesiecy, nie `now()`, inaczej test przestaje
byc deterministyczny po przejsciu kalendarza).

**ODKRYCIE W DRY-RUNIE (nieprzewidziane w tresci ticketu) -- `v_customer_timeline` (V017, tresc
finalna w V025) zalezy od `social_message` przez UNION ALL (`FROM social_message sm JOIN contact
...`).** PostgreSQL rejestruje to jako twarda zaleznosc `pg_depend` po OID tabeli -- po `RENAME
social_message -> social_message_old` widok "podazyl" za OID-em starej tabeli; `DROP TABLE
social_message_old` bez wczesniejszego usuniecia widoku konczy sie `2BP01` ("cannot drop table ...
because other objects depend on it"). Zweryfikowane pg_depend na scratch: to JEDYNY zalezny obiekt
(zero funkcji -- funkcje PL/pgSQL odpytujace tabele po nazwie w tresci SQL NIE tworza twardej
zaleznosci katalogowej, tylko widoki/FK/inne obiekty z `pg_rewrite`/bezposrednim odwolaniem). Fix:
`DROP VIEW IF EXISTS v_customer_timeline;` PRZED `DROP TABLE social_message_old;`, potem
`CREATE OR REPLACE VIEW v_customer_timeline AS ...` (tresc 1:1 z V025, zero zmian SQL -- widok
odpytuje po nazwie, nie po OID, wiec po przemianowaniu nowej tabeli na `social_message` definicja
dziala bez modyfikacji) zaraz po. **Zasada na przyszlosc (DB-067, `email_message`, ten sam widok ma
rowniez `FROM email_message em`):** PRZED pisaniem kazdej migracji online-swap sprawdz
`pg_depend`/`pg_rewrite` dla WSZYSTKICH widokow zaleznych od tabeli (nie tylko tych wymienionych w
tresci ticketu) -- `SELECT DISTINCT dependent_view.relname FROM pg_depend JOIN pg_rewrite ON
pg_depend.objid=pg_rewrite.oid JOIN pg_class dependent_view ON pg_rewrite.ev_class=dependent_view.oid
JOIN pg_class source_table ON pg_depend.refobjid=source_table.oid WHERE source_table.relname =
'<tabela>'`. Ten sam wzorzec DROP VIEW/CREATE OR REPLACE jest juz precedensem w V025 (ktora z
analogicznego powodu -- zmiana typu kolumny -- usuwala/odtwarzala ten sam widok).

**WAZNE ODKRYCIE ARCHITEKTONICZNE -- `PartitionReclaimJob`/`PartitionScannerImpl` (BE-115/BE-145)
JUZ ISTNIEJA i sa w 100% generyczne po nazwie tabeli.** Ticket DB-065 mowi "DROP idzie wylacznie
przez PartitionReclaimJob -- to bedzie BE-133" (brzmi jak nowy job), ale w rzeczywistosci job
istnieje od BE-115/BE-145 (`backend/app/src/main/java/com/contactcenter/domain/retention/
PartitionReclaimJob.java` + `PartitionScannerImpl.java`) i juz dzis obsluguje `contact`,
`contact_event`, `contact_transcription`, `contact_ai_summary` przez hardcodowana mape
`TABLE_CATEGORIES` (nazwa tabeli -> `RetentionDataCategory`). `PartitionScannerImpl.listPartitions`/
`countRowsByTenant`/`dropPartition` sa CALKOWICIE generyczne (parsuja `<tabela>_YYYY_MM` z
`pg_tables`, licza wiersze przez `FROM ONLY "<partycja>"`) -- ZERO zmian potrzebnych w tej klasie dla
`social_message`. Reguła "DROP tylko gdy partycja jest pusta" (BE-145, 2026-09-26) zostala
wprowadzona WLASNIE z mysla o `email_message`/`social_message` (cytat z javadoc
`PartitionReclaimJob`: "trzeci, nieoczywisty mechanizm usuwania wierszy contact... mogl osierocic
email_message/social_message... bez zadnej sciezki ich pozniejszego usuniecia"). **Wniosek dla
BE-132/BE-133:** koszt BE-133 jest bardzo niski -- JEDNA linia w `PartitionReclaimJob.TABLE_CATEGORIES`
(`"social_message", RetentionDataCategory.CONTACT_INTERACTIONS`) + ewentualnie analogiczny wpis dla
`email_message` przy DB-067. Nazwy partycji V100 (`social_message_YYYY_MM` + `_default`) juz
spelniaja konwencje, ktorej `PartitionScannerImpl` wymaga.

**Guard FB/IG `sent_at` niedeterministyczny (warunek wejscia z ticketu, potwierdzone w kodzie
2026-10-01, `SocialWebhookController.java`):** `parseFacebookEvent` (linie ~305-313) i
`parseInstagramEvent` (~343-351) OBIE ustawiaja `sent_at = Instant.now()` w chwili przetworzenia
webhooka -> NIEDETERMINISTYCZNE, redelivery dostaje inny `sent_at`. `parseWhatsAppMessage` (~386-419)
czyta `timestamp` z payloadu (Unix epoch sekundy), fallback `Instant.now()` TYLKO gdy `timestamp<=0`
-> DETERMINISTYCZNE. Po V100 `UNIQUE(tenant_id, external_message_id, sent_at)` chroni WhatsApp jak
dawniej, ale NIE chroni FB/IG -- udokumentowane w `COMMENT ON CONSTRAINT uq_social_message_external_id`
(persystuje w katalogu, przezyje kolejne sesje) i w naglowku V100. BE-132 MUSI naprawic zrodlo
`sent_at` dla FB/IG (czas z payloadu Meta) PRZED wdrozeniem na srodowisko z realnym ruchem FB/IG --
oba tickety (DB-065+BE-132) w jednym wydaniu, nigdy DB-065 samo.

**Test Testcontainers `SocialMessagePartitioningTest`** (`com.contactcenter.domain.social`, wzorzec
"pojedyncza swieza baza" jak `EmailSocialMessageRlsWritePoliciesTest`/DB-064) -- 19 testow (struktura,
routing/pruning partycji, unikalnosc zlozona x2 WhatsApp-OK/FB-slaba, RLS cross-tenant przez tabele
nadrzedna, funkcje rotacji). **Pulapki odkryte przy pisaniu testu:**
1. `pg_constraint WHERE conname='fk_social_message_tenant'` bez `AND conrelid='social_message'::regclass`
   zwraca >1 wiersz -- FK (jak PK/CHECK) propaguje WLASNY wiersz `pg_constraint` (ta sama nazwa) do
   KAZDEJ partycji, nie tylko do rodzica. Zawsze filtrowac po `conrelid` gdy test dotyka tabeli
   partycjonowanej ze wspoldzielonym kontenerem (inne klasy/metody moga juz dolozyc wlasne partycje
   przez `create_social_message_partition`).
2. Spring `JdbcTemplate` klasyfikuje SQLState `42501` (insufficient_privilege, RLS) GENERYCZNIE jako
   `BadSqlGrammarException` (fallback `SQLStateSQLExceptionTranslator`, klasa "42" = "syntax error or
   access rule violation") -- NIE jako `PermissionDeniedDataAccessException`. Komunikat
   "row-level security policy" jest w PRZYCZYNIE (`PSQLException`), nie w komunikacie zewnetrznego
   wyjatku Springa -- test musi schodzic po `getCause()` do korzenia (AssertJ `hasRootCauseMessageContaining`
   NIE istnieje w wersji AssertJ uzywanej w tym repo -- recznie `while (root.getCause()!=null) root=root.getCause()`).
   `DuplicateKeyException` (23505, unique violation) NATOMIAST dziala poprawnie przez standardowe AssertJ
   `isInstanceOf` -- Spring MA wpis `duplicateKeyCodes` dla PostgreSQL w sql-error-codes.xml, ale NIE ma
   dedykowanej klasy dla "insufficient_privilege"/42501.
3. EXPLAIN pruning na malej partycji (1 wiersz) daje `Seq Scan` (poprawnie, nie Index Scan) -- test
   sprawdza KTORA partycja jest w planie (pruning), nie typ skanu w tej partycji (zob.
   [[project_contact_center]] "wazne odkrycie metodologiczne" o malych partycjach dev dajacych Seq
   Scan poprawnie).

**Metoda weryfikacji migracji:** dry-run przez `RunFlyway.java` (jak V082-090) na scratch DB
(`scratch_db100`, utworzona i usunieta w tej sesji, host `172.18.0.3:5432` -- IP bridge zmienne
miedzy sesjami) z PELNYM lancuchem migracji TEJ galezi (`filesystem:` lokalizacja, V001..V097,V099,V100
-- V098 z innej niezmergowanej galezi nieobecny, zero problemu, Flyway tylko wymaga rosnacej
kolejnosci). Pierwszy dry-run zlapal problem widoku (patrz wyzej) -- `ROLLBACK calosci`, baza
nietknieta. Po poprawce: pelny lancuch V001->V100 zielony, struktura/partycje/funkcje/RLS/widok
zweryfikowane manualnie SQL-em przed napisaniem testu Java.

**REGRESJA odkryta przez PEŁNY `mvn verify -pl app` (orchestrator, po przerwaniu sesji limitem) --
6 failures w 3 PRE-ISTNIEJACYCH plikach testowych DB-059/BE-127 (pisanych gdy `social_message` byla
zwykla tabela), NIEWYKRYTA przez moj wlasny selektywny `-Dtest=` bo nie uruchomilem calego modulu
`app` za pierwszym razem.** Dwie NIEZALEZNE przyczyny:
1. **`COMMENT ON INDEX idx_social_message_tenant_orphan_sent` nieodtworzony po online-swapie.**
   Zapomnialem, ze `RENAME`/przebudowa tabeli NIE dziedziczy komentarzy ze starej tabeli (jak
   indeksow/RLS/triggerow) -- dodalem `COMMENT` dla NOWEGO `idx_social_message_tenant_sent_at`, ale
   nie dla ODTWORZONEGO `idx_social_message_tenant_orphan_sent` (z DB-059/V097). Fix: dopisany
   `COMMENT ON INDEX idx_social_message_tenant_orphan_sent_new IS '...'` (tresc 1:1 z V097) zaraz po
   `CREATE INDEX` w sekcji 5 V100 -- komentarz "podazy" za OID-em przez pozniejszy `ALTER INDEX ...
   RENAME` w sekcji 8, wiec wystarczy go ustawic raz, na nazwie `_new`. **Zasada: przy KAZDYM
   online-swapie odtwarzajacym indeks z wczesniejszej migracji, zawsze sprawdz grepem czy oryginalna
   migracja miala `COMMENT ON INDEX`/`COMMENT ON COLUMN`/`COMMENT ON CONSTRAINT` dla danego obiektu --
   komentarze NIE sa czescia "oczywistej" listy (PK/FK/CHECK/RLS/indeksy), latwo je przeoczyc.**
2. **Indeks na partycjonowanej tabeli propaguje sie do KAZDEJ partycji jako ODREBNA relacja z
   AUTO-wygenerowana nazwa** (np. `social_message_2026_10_contact_id_sent_at_idx`), NIE z nazwa
   rodzica -- `EXPLAIN` w planie zapytania ZAWSZE wypisuje nazwe fizycznego indeksu DZIECKA danej
   partycji, nigdy nazwe indeksu rodzica. 3 istniejace testy (`OrphanMessagePurgeIndexesTest`,
   `SocialMessageOrphanPurgeIntegrationTest` x2, `SocialMessagePurgeIntegrationTest`) robily literalne
   `plan.contains("idx_social_message_...")` -- to NIGDY nie dziala na partycjonowanej tabeli,
   niezaleznie od tego czy planner faktycznie uzyl indeksu. **Fix: nowy reuzywalny helper**
   `PostgresTestDatabase.explainUsesIndexOrItsPartitionChildren(jdbc, plan, parentIndexName)` --
   sprawdza `plan.contains(parentIndexName)` (dziala dla NIE-partycjonowanych tabel, np. dzisiejsze
   `email_message`) ALBO odpytuje `pg_inherits`/`pg_class` (`SELECT c.relname FROM pg_inherits i JOIN
   pg_class c ON c.oid=i.inhrelid WHERE i.inhparent = ?::regclass`) o nazwy indeksow potomnych i
   sprawdza czy jeden z nich jest w planie. **Bezposrednio reuzywalny w DB-067 dla `email_message`,
   gdy ten sam problem powtorzy sie identycznie.**
3. **Dodatkowa, NIEZALEZNA pulapka w `SocialMessagePurgeIntegrationTest.explain_usesContactIndex`:**
   zapytanie `DELETE ... WHERE contact_id IN (...)` (BEZ filtra `sent_at` = kolumna partycjonowania)
   NIE MOZE przyciac (prune) zadnej partycji -- Append odwiedza WSZYSTKIE partycje, w tym prawie puste
   (inne partycje utworzone przez INNE klasy testowe we wspoldzielonym kontenerze, np.
   `social_message_2027_06/07` z `SocialMessagePartitioningTest$PartitionFunctions`). Te prawie-puste
   partycje POPRAWNIE dostaja `Seq Scan` (koszt ~1.0, mniej niz Index Scan) -- to NIE jest regresja.
   Usunieto globalne `.doesNotContain("Seq Scan")` dla tego konkretnego testu (zastapione komentarzem
   wyjasniajacym) -- dowodem braku regresji jest WYLACZNIE to, ze partycja z faktycznymi danymi
   (30 000 wierszy) uzywa indeksu (asercja przez helper z punktu 2). **Zasada: dla zapytan na tabeli
   partycjonowanej ktore NIE filtruja po kolumnie partycjonujacej (musza odwiedzic wszystkie partycje),
   `doesNotContain("Seq Scan")` na calym planie jest ZLYM testem -- sprawdzaj uzycie indeksu PRZEZ
   konkretna partycje (helper), nie brak Seq Scan globalnie.**

**Wniosek procesowy: selektywny `mvn test -Dtest=<lista>` (bez pelnego `mvn verify -pl app`) NIE
wystarczy jako dowod braku regresji dla migracji zmieniajacej fizyczna strukture tabeli (partycjonowanie)
-- trzeba uruchomic WSZYSTKIE istniejace testy dotykajace tej tabeli (grep `social_message`/nazwa
indeksu po calym `src/test`), nie tylko te z tego samego ticketu/sesji. Przy DB-067 (email_message)
PRZED napisaniem migracji zrob `grep -rl "idx_email_message" backend/app/src/test` i przejrzyj KAZDY
wynik pod katem (a) literalnych dopasowan nazw indeksow w EXPLAIN (podmien na helper z punktu 2) i
(b) zapytan bez filtra na kolumnie partycjonujacej (`message_at`) ktore odwiedza wszystkie partycje
(punkt 3).**

Powiazane: [[contact_center_project]] (wzorzec V085 online-swap, pulapka malych partycji dev),
[[project_db064_email_social_message_rls_write_policies]] (RLS odtworzona 1:1), [[feedback_migration_numbering_check]]
(V100 potwierdzone jako wolne na wszystkich galeziach + zywej bazie), [[feedback_rls_testing]]
(RLS przez tabele nadrzedna, partycje potomne relrowsecurity=f), [[project_db059_orphan_message_indexes]]
(3 testy regresji ktore zlapaly ten problem).
