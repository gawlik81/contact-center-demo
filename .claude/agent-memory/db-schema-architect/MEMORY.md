# Memory Index – DB Schema Architect

## Project Memories

- [contact_center_project.md](project_contact_center.md) – Stack, decyzje architektoniczne, lokalizacja migracji Flyway, stan po V089 (DB-053 ukończony: 4 indeksy tenant+czas pod RetentionPurgeService/BE-113, zamyka warstwę indeksową EPIC-29); wzorzec DETACH/ATTACH PARTITION DEFAULT do backfillu partycji z niepustym default; wzorzec online-swap: temp-suffix dla PK/indeksów vs finalna nazwa od razu dla CHECK/trigger/policy; wybór kolumny partycjonującej gdy tabela ma 2 kolumny czasowe; metoda weryfikacji EXPLAIN na małych dev-partycjach (symuluj dziesiątki tenantów, nie tylko 2 realne); RunFlyway.java jako jedyna działająca metoda ręcznego stosowania migracji; brak infrastruktury Testcontainers dla testów migracji
- [feedback_pk_naming.md](feedback_pk_naming.md) – Konwencja PK mieszana: {tabela}_id (stare tabele) vs id (od V069+) — zawsze weryfikuj przez psql przed FK
- [feedback_rls_testing.md](feedback_rls_testing.md) – Test izolacji RLS pod SET ROLE app_user, nigdy pod ccapp (ccapp ma BYPASSRLS); + partycje potomne mają relrowsecurity=f, testuj RLS zawsze przez tabelę nadrzędną
- [feedback_flyway_manual_timezone.md](feedback_flyway_manual_timezone.md) – KRYTYCZNE: ręczne stosowanie migracji (metoda RunFlyway.java) wymaga `-Duser.timezone=UTC` na JVM, inaczej ciche przesunięcie TIMESTAMPTZ (host ma strefę Europe/Warsaw, baza działa w UTC); flyway-maven-plugin CLI ad-hoc NIE działa w tym repo (brak driverów na classpath pluginu)
- [project_db_context.md](project_db_context.md) – Kluczowe fakty o schemacie Contact Center: PK tenant(tenant_id), RLS, Flyway, tabele EPIC-27
