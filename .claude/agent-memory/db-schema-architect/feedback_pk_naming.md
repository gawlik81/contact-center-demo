---
name: feedback_pk_naming
description: Konwencja PK w tym projekcie to {tabela}_id, nie id — weryfikuj przez psql przed pisaniem FK
metadata:
  type: feedback
---

Klucze główne w tym projekcie NIE używają nazwy `id`. Konwencja: `{tabela}_id`.

Przykłady potwierdzone w schemacie:
- `tenant.tenant_id`
- `queue.queue_id`
- `campaign.campaign_id`

**Why:** DDL dla V069 zawierał `REFERENCES tenant(id)` i `REFERENCES queue(id)` — oba błędne. Migracja wysypała się z `ERROR: column "id" referenced in foreign key constraint does not exist`. Kolumny nazywają się `tenant_id` i `queue_id`.

**How to apply:** Przed napisaniem jakiegokolwiek REFERENCES sprawdź `\d <tabela>` w psql (user=ccapp, db=contact_center) lub przeszukaj istniejące migracje w `/home/pawelm/contact-center/backend/src/main/resources/db/migration/` po wzorcu `REFERENCES <tabela>`. Nigdy nie zakładaj że PK to `id`.

Powiązane: [[contact_center_project]]
