---
name: Testy repozytoriów – styl i podejście
description: Jak pisać testy repozytoriów w projekcie (brak H2, Mockito EntityManager)
type: feedback
---

Projekt nie ma H2 w zależnościach testowych – testy repozytoriów używają mockowanego EntityManager (Mockito), tak jak CrossTenantAccessTest.

**Why:** H2 nie jest w `pom.xml`; są Testcontainers ale tylko do testów IT (nie unit). Testy unit repozytoriów mockują EntityManager przez `ReflectionTestUtils.setField(repo, "em", entityManager)`.

**How to apply:** Przy pisaniu testów repozytoriów zawsze używaj Mockito + ReflectionTestUtils. Przy stubowaniu wielu różnych SQL-i w jednej metodzie – używaj `thenAnswer` na `anyString()` zamiast wielu `when(contains(...))` żeby uniknąć NPE.

Gdy używasz wspólnych stubbingów w `@BeforeEach` (np. defaultowe zachowanie mockQuery), ZAWSZE dodaj `@MockitoSettings(strictness = Strictness.LENIENT)` na klasie testu – inaczej Mockito strict mode zgłosi `UnnecessaryStubbingException` dla testów które nie korzystają z wszystkich stubbingów (patrz QueueAssignmentRepositoryTest).

Uwaga na generics: `mock.getResultList().thenReturn(List.of(Object[]))` – Java nie może wywnioskować `List<Object[]>` z `List.of(array)`. Zamiast tego użyj: `ArrayList returnedRows = new ArrayList(); returnedRows.add(row);` z `@SuppressWarnings("unchecked")`.
