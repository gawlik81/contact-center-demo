---
name: BE-027 Contact API
description: Implementacja Contact CRUD – historia kontaktów, paginacja z filtrami, uprawnienia AGENT vs SUPERVISOR
type: project
---

Contact API (BE-027) zaimplementowane. Schemat tabeli `contact` pochodzi z V007 i różni się od opisu w zadaniu:
- kolumny: `remote_address`, `queued_at`, `assigned_at`, `duration_seconds`, `channel_metadata` (JSONB), `recording_url`
- brak kolumn: `notes`, `metadata`, `wait_time_seconds`, `handle_time_seconds`, `recording_id`, `external_id`
- ENUMy: `contact_channel` (PHONE/EMAIL/SOCIAL_FACEBOOK/SOCIAL_INSTAGRAM/SOCIAL_WHATSAPP), `contact_status` (QUEUED/ACTIVE/ON_HOLD/COMPLETED/ABANDONED), `contact_direction` (INBOUND/OUTBOUND)
- PK złożony `(contact_id, started_at)` – tabela partycjonowana RANGE po `started_at`

Wzorzec implementacji (tabel partycjonowanych):
- `ContactId.java` – `@IdClass` z `(UUID contactId, Instant startedAt)`
- `Contact.java` – `@IdClass(ContactId.class)`, dwa pola `@Id`, JSONB przez `@JdbcTypeCode(SqlTypes.JSON)`
- `ContactRepository` – rozszerza istniejący plik (zachowano metody dla nagrań BE-010), dodano: `findById`, `findContacts`, `countContacts`, `findByCustomerId`, `countByCustomerId`, `insert` (natywny SQL), `update` (natywny SQL)
- Trigger `fn_contact_on_update` w DB automatycznie oblicza `duration_seconds` przy ustawieniu `ended_at` i ustawia `updated_at`

Logika uprawnień (w ContactService, nie kontrolerze):
- `isAgent=true` → `agentId` w filtrze wymuszony na `userId`, update/disposition tylko własnych kontaktów
- `isAgent=false` (SUPERVISOR/ADMIN) → pełen CRUD

**Why:** Tabela partycjonowana + istniejący ContactRepository (operacje na nagraniach) → rozszerzono zamiast nadpisania.

**How to apply:** Przy kolejnych zadaniach dotyczących tabeli contact: sprawdź ContactRepository przed modyfikacją – zawiera już metody BE-010 (recording) i BE-027 (CRUD).
