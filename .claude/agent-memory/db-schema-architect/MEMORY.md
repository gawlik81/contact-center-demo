# Memory Index – DB Schema Architect

## Project Memories

- [contact_center_project.md](project_contact_center.md) – Stack, decyzje architektoniczne, lokalizacja migracji Flyway
- [feedback_pk_naming.md](feedback_pk_naming.md) – Konwencja PK mieszana: {tabela}_id (stare tabele) vs id (od V069+) — zawsze weryfikuj przez psql przed FK
- [feedback_rls_testing.md](feedback_rls_testing.md) – Test izolacji RLS pod SET ROLE app_user, nigdy pod ccapp (ccapp ma BYPASSRLS); + partycje potomne mają relrowsecurity=f, testuj RLS zawsze przez tabelę nadrzędną
