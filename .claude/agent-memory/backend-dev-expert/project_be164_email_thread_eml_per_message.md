---
name: project-be164-email-thread-eml-per-message
description: BE-164 (fix/email-thread-preview-eml) - preview calej rozmowy e-mail + EML per wiadomosc (email_message.eml_s3_key, V145/V146), sprzatanie S3, pulapka ResponseStatusException->500
metadata:
  type: project
---

Decyzje produktowe: podglad kontaktu EMAIL = cala rozmowa (newest 100, ASC); kazda wiadomosc ma wlasny EML; `contact.recording_url` nadal = EML pierwszej wiadomosci.

- Klucz follow-upu: `{tenant}/{yyyy}/{MM}/{contactId}/{messageId}.eml` (`EmailEmlService#generateAndUploadForMessage`); pierwsza wiadomosc `{contactId}.eml` + ten sam klucz w `eml_s3_key` (Creator).
- `EmailMessage.emlS3Key` jest `insertable/updatable=false`; zapis TYLKO natywnym `EmailMessageRepository#updateEmlS3Key` (pelny klucz message_id+message_at+tenant). `UPDATE_SQL` ogolny nie rusza kolumny.
- Joiner generuje EML synchronicznie po commicie joina (poller nie jest @Transactional; attach ma wlasna tx). NIE rejestrowac afterCommit + UPDATE: w afterCommit tx jest juz zatwierdzona (UPDATE nigdy sie nie commituje), a REQUIRES_NEW na wierszu zablokowanym przez zewnetrzna tx = deadlock.
- Legacy fallback (eml_s3_key NULL + recording_url konczy sie .eml) tylko dla NAJWCZESNIEJSZEJ wiadomosci; przy truncated (lista = najnowsze) fallback nie dotyczy nikogo.
- Sprzatanie S3: `purgeRows` (kontakty + sieroty) kasuje tez `eml_s3_key` (allow-lista `isRecordingKeyOwnedByTenant`, nie `isOwnedByTenant`); `RecordingRetentionJob` dla `.eml` woła `EmailMessageService#deleteEmlObjectsByContact`; GDPR = V146 (anonymize_customer + export_customer_data zebrane z V098/V095 skryptem). Partycje email_message DROP tylko puste (BE-145) - bez wycieku.
- PULAPKA: `GlobalExceptionHandler` catch-all `Exception.class` przechwytywal `ResponseStatusException` => 404/400/503 z serwisow konczyly sie 500; dodany handler `ResponseStatusException` (dotyczy tez /recording).
- Agent ownership dla email-preview nieszkodliwe: `getContact` (modal) i tak odrzuca cudzy kontakt 409 wczesniej.
