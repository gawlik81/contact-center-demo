---
name: contact_enum_cast_after_v025
description: ContactRepository używa CAST(:x AS VARCHAR), nie CAST(:x AS contact_channel/contact_status/contact_direction), bo V025 usuwa te typy
type: feedback
---

Po migracji V025 typy ENUM `contact_channel`, `contact_direction`, `contact_status` zostały usunięte (DROP TYPE).
`ContactRepository` natywne SQL muszą używać `CAST(:channel AS VARCHAR)`, `CAST(:direction AS VARCHAR)`, `CAST(:status AS VARCHAR)` zamiast starych nazw typów.

**Why:** Jeśli INSERT/UPDATE używał `CAST(:channel AS contact_channel)` a typ już nie istnieje po V025, PostgreSQL rzuca `type "contact_channel" does not exist`. W `MockTelephonyAdapter.persistMockContact()` ten błąd był łapany przez `catch (Exception e)`, zwracał `null` jako contactId – frontend dostawał `mock-1` zamiast UUID w WebSocket evencie, co powodowało 422 przy `PATCH /api/contacts/mock-1/disposition`.

**How to apply:** W każdym natywnym SQL na tabeli `contact` dotyczącym kolumn channel/direction/status używaj `VARCHAR` jako cel CAST, nigdy dawnych nazw ENUM typów. Dotyczy INSERT, UPDATE, WHERE filters w appendFilterConditions i updateContactStatusOnTelephonyEvent.
