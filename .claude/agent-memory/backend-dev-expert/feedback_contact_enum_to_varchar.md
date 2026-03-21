---
name: Contact ENUM columns not converted by V019
description: contact.channel/direction/status were PostgreSQL ENUMs not touched by V019; fixed in V025
type: feedback
---

Tabela `contact` zawiera trzy kolumny zdefiniowane jako PostgreSQL ENUM w V007: `channel` (`contact_channel`), `direction` (`contact_direction`), `status` (`contact_status`). Migracja V019 konwertowała tylko `tenant.status`, `app_user.role` i `app_user.status` — tabela `contact` pozostała z ENUM-ami.

**Why:** Hibernate 6 binduje wszystkie Java `String` przez JDBC type VARCHAR (code 12). PostgreSQL odmawia przypisania VARCHAR do kolumny ENUM bez jawnego CAST, stąd błąd `column "channel" is of type contact_channel but expression is of type character varying`.

**How to apply:** Gdy nowa tabela ma kolumny ENUM w PostgreSQL i jest mapowana przez Hibernate jako `String` + `@Column`, zawsze dodaj migrację konwertującą ENUM → `VARCHAR(N) + CHECK CONSTRAINT`, tak samo jak V019/V025. Pamiętaj o:
- DROP widoków zależnych przed ALTER (CASCADE) — przeszukaj WSZYSTKIE migracje V001–Vprev pod kątem `CREATE VIEW` / `CREATE MATERIALIZED VIEW` z `FROM contact` lub `JOIN contact`, błąd `cannot alter type of a column used by a view or rule` pojawia się dla każdego pominiętego widoku osobno
- W projekcie contact-center: lista widoków zależnych od `contact.channel/direction/status` to: `mv_agent_daily_stats` (V011), `v_active_contacts` (V016), `v_queue_realtime_stats` (V016/V019), `v_customer_timeline` (V017)
- DROP indeksów partial z WHERE na ENUM przed ALTER, odtworzeniu po
- ALTER propaguje na partycje automatycznie
- DROP TYPE na końcu (po ALTER WSZYSTKICH kolumn korzystających z tego typu — nie tylko w tabeli docelowej, ale w KAŻDEJ tabeli w schemacie). Przykład: `contact_direction` był używany też w `email_message.direction` i `social_message.direction` (V010), co powoduje błąd `cannot drop type contact_direction because other objects depend on it` jeśli pominięte
