---
name: project-be144-minio-test-skip-afterall-pitfall
description: Assumptions.assumeTrue(false,...) w @BeforeAll static start() NIE zapobiega uruchomieniu @AfterAll static stop() — pola zainicjowane PO punkcie awarii zostają null, a JUnit5/Surefire nadal raportuje Errors zamiast czystego Skipped
metadata:
  type: project
---

Kontekst: `EmailAttachmentStorageServiceMinioTest` (BE-125/EPIC-30) — obraz MinIO przestał być anonimowo pobieralny (patrz [[project-minio-image-registry]]), więc `start()` (`@BeforeAll`) owinięto w `try { minio.start(); } catch (RuntimeException e) { Assumptions.assumeTrue(false, "..."); }`, żeby awaria startu kontenera dawała JUnit SKIPPED zamiast psuć CI.

**Zweryfikowane empirycznie (2026-09-30, symulacja przez podmianę `MINIO_IMAGE` na nieistniejący tag):**
- `@AfterAll static void stop()` **JEST wywoływane** przez JUnit 5 nawet gdy `@BeforeAll` przerwał się przez `Assumptions.assumeTrue(false, ...)` (rzuca `org.opentest4j.TestAbortedException`) — to nie jest opcjonalne zachowanie, testowane na JUnit 5 (wersja z tego repo).
- Pola statyczne inicjalizowane PO punkcie awarii w `start()` (tu: `pool`, `jdbc`, `ctx`, `service` — przypisywane dopiero po `minio.start()`) pozostają `null`.
- `stop()` w tym pliku ma `if (s3 != null)`/`if (minio != null)` guardy, ale **`pool.close()` wywoływane jest bez null-guardu** → `NullPointerException` w `@AfterAll`.
- Efekt: Surefire raportuje `Tests run: 1, Failures: 0, Errors: 1, Skipped: 0` (NPE w `stop()` "wygrywa" nad `TestAbortedException` w `start()`, który staje się tylko "Suppressed") → **BUILD FAILURE**, nie skip. Cel (nie psuć CI przy niedostępnym obrazie) NIE jest osiągnięty, dopóki `@AfterAll` nie ma null-guardów na wszystkich polach używanych bez `if (x != null)`.
- `JpaTestContext.close(ctx)` (helper w `com.contactcenter.support`) JEST już null-safe (`if (ctx == null) return;`) — to konkretnie `HikariDataSource pool` w tym pliku testowym nie ma guardu.

**Wyjątek rzucany przez `GenericContainer.start()` przy błędzie pobrania obrazu (testcontainers 1.20.4):** w tym konkretnym scenariuszu (401/no-such-manifest od registry) propaguje się **`org.testcontainers.containers.ContainerFetchException` bezpośrednio** (nie opakowany w `ContainerLaunchException`, mimo że kod źródłowy `GenericContainer.doStart()` sugerowałby taki wrapping przez `catch(Exception e) { throw new ContainerLaunchException(...) }` wokół wywołania `getDockerImageName()`) — empiria > czytanie źródeł na tej wersji. Obie klasy dziedziczą wprost po `RuntimeException` bez wspólnego przodka poza nim, więc bezpieczny catch to `catch (RuntimeException e)`, nie próba wymienienia obu typów.

**How to apply:** przy każdym nowym/istniejącym teście z ręcznym (nie `@Testcontainers`/`@Container`) `static void start()`/`static void stop()`, jeśli `start()` może przerwać się przez `Assumptions`/wyjątek PRZED zainicjowaniem jakiegokolwiek pola używanego w `stop()` — `stop()` MUSI mieć null-guard na KAŻDYM takim polu, nie tylko na wybranych. Nie zakładaj automatycznie, że pominięty `@BeforeAll` = pominięty `@AfterAll`.
