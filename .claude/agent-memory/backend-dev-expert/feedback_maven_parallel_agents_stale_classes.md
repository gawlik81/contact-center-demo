---
name: feedback-maven-parallel-agents-stale-classes
description: Równoległy build innego agenta w tym samym drzewie potrafi skompilować źródła w trakcie Twoich edycji — Maven uznaje klasy za aktualne i Twój nowy test się nie uruchamia; lekarstwo `touch`
metadata:
  type: feedback
---

Gdy dwóch agentów buduje to samo drzewo (`backend/app/target`), build drugiego może skompilować plik w połowie Twojej edycji; Twoja edycja ma mtime STARSZY niż wynikowa `.class`, więc `maven-compiler-plugin` przy Twoim przebiegu uznaje „nothing to compile" i test się nie uruchamia — bez błędu, `mvn` kończy się kodem 0.

**Objaw:** nowa klasa `@Nested`/nowy test nie ma raportu w `target/surefire-reports` (i `.class` w `target/test-classes`), a reszta klasy przechodzi na zielono.
**Why:** złapane przy BE-125 (poprawki po CR): `EmailMessagePurgeIntegrationTest$OrphanRowMapping` „przeszedł" bez uruchomienia.
**How to apply:** po dopisaniu testów sprawdź, że każdy nowy test ma raport z aktualnym mtime; przy wątpliwości `touch` edytowanych źródeł przed `mvn`. Sprawdzaj `Tests run` per klasa z raportów, nie sam kod wyjścia. Kontrola mutacyjna w tym samym drzewie: jeden skrypt pod `flock` z `trap` przywracającym oryginały (kopie w scratchpadzie, `cmp` po przywróceniu), żeby drugi agent nie zbudował zmutowanego kodu.
