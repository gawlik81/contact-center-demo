---
name: project-epic30-be125-message-purge
description: EPIC-30 BE-125 — kontrakt purgeByContactIds (email+S3, social), orkiestracja BEZ transakcji w serwisie, zablokowane kontakty, RLS „ciche 0 wierszy", punkty wejścia dla BE-126/127
metadata:
  type: project
---

BE-125 (2026-09-21, założenie D1 = A) — usuwanie wiadomości po `contact_id` z obiektami S3; NIE podpięte do `RetentionPurgeServiceImpl` (to BE-126, `detachContactReferences` tylko `@Deprecated`).

**Kontrakt (dla BE-126/127/129):**
- `EmailMessageService#purgeByContactIds(tenantId, contactIds)` → `PurgedMessages(deletedRows, s3ObjectsDeleted, s3Failures, s3Rejected, Set<UUID> contactIdsBlocked)`; `SocialMessageService#purgeByContactIds` → `int` (bez S3).
- Orkiestracja siedzi w `EmailMessageServiceImpl` (BEZ `@Transactional`), repo ma tylko dwie krótkie transakcje: `findAttachmentsByContactIds` (readOnly) i `deleteByIds` (`DELETE…RETURNING`). Odstępstwo od ticketu (`EmailMessageRepository#purgeByContactIds`): repo nie robi I/O do S3 i nie trzyma połączenia HikariCP na czas S3. Nie „naprawiać" dodaniem `@Transactional`.
- `EmailMessageServiceImpl#purgeRows(tenantId, rows)` (package-private) = fazy S3→DELETE niezależne od selektora; BE-127 dorzuca tylko własny SELECT osieroconych (`AttachmentsRow.contactId` może być null).
- `contactIdsBlocked` = kontakty z ≥1 wiadomością NIEusuniętą: porażka S3, przerwana faza S3 (bezpiecznik po 3 porażkach z rzędu) ORAZ wiersz niezwrócony przez `DELETE…RETURNING`.
- Allow-lista `email-attachments/{tenantId}/` + odrzut segmentów `.`/`..` i znaków sterujących (`EmailAttachmentKeys`, wspólne źródło kluczy dla `store`/`storePending`); obcy klucz → `s3Rejected`, wiersz i tak usuwany.
- `EmailAttachmentException` wyniesiony do publicznej klasy top-level (kontrakt `delete` widoczny spoza pakietu); `delete` łapie `SdkException` (S3Exception + SdkClientException).

**Why (nieoczywiste):**
- **Pod rolą bez BYPASSRLS `DELETE` z `email_message` usuwa 0 wierszy BEZ błędu** (polityki wiadomości = tylko `FOR SELECT`, V012; udowodnione testem `EmailMessagePurgeRlsIntegrationTest`). Bez sprawdzania `RETURNING` purge zgłosiłby sukces, usunął obiekty S3 i (w BE-126) kontakt, zostawiając PII. Ta sama pułapka dotyczy `contact` (DB-064/BE-138/BE-139).
- Ten sam klucz S3 w >1 wiadomości jest usuwany raz na wywołanie; skasowanie współdzielonego klucza psuje załącznik wiadomości spoza partii (przyjęte w tickecie, punkt (e)).
- Prefiks `{messageId}/` (Should) NIE wdrożony: 1 `ListObjectsV2` na każdą wiadomość INBOUND (także bez załączników) + szeroka kasacja po prefiksie; sierota istnieje tylko gdy padł UPDATE `attachments` po uploadzie — to sprząta sweep „niewskazywanych obiektów" z BE-131.

**Po code review (2026-09-21, poprawki BE125-03/07/09/10/11 — kontrakt dla BE-127/126):**
- `PurgedMessages#contactIdsBlocked` jest zbiorem odpornym na `contains(null)` (`unmodifiableSet(HashSet)`, NIE `Set.of/copyOf` — te rzucają NPE); element `null` na wejściu nadal odrzucany. Wolno pisać `blocked.contains(row.contactId())` dla osieroconych.
- `EmailMessageRepository#toAttachmentsRow(Object[])` (package-private) mapuje `contact_id = NULL` → `contactId = null`; BE-127 ma użyć tego samego mapowania, nie własnego `toUuid`.
- `extractS3Keys` loguje jedno zbiorcze WARN/wiadomość (`staryFormatS3Url`/`nietekstowyS3Key`/`brakS3Key`), bez wartości pól; nadal BEZ licznika w `PurgedMessages` (osobna decyzja, poza BE-125).
- `encodeFilename`: nazwa `.`/`..` → `attachment` (URLEncoder nie koduje kropek); `forLog` czyści też U+2028/2029 i znaki bidi.

**How to apply:** BE-126: `email.purgeByContactIds` → `social.purgeByContactIds` → `deleteContacts` tylko dla `ids − contactIdsBlocked`; niezmiennik postępu = faktycznie usunięte wiersze `contact` > 0 (RLS-zero!). Powiązane: [[project-epic30-be124-message-retention-adr]], [[feedback-recording-delete-from-s3-swallows-errors]], [[feedback-jpa-real-db-integration-test-harness]].
