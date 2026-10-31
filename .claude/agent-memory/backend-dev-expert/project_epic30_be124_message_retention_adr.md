---
name: project-epic30-be124-message-retention-adr
description: EPIC-30 BE-124 (ADR D1 retencja treści wiadomości) — stan decyzji i ustalenia inwentaryzacji S3/PII, które zmieniają założenia BE-125/126/127/131
metadata:
  type: project
---

BE-124 (analiza/ADR, 2026-09-20) zapisane w `TASKS-BACKEND.md` (notatka ticketu, 11 sekcji) i `DESIGN-message-retention-and-partitioning.md` §3 D1, §2 U16–U19, §7 R6–R7. Zero zmian w kodzie.

**Stan D1:** przyjęte do realizacji 2026-09-20 na podstawie polecenia realizacji po przedstawieniu założenia A (DELETE wiadomości + S3 razem z purge kontaktu, kategoria `CONTACT_INTERACTIONS`). **Właściciel nie potwierdził D1 wprost** — nigdy nie pisać „zatwierdzone/potwierdzone" w dokumentach; A to założenie robocze. Pytania do PO są zapisane w notatce jako „do przekazania" (agent nie ma kanału do PO).

**Why:** zlecający wymagał uczciwego zapisu stanu decyzji; usunięcie jest nieodwracalne (bucket `contact-center-recordings` niewersjonowany, bez lifecycle), więc rozróżnienie „założenie" vs „zatwierdzone" ma skutki dla wdrożenia (auto-purge, przycisk „Usuń teraz").

**Ustalenia nieoczywiste (weryfikowane w kodzie/bazie/MinIO), ważne dla implementacji BE-125…131:**
- **`email-attachments/{tenantId}/pending/…` NIE jest tymczasowe:** klucz z uploadu agenta trafia bez przeniesienia do `attachments[*].s3_key` wysłanej wiadomości OUTBOUND (`EmailSendServiceImpl#buildAttachmentsJson`); live 8 z 9 obiektów `pending/` jest wskazywanych. TTL/lifecycle po prefiksie skasowałby załączniki wysłanych maili — BE-131 skorygowany (kandydat = niewskazywany albo najpierw „promocja" do `{messageId}/`).
- **EML e-maili leży w `contact.recording_url`** (`{tenantId}/{yyyy}/{MM}/{contactId}.eml`, pełna kopia treści + załączniki) → podlega RECORDINGS, nie CONTACT_INTERACTIONS; purge kontaktu, który nie sprząta `recording_url`, osierocia obiekt. `RecordingRetentionJob` przetwarza jedną paczkę 100 na tenanta na dobę (bez pętli).
- **Klucze `s3_key` wiadomości OUTBOUND pochodzą od klienta** (`EmailReplyRequest.PendingAttachment#s3Key`, brak walidacji prefiksu w wysyłce) → purge musi mieć allow-list prefiksu `email-attachments/{tenantId}/`.
- Kolejność purge po BE-126: S3 → wiersze wiadomości → wiersz kontaktu; niezmiennik pętli = „postęp = faktycznie usunięte kontakty > 0"; kontakty usuwać tylko spoza `contactIdsBlocked`. Dzisiejsze `DELETE … RETURNING` przed sprzątaniem dzieci byłoby po BE-126 błędne.
- Social po PR #44: FB/IG `sent_at` = `Instant.now()` webhooka (tylko WhatsApp bierze czas platformy) → unikalność `(tenant_id, external_message_id, sent_at)` nie dedupikuje redelivery FB/IG; `attachments` zawsze `[]`; domena social bez S3; `saveOutboundMessage`/`loadSendContext` mają `@Transactional` przez self-invocation (nieskuteczne).
- Testy: Testcontainers ma tylko Postgres/RabbitMQ, **brak MinIO/LocalStack** — S3 testować mockiem `S3Client` (wzorzec `RecordingServiceTest`).
- Demo 2026-09-20 (dane szybko się starzeją): 23 sieroty `email_message` (15 z `body_html`), 0 kandydatów do purge przy polityce KMN 6 mies. (pierwsza zakwalifikuje się ≈ 2026-10-26); 75 wskaźników `recording_url` = 75 obiektów S3.

**How to apply:** przy BE-125/126/127/131/129/132 najpierw przeczytaj sekcje 3–6 notatki BE-124; nie zakładaj, że `pending/` = śmieci ani że `RecordingService#deleteFromS3` zgłasza błędy (zob. [[feedback-recording-delete-from-s3-swallows-errors]]). Kontekst silnika: [[project_epic29_be113_retention_purge_service]], załączniki: [[project-email-attachments]].
