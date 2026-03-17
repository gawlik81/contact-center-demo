# Agent Memory – Backend Dev Expert

## Projekt
- [project_contact_center.md](project_contact_center.md) – Stack, struktura Maven, konwencje, klasy konfiguracyjne, profile Spring Boot, Docker Compose

## Znane pułapki
- [feedback_hibernate6_null_param_bytea.md](feedback_hibernate6_null_param_bytea.md) – Hibernate 6: JPQL z `:param IS NULL` + LOWER() na tym samym parametrze String → PostgreSQL `lower(bytea) does not exist`; fix: natywny SQL z `CAST(:param AS TEXT)`
