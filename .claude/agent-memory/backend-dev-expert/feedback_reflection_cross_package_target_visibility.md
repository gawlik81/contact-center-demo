---
name: feedback_reflection_cross_package_target_visibility
description: Mockito/AOP testy z refleksją cross-package (AuditAspect.captureOldValue fetchOldValueMethod) — target class musi być public, nie tylko metoda
type: feedback
---

`AuditAspect#captureOldValue` (fallback dla encji spoza `ENTITY_CLASS_MAP`) woła
`target.getClass().getMethod(fetchMethod, UUID.class).invoke(target, entityId)` — refleksja z
pakietu `infrastructure.aspect` na obiekt `target` zwrócony przez `pjp.getTarget()`.

**Pułapka:** gdy `target` jest instancją klasy testowej zadeklarowanej w INNYM pakiecie (np. test w
`com.contactcenter.infrastructure`, klasa pod testem w `com.contactcenter.infrastructure.aspect` —
częsty układ w tym repo, `AuditAspectTest` leży płasko w `infrastructure`, nie w `infrastructure.aspect`),
sama metoda pomocnicza może być `public`, ale jeśli OTACZAJĄCA klasa (nested static class w teście)
ma domyślną (package-private) widoczność, `Method.invoke()` rzuca `IllegalAccessException` mimo że
`getMethod()` znajduje metodę poprawnie — JVM sprawdza dostępność KLASY deklarującej, nie tylko
metody, przy wywołaniu spoza pakietu.

**Why:** złapane empirycznie przy pisaniu `AuditAspectTest$PiiMasking#captureOldValue_...` (BE-142) —
test failował z `event.oldValue()` == null (wyjątek połknięty przez `catch (Exception e)` w
`captureOldValue`, tylko `log.warn`), bez jawnego stack trace w assercji — trzeba było zgadnąć
przyczynę z wiedzy o Javie, nie z komunikatu testu.

**How to apply:** każda pomocnicza klasa testowa (`static class FetchTarget`/podobne) używana jako
`pjp.getTarget()` w testach `AuditAspect`/podobnych aspektów AOP z refleksją cross-package MUSI być
zadeklarowana jako `public static class`, nie tylko mieć `public` metody. Jeśli test nagle zwraca
`null` tam, gdzie oczekujesz wartości z refleksyjnego wywołania złapanego w szerokim
`catch (Exception e) { log.warn(...); return null; }` — podejrzewaj widoczność klasy, nie tylko
metody, zanim zaczniesz debugować logikę biznesową.
