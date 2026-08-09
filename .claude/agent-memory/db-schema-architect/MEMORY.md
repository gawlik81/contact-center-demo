# Memory Index – DB Schema Architect

## Project Memories

- [contact_center_project.md](project_contact_center.md) – Stack, decyzje architektoniczne, lokalizacja migracji Flyway, stan po V084 (retention_purge_log, EPIC-29, PK surogat purge_id, CHECK data_category dodany mimo braku w tickecie), metoda weryfikacji Flyway z hosta przez bridge IP, brak infrastruktury Testcontainers dla testów migracji
- [feedback_pk_naming.md](feedback_pk_naming.md) – Konwencja PK mieszana: {tabela}_id (stare tabele) vs id (od V069+) — zawsze weryfikuj przez psql przed FK
- [feedback_rls_testing.md](feedback_rls_testing.md) – Test izolacji RLS pod SET ROLE app_user, nigdy pod ccapp (ccapp ma BYPASSRLS); + partycje potomne mają relrowsecurity=f, testuj RLS zawsze przez tabelę nadrzędną
- [project_db_context.md](project_db_context.md) – Kluczowe fakty o schemacie Contact Center: PK tenant(tenant_id), RLS, Flyway, tabele EPIC-27
