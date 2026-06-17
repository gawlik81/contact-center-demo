---
name: project-email-attachments
description: Email attachment support – IMAP extraction, S3 storage, agent upload, SMTP send with attachments
metadata:
  type: project
---

Zaimplementowana pełna obsługa załączników email.

**Nowe pliki:**
- `domain/email/EmailAttachmentStorageService.java` – interfejs: store(), storePending(), presignedDownloadUrl(), download()
- `domain/email/EmailAttachmentStorageServiceImpl.java` – implementacja S3Client/S3Presigner (ten sam bucket co nagrania)
- `api/email/EmailAttachmentController.java` – POST /api/email/attachments/upload, GET /api/email/attachments/download
- `api/email/dto/UploadedAttachmentResponse.java` – DTO odpowiedzi upload

**Zmodyfikowane pliki:**
- `EmailMessageResponse.java` – dodano `List<AttachmentMeta> attachments`, parsowanie z JSONB (format: filename/content_type/size_bytes/s3_key)
- `EmailReplyRequest.java` – dodano `List<PendingAttachment> attachments`
- `EmailSendService.java` – sygnatura sendReply() rozszerzona o `List<PendingAttachment> attachments`
- `EmailSendServiceImpl.java` – sendSmtp() z multipart/mixed gdy attachments niepuste; buildAttachmentsJson(); wstrzyknięty ObjectMapper
- `EmailPollingServiceImpl.java` – extractAndStoreAttachments() po save(); collectAttachments() rekurencyjna; tylko Content-Disposition: attachment
- `EmailController.java` – przekazuje request.attachments() do sendReply()
- `application.yml` – dodano spring.servlet.multipart.max-file-size=25MB, max-request-size=30MB

**Schemat kluczy S3:**
- Inbound (IMAP): `email-attachments/{tenantId}/{messageId}/{encodedFilename}`
- Pending (agent): `email-attachments/{tenantId}/pending/{uuid}/{encodedFilename}`

**IDOR protection:** GET /download waliduje że s3Key zaczyna się od `email-attachments/{tenantId}/`

**Why:** Wymaganie produktowe – agenci muszą móc dołączać i odbierać załączniki w kanale email.

**How to apply:** EmailAttachmentStorageService jest benem Spring – wstrzykiwany do EmailPollingServiceImpl i EmailSendServiceImpl przez @RequiredArgsConstructor.
