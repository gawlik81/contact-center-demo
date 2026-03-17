---
name: Mockito @Nested i @BeforeEach – problem z inicjalizacją
description: W testach JUnit 5 z @Nested, @BeforeEach zewnętrznej klasy może nie inicjalizować pól używanych w nested class jeśli instancja jest tworzona przez Surefire – używaj @MockitoSettings(strictness = LENIENT) i przenoś setUp do nested class lub unikaj testowania przez refleksję records w nested.
type: feedback
---

Gdy `@BeforeEach` w klasie zewnętrznej inicjalizuje instancje ręcznie (np. `new AuditAspect(mock, objectMapper)`), testy w `@Nested` mogą dostawać niezainicjalizowane `null` dla tych pól gdy Surefire uruchamia nested class jako osobny test run.

**Why:** JUnit 5 + Surefire tworzy osobne instancje dla klasy zewnętrznej i nested – `@BeforeEach` zewnętrznej nie zawsze jest wywołany dla nested instance.

**How to apply:**
- Używaj `@MockitoSettings(strictness = Strictness.LENIENT)` żeby unikać `UnnecessaryStubbingException`.
- Inicjalizację ręczną (nie przez `@InjectMocks`) przenieś do `@BeforeEach` wewnątrz każdej `@Nested` lub używaj `@TestInstance(Lifecycle.PER_CLASS)`.
- Unikaj testowania przez refleksję Java records (metoda `id()`) gdy testy są w `@Nested` – zamiast tego przekaż UUID bezpośrednio w argach.
