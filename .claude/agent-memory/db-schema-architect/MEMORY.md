# Memory Index – DB Schema Architect

## Project Memories

- [contact_center_project.md](project_contact_center.md) – Stack, decyzje architektoniczne, lokalizacja migracji Flyway, stan po V088 (DB-052 FUNDAMENT ukończony: naprawa rotacji partycji contact/audit_log + rozszerzenie na contact_event/contact_transcription/contact_ai_summary — cały EPIC-29 rotation mechanism gotowy); wzorzec DETACH/ATTACH PARTITION DEFAULT do backfillu partycji z niepustym default; wzorzec online-swap: temp-suffix dla PK/indeksów vs finalna nazwa od razu dla CHECK/trigger/policy; wybór kolumny partycjonującej gdy tabela ma 2 kolumny czasowe; metoda weryfikacji Flyway z hosta przez bridge IP, brak infrastruktury Testcontainers dla testów migracji
- [feedback_pk_naming.md](feedback_pk_naming.md) – Konwencja PK mieszana: {tabela}_id (stare tabele) vs id (od V069+) — zawsze weryfikuj przez psql przed FK
- [feedback_rls_testing.md](feedback_rls_testing.md) – Test izolacji RLS pod SET ROLE app_user, nigdy pod ccapp (ccapp ma BYPASSRLS); + partycje potomne mają relrowsecurity=f, testuj RLS zawsze przez tabelę nadrzędną
- [feedback_flyway_manual_timezone.md](feedback_flyway_manual_timezone.md) – KRYTYCZNE: ręczny `flyway-maven-plugin:migrate` z hosta wymaga `MAVEN_OPTS="-Duser.timezone=UTC"`, inaczej ciche przesunięcie TIMESTAMPTZ (host ma strefę Europe/Warsaw, baza działa w UTC)
- [project_db_context.md](project_db_context.md) – Kluczowe fakty o schemacie Contact Center: PK tenant(tenant_id), RLS, Flyway, tabele EPIC-27
