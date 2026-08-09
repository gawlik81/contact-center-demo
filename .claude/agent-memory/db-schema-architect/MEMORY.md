# Memory Index – DB Schema Architect

## Project Memories

- [contact_center_project.md](project_contact_center.md) – Stack, decyzje architektoniczne, lokalizacja migracji Flyway, stan po V087 (partycjonowanie contact_event/V085, contact_transcription/V086, contact_ai_summary/V087 — cała trójka EPIC-29 ukończona, blokuje DB-052/V088 rotację partycji); wzorzec online-swap: temp-suffix dla PK/indeksów vs finalna nazwa od razu dla CHECK/trigger/policy; wybór kolumny partycjonującej gdy tabela ma 2 kolumny czasowe (zdarzenie biznesowe > zapis do bazy, V087 generated_at vs created_at); styl nazwy PK po swapie to decyzja stylistyczna (auto `..._pkey` w V086 vs jawne `pk_...` w V085/V087), metoda weryfikacji Flyway z hosta przez bridge IP, brak infrastruktury Testcontainers dla testów migracji
- [feedback_pk_naming.md](feedback_pk_naming.md) – Konwencja PK mieszana: {tabela}_id (stare tabele) vs id (od V069+) — zawsze weryfikuj przez psql przed FK
- [feedback_rls_testing.md](feedback_rls_testing.md) – Test izolacji RLS pod SET ROLE app_user, nigdy pod ccapp (ccapp ma BYPASSRLS); + partycje potomne mają relrowsecurity=f, testuj RLS zawsze przez tabelę nadrzędną
- [project_db_context.md](project_db_context.md) – Kluczowe fakty o schemacie Contact Center: PK tenant(tenant_id), RLS, Flyway, tabele EPIC-27
