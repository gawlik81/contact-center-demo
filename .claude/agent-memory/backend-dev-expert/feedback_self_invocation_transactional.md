---
name: Self-invocation @Transactional – self-injection przez @Lazy
description: Wzorzec naprawy self-invocation omijającego Spring AOP proxy dla @Transactional
type: feedback
---

Gdy metoda `@Transactional` jest wywoływana przez `this.metoda(...)` z tej samej klasy, Spring AOP proxy jest omijany i transakcja nigdy nie startuje.

**Fix**: Dodaj pole self z `@Autowired @Lazy` (pole niefinalne – poza `@RequiredArgsConstructor`):
```java
@Autowired
@Lazy
private NazwaSerwisu self;
```
Następnie wywołuj `self.metoda(...)` zamiast `this.metoda(...)`.

**Why:** Spring AOP działa przez proxy – wywołanie przez `this` trafia bezpośrednio do metody, pomijając proxy z interceptorami transakcji.

**How to apply:** Wszędzie gdzie serwis wywołuje własną metodę `@Transactional` z metody bez tej adnotacji lub z innego wątku (RabbitMQ listener). Zastosowano w `ProgressiveDialerService.onAgentStatusChanged` → `self.initiateDialForAgent`.
