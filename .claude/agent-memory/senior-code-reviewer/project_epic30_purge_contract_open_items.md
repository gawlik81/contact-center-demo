---
name: project-epic30-purge-contract-open-items
description: EPIC-30 message purge (BE-125 reviewed 2026-09-21, DB-079 approved) — open contract gaps to re-check when BE-126/BE-127 land, plus the unticketed send-path s3Key hole
metadata:
  type: project
---

Reviewed 2026-09-21 (entries in CR-BACKEND.md "BE-125", CR-DATABASE.md "DB-079"): BE-125 = 4/5 approve-with-fixes, DB-079 = 5/5 approve. No blockers. The S3-before-row order, `DELETE ... RETURNING` confirmation, tenant allow-list (`EmailAttachmentKeys#isOwnedByTenant`) and real-DB tests are sound — don't re-litigate them.

**Open items that BE-126 / BE-127 must not repeat or leave open (re-check at their review):**
- SELECT→S3→DELETE window (BE125-02): a reply inherits the old contact (`EmailSendServiceImpl:102`), lands after the SELECT, survives, and BE-126 then deletes the contact -> dangling message with PII, no FK on `email_message.contact_id`. Fix expected in BE-126: second `purgeByContactIds` after `deleteContacts`, or BE-127 "dangling" (`NOT EXISTS`) made mandatory.
- Head-of-line blocking (H-1): `contactIdsBlocked` cannot tell transient from permanent S3 failure; with `ORDER BY started_at, contact_id` blocked contacts pin the head of every batch. Loop must continue on `ids.size() == batch`, not on deleted-contact count.
- Latent traps for BE-127 (orphans, `contactId == null`): `EmailMessageRepository#toUuid(null)` throws IAE; `PurgedMessages.contactIdsBlocked` is `Set.copyOf` so `.contains(null)` NPEs.
- Social returns only `int` (no "blocked"/RLS-zero signal). Harmless today (contact, email_message, social_message all lack a DELETE policy; app runs as superuser `ccapp` BYPASSRLS) but becomes real if `contact` gets a DELETE policy (DB-074) before `social_message` (DB-064).
- Public `EmailAttachmentStorageService#delete(String)` has no tenant guard and the allow-list is package-private — BE-126 pt (b) (`recording_url`), BE-129, BE-131 callers must enforce a prefix themselves unless the signature becomes `delete(tenantId, key)`.

**Unticketed security hole (DESIGN U18, verified in code):** `EmailSendServiceImpl#buildAttachmentPart` (:326) downloads a client-supplied `s3Key` with no `email-attachments/{tenantId}/` check (cross-tenant attach); `EmailAttachmentController#downloadAttachment` (:130-131) checks the prefix but not `..`. DESIGN says "separate ticket" but none exists in TASKS-BACKEND.md as of 2026-09-21 — check whether it was created when reviewing anything under `domain/email` send/download.

**Method that worked:** verify claims read-only against the live stack: `docker exec -i -e PGOPTIONS='-c default_transaction_read_only=on' cc-postgres psql -U ccapp -d contact_center` (`pg_policies`, `pg_trigger`, `\d table`, `flyway_schema_history`, shape of JSONB keys). Migration-number claims: `git ls-tree --name-only <ref> backend/src/main/resources/db/migration/` for every ref.
