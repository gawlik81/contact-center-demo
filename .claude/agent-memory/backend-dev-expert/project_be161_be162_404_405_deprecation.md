---
name: project_be161_be162_404_405_deprecation
description: BE-161 handler 404 (NoResourceFoundException) + 405 w GlobalExceptionHandler, BE-162 deprecated assigned-contact
metadata:
  type: project
---

GlobalExceptionHandler NIE dziedziczy po ResponseEntityExceptionHandler, a catch-all `Exception` ma pierwszenstwo przed DefaultHandlerExceptionResolver - wyjatki Spring MVC (404/405/415/400...) dawaly 500. BE-161 dodal handlery `NoResourceFoundException` (404, type resource-not-found, detail statyczny bez echa sciezki, log DEBUG) i `HttpRequestMethodNotSupportedException` (405, `headers(ex.getHeaders())` daje Allow). Pozostale wyjatki MVC nadal 500 (lista w CR-BACKEND.md, kandydat na osobny ticket).

**Why:** ticket zakladal, ze 405 dziala - baseline test pokazal 500.
**How to apply:** nowy wyjatek MVC -> sprawdz najpierw czy nie wpada w catch-all. Testy: `AgentSelfControllerTest.UnknownPath` (realny lancuch filtrow, `/plugin-assets/x` testowalny w WebMvcTest).

BE-162: `@Operation(deprecated=true)` + `@Deprecated` na `getAssignedContact()`; test refleksyjny (springdoc nie ladowany w @WebMvcTest, api-docs pod `/api-docs`).
