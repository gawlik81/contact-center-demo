---
name: feedback_pk_naming
description: Konwencja PK w tym projekcie to {tabela}_id, nie id — weryfikuj przez psql przed pisaniem FK
metadata:
  type: feedback
---

Konwencja PK w tym projekcie jest MIESZANA i zależy od WIEKU tabeli — nie zakładaj jednego wzorca dla całej bazy.

- Tabele starsze (do ok. V068): PK = `{tabela}_id`. Potwierdzone: `tenant.tenant_id`, `queue.queue_id`, `campaign.campaign_id`, `app_user.user_id`.
- Tabele nowsze (od V069+: custom_disposition, disposition_set, disposition_set_item, plugin, plugin_version): PK = `id`. Potwierdzone w V071 (`disposition_set.id`) i V074 (`plugin.id`, `plugin_version.id`).

**Why:** DDL dla V069 zawierał `REFERENCES tenant(id)` i `REFERENCES queue(id)` — oba błędne, bo te tabele są starsze i mają PK `tenant_id`/`queue_id`. Migracja wysypała się z `ERROR: column "id" referenced...`. Odwrotny błąd byłby równie łatwy: zakładać `{tabela}_id` dla nowej tabeli, gdy projekt już przeszedł na `id`.

**How to apply:** Przed napisaniem jakiegokolwiek REFERENCES do tabeli ZAWSZE sprawdź `\d <tabela>` w psql (`docker exec cc-postgres psql -U ccapp -d contact_center -c "\d <tabela>"`, hasło w `.env.local-demo` pod `DB_PASSWORD`, NIE `ccapp:ccapp`) lub przeszukaj istniejące migracje w `/home/pawelm/contact-center/backend/src/main/resources/db/migration/` po wzorcu `REFERENCES <tabela>`. Dla FK do tabel starszych niż V069 oczekuj `{tabela}_id`; dla nowych tabel tworzonych w tym samym tickecie możesz swobodnie użyć `id` jako PK (zgodnie z aktualnym wzorcem), ale FK do innych, istniejących tabel zawsze weryfikuj indywidualnie. Nigdy nie zakładaj wzorca bez sprawdzenia.

Powiązane: [[contact_center_project]]
