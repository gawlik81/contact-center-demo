---
name: fe012-email-attachments
description: Email attachment support added to EmailContactComponent and EmailThreadMessageComponent (upload, download, pending list)
metadata:
  type: project
---

Email attachment support added to agent email panel.

**Why:** Agents need to send and view file attachments in email contacts.

**How to apply:** When extending email functionality, note these patterns and API contracts.

## Interfaces added to EmailService (`email.service.ts`)

- `EmailAttachment` — `{ filename, contentType, sizeBytes, s3Key }` — on received messages
- `PendingAttachment` — same shape with `s3Key` first — for outbound request
- `UploadedAttachmentResponse` — adds `id` field from backend

`EmailMessage.attachments?: EmailAttachment[]` — optional list on incoming messages.
`SendReplyRequest.attachments?: PendingAttachment[]` — sent with reply.

## API endpoints used

- `POST /api/email/attachments/upload` — `multipart/form-data`, field `file`, returns `UploadedAttachmentResponse`
- `GET /api/email/attachments/download?s3Key=...` — returns `{ url: string }` (presigned S3 URL), opened in `window.open(..., '_blank', 'noopener,noreferrer')`

## EmailThreadMessageComponent changes

- Injected `EmailService` and `DestroyRef`
- `downloadAttachment(att: EmailAttachment)` — calls `getAttachmentDownloadUrl`, opens URL
- `formatFileSize(bytes)` — B / KB / MB formatter
- HTML: `@if (message().attachments?.length)` block below iframe, `.thread-message__attachments` section with `.attachment-chip` buttons
- SCSS: `.thread-message__attachments`, `.thread-message__attachments-label`, `.thread-message__attachment-list`, `.attachment-chip` + sub-elements

## EmailContactComponent changes

- Added `@ViewChild('fileInput')` for hidden `<input type="file" multiple>`
- Signals: `pendingAttachments`, `uploading`, `uploadError`
- Methods: `triggerFileInput()`, `onFileSelected(event)` (sequential upload loop), `removeAttachment(s3Key)`, `formatFileSize(bytes)`, `trackByS3Key()`
- `sendReply()` — passes `attachments: pendingAttachments().length > 0 ? ... : undefined`, clears list on success
- HTML: `.email-reply__attachments` div inside `.email-reply__inner`, after editor wrapper, before `.email-reply__actions`
- SCSS: `.email-reply__attachments`, `&__upload-error`, `&__pending-list`, `&__pending-item`, `.pending-item__*`, `.btn--sm` modifier added

## i18n keys added (agent.emailContact section, all 4 languages: pl/en/de/uk)

```
attachFile, attachFileLabel, attachments, pendingAttachments, removeAttachment, uploadError
```

## Conventions confirmed

- Project uses `sr-only` (not `visually-hidden`) for screen-reader-only content — defined in global `styles.scss`
- i18n files live at `public/i18n/{pl,en,de,uk}.json`

See also: [[fe012-email-contact]]
