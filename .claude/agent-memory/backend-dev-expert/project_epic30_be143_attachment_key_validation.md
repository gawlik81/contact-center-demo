---
name: project-epic30-be143-attachment-key-validation
description: EPIC-30 BE-143 — EmailAttachmentKeys upubliczniona (decyzja API pkt c dla BE-126/BE-129/BE-131), EmailAttachmentAccessDeniedException → 403, walidacja jako krok 0 w EmailSendServiceImpl
metadata:
  type: project
---

BE-143 (2026-09-22) zamknął lukę BE125-01: `s3Key` z `EmailReplyRequest.PendingAttachment` (ciało
żądania klienta) nie był walidowany przed wysyłką — agent tenanta A mógł dołączyć do maila
dowolny znany klucz S3 (nagranie/EML/załącznik tenanta B).

**Decyzja API (pkt c ticketu) — wybrana opcja (i), BEZ nowej klasy `S3KeyPolicy`:**
`domain.email.EmailAttachmentKeys` (była package-private) jest teraz `public final class`;
`isOwnedByTenant(UUID, String)` i `forLog(String)` upublicznione (bez zmiany logiki — testy
`EmailAttachmentKeysTest` z BE-125 przeszły bez modyfikacji). Nowa metoda
`isRecordingKeyOwnedByTenant(UUID tenantId, String s3Key)` — allow-lista dla schematu
`{tenantId}/…` (BEZ korzenia `email-attachments/`, używanego przez `RecordingServiceImpl#buildS3Key`
i `EmailEmlService`) — przygotowana dla BE-126 (pkt b, purge `contact.recording_url`), BE-129
(`GdprServiceImpl`), BE-131 (sweep bucketu). **BE-143 samo jej NIE wywołuje** — czysto
przygotowanie API. Obie metody dzielą prywatny `hasCleanPrefixedSuffix(prefix, s3Key)`.

**Kod błędu: HTTP 403** (nie 400) — nowy `EmailAttachmentAccessDeniedException` (`domain.email`,
`RuntimeException`, jeden konstruktor `String message`) zamiast rozszerzenia
`CrossTenantAccessException` (ten ma `resourceId: UUID` w konstruktorze, `s3Key` to `String` —
zmiana sygnatury współdzielonego wyjątku uznana za zbyt inwazyjną). Handler
`GlobalExceptionHandler#handleEmailAttachmentAccessDeniedException`, wzorzec 1:1 z
`handleCrossTenantAccessException` (RFC 7807, komunikat ogólny, szczegóły w WARN).

**Gdzie żyje walidacja — TYLKO w `EmailSendServiceImpl`, nie duplikowana w kontrolerze:**
`validateAttachmentKeys(tenantId, attachments)` to KROK 0 na początku `sendReply`/`sendNew` —
PRZED `findById(originalMessageId)`/`tenantService.findTenantEntity`/deszyfrowaniem hasła SMTP,
nie tylko przed `sendSmtp`. `EmailController#replyToMessage`/`#sendOutboundEmail` przekazują
`attachments` do serwisu bez własnej walidacji (DRY — jedna implementacja starczy, bo oba
endpointy i tak przechodzą przez ten sam serwis). `buildAttachmentPart` (pobieranie z S3 do
wysyłki) NIE zmieniony — po BE-143 dostaje już tylko zweryfikowane klucze, jego istniejący
try/catch zostaje dla realnych błędów S3 (obiekt skasowany między uploadem a wysyłką), nie dla
allow-listy.

**Pobieranie (`EmailAttachmentController#downloadAttachment`):** `s3Key.startsWith(prefix)` →
`EmailAttachmentKeys.isOwnedByTenant(tenantId, s3Key)`; odpowiedź nadal bezpośredni
`ResponseEntity.status(403)` (bez wyjątku) — inny mechanizm niż wysyłka, bo już tak było przed
BE-143 i AC wymagał zachowania „403 jak dotąd".

**Testy (28 nowych, wzorzec kontynuuje istniejące konwencje, żadnego `MockMvc`):**
- `EmailSendServiceTest` (domain.email) — rzeczywisty `EmailSendServiceImpl`, `@Spy` na
  `sendSmtp` (wzorzec już w pliku z BE-015/BE-125) — TU żyje cała logika AC (i)-(iv), osobno dla
  `sendNew` (4 przypadki) i `sendReply` (2, bo walidacja jest wspólna — nie duplikować wszystkich
  4 na obu ścieżkach).
- `EmailControllerTest`/`EmailAttachmentControllerTest` (nowe pliki, `api.email`) — wywołanie
  metody kontrolera bezpośrednio, `TenantContext` mockowany statycznie (`EmailController`) albo
  ustawiany realnie przez `ThreadLocal` (`EmailAttachmentController`, bo test WP-2 „pusty
  kontekst" musi zaobserwować prawdziwy `IllegalStateException` z `TenantContext.getTenantId()`,
  nie zachowanie mocka) — ten sam brak-`MockMvc` co `RetentionControllerTest`/BE-118.
- `GlobalExceptionHandlerTest` — nowy `@Nested` po wzorcu `CrossTenantAccessExceptionTests`.

**FE zweryfikowany jako bezpieczny (ryzyko i z ticketu), BEZ zmian:** `pendingAttachments` w
`email-contact.component.ts` i `adhoc-email-modal.component.ts` zasilane WYŁĄCZNIE odpowiedzią
`POST /api/email/attachments/upload` (`resp.s3Key`) — brak pola formularza pozwalającego wpisać
klucz ręcznie. Obsługa nowego błędu 403 w FE (komunikat dla agenta) NIE zaimplementowana ani
zweryfikowana — poza zakresem BE-143.

**Why:** ustalone w code review BE-125 (BE125-01) — luka istniała od wprowadzenia załączników
(BE-015), niezależna od purge (BE-125/126). DESIGN U18 odkładał ją do osobnego ticketu, który
nie istniał do 2026-09-22.

**How to apply:** BE-126 (pkt b — purge `contact.recording_url`), BE-129 (`GdprServiceImpl`),
BE-131 (sweep bucketu) powinny wołać `EmailAttachmentKeys.isRecordingKeyOwnedByTenant(tenantId, s3Key)`
zamiast pisać własną allow-listę. Sekcja BE-143 w `TASKS-BACKEND.md` NIE mogła dopisać uwagi
kontraktowej do treści tamtych ticketów (ograniczenie zakresu edycji tej iteracji) — trzeba to
zrobić ręcznie przy podejmowaniu tamtych ticketów. Powiązane: [[project-epic30-be125-message-purge]],
[[project-epic30-be124-message-retention-adr]], [[feedback-repository-tests]] (konwencja testów
kontrolerów bez MockMvc).
