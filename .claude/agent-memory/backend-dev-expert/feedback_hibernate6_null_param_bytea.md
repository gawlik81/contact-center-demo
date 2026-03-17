---
name: Hibernate 6 – błąd lower(bytea) przy IS NULL + funkcja na tym samym parametrze JPQL
description: W Hibernate 6 (Spring Boot 3.x) użycie tego samego bind parametru String w predykacie IS NULL i w wywołaniu LOWER() w JPQL powoduje błąd PostgreSQL "function lower(bytea) does not exist"
type: feedback
---

W JPQL Hibernate 6 błędnie typuje parametr String jako `bytea` gdy ten sam `:param` pojawia się zarówno w predykacie `:param IS NULL` (brak kontekstu kolumny) jak i w `LOWER(t.column) LIKE LOWER(CONCAT('%', :param, '%'))` w tej samej klauzuli WHERE.

**Why:** Hibernate 6 zmienił mechanizm wiązania parametrów względem Hibernate 5. Przy pierwszym wystąpieniu `:param IS NULL` nie ma kontekstu kolumny, z której Hibernate mógłby wydedukować typ – wysyła go jako Object/bytea. PostgreSQL zgłasza `function lower(bytea) does not exist`.

**How to apply:** Gdy trzeba zbudować opcjonalny filtr JPQL z IS NULL na parametrze String używanym też w funkcji:
1. Przepisać na natywny SQL z `CAST(:param AS TEXT) IS NULL OR LOWER(col) LIKE LOWER('%' || CAST(:param AS TEXT) || '%')`
2. Dla enum-ów przekazywać wartość jako String (`.name()`) i porównywać `col::TEXT = CAST(:param AS TEXT)`
3. Sygnatura repozytorium przyjmuje `String status` zamiast `TenantStatus status` – konwersja przez `.name()` odbywa się w serwisie

Przykład zastosowania: `TenantRepository.findAllByOptionalFilters(String name, String status)`
