---
name: project-be141-etl-remote-address-removal
description: BE-141 (EPIC-30) – usunięcie remote_address z ETL contact→contacts_dw; warunek wstępny dla DB-078 (drop kolumny); pierwsze testy real-DB dla PostgresDwWriter/EtlSyncServiceImpl
metadata:
  type: project
---

BE-141 usunął `contact.remote_address` (PII – CLI/e-mail klienta) ze ścieżki ETL do DW: usunięty z
`EtlSyncServiceImpl#SELECT_CONTACTS_FOR_ETL` i `ContactDwRowMapper`, z rekordu `ContactDwRow` (komponent
+ Javadoc) i z `PostgresDwWriter#UPSERT_SQL`/bind params. `ClickHouseDwWriter` nigdy tej kolumny nie
zapisywał (schemat `dw/migrations/V001` – „brak PII") – tylko poprawiony stały Javadoc.
Kolumna `contacts_dw.remote_address` w PostgreSQL (V036) ZOSTAJE w schemacie (nullable) – jej drop to
DB-078, zależny od BE-141, realizowany osobno.

**Why:** Minimalizacja danych RODO (EPIC-30) – kolumna nie służyła analityce w DW, tylko niosła PII.

**How to apply:** Przy DB-078 (drop `contacts_dw.remote_address`) nie trzeba zmieniać żadnego kodu Java
– writer już dziś nie wiąże żadnego parametru na tę kolumnę (sprawdzone w teście na schemacie z kolumną
obecną). Jedyny krok dla DB-078: migracja SQL + (opcjonalnie) powtórzenie
`PostgresDwWriterIntegrationTest` po drop, by potwierdzić brak regresji na nowym schemacie.

Dodane pierwsze testy integracyjne (Testcontainers, pełny Flyway) dla tej ścieżki – wcześniej jej NIE
było (`infrastructure/etl` miał zero testów, `EtlSyncServiceImplTest` był czysto mockowy):
- `PostgresDwWriterIntegrationTest` (`infrastructure.etl`) – nowy wiersz, pola nullable, batch,
  idempotentność ON CONFLICT, no-op na pustej liście; asercja `remote_address IS NULL` po zapisie.
- `EtlSyncServiceImplIntegrationTest` (`domain.etl`) – wywołuje bezpośrednio pakietowo widoczną
  `fetchContactsForEtl(...)` (realny SQL + realny `RowMapper`) na prawdziwym wierszu `contact`
  (z `remote_address` ustawionym w źródle, by dowieść że nie przechodzi do DW), potem
  `PostgresDwWriter#upsert`. Celowo NIE używa globalnego `syncTable`/`etl_sync_state` (ten wiersz jest
  współdzielony między klasami testowymi na tym samym kontenerze) – cutoff tuż przed seedem izoluje wynik
  od danych innych testów bez czyszczenia stanu ETL.
Powiązane: [[project-be030-etl-pipeline]], [[feedback-contact-ref-integrity-trigger-fk]].
