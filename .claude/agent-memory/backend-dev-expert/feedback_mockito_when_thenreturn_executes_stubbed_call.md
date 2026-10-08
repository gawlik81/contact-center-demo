---
name: feedback_mockito_when_thenreturn_executes_stubbed_call
description: when(mock.metoda(arg)).thenReturn(...) WYKONUJE mock.metoda(arg) żeby je zarejestrować — na współdzielonym (static) mocku wcześniejszy when(any()).thenThrow(...) przechwyci to wywołanie i rzuci wyjątek W SETUP, nie w logice testu
metadata:
  type: feedback
---

Odkryte przy BE-123 (`PartitionReclaimPlatformHorizonIntegrationTest`, 2026-10-08): test
"ścieżka niezależna od `RetentionPolicyService`" stubował `when(retentionPolicyService
.findMaxRetentionMonths(any())).thenThrow(new ResourceNotFoundException(...))`. Mock jest polem
`static` (jeden na całą klasę testową, Testcontainers/`JpaTestContext`), a `@BeforeEach setUp()`
w KOLEJNYM teście próbował `when(retentionPolicyService.findMaxRetentionMonths(TRANSCRIPTS))
.thenReturn(60)` — i to `setUp()` samo rzuciło `ResourceNotFoundException`, zanim dotarło do
`.thenReturn(60)`.

**Why:** Mockito's `when(mock.foo(x))` najpierw FAKTYCZNIE WYKONUJE `mock.foo(x)` (żeby
framework "zobaczył" to wywołanie i mógł do niego dopisać nowy stub), a DOPIERO wynik tego
wywołania jest przechwytywany przez `when(...)`. Jeśli na tym mocku istnieje JUŻ zarejestrowany
stub pasujący do tego wywołania (tu: `any()` z `thenThrow`, zarejestrowany we WCZEŚNIEJSZYM
teście na tym samym statycznym mocku), to wywołanie `mock.foo(x)` wewnątrz `when(...)` rzuca ten
wyjątek NATYCHMIAST — `.thenReturn(60)` nigdy nie zostaje dołożone, bo wyjątek przerywa
wykonanie linii. Błąd pojawia się w `setUp()`, nie w ciele testu, co jest mylące (wygląda jak
awaria infrastruktury testowej, nie logiki testu).

**How to apply:** Gdy mock jest `static`/współdzielony między testami w jednej klasie i
JAKIKOLWIEK test w tej klasie stubuje `thenThrow` dla szerokiego matchera (`any()`), dodaj
`Mockito.reset(mock)` na POCZĄTKU `@BeforeEach`, PRZED ponownym stubowaniem — nie polegaj na
założeniu "ostatni zarejestrowany stub wygrywa", bo to jest prawdą dla ODPOWIADANIA na
wywołanie, ale nie chroni przed tym, że samo PONOWNE stubowanie (`when(...)`) może zostać
przechwycone przez stary stub. Alternatywa: użyj `doThrow(...).when(mock).foo(any())` tylko
tam gdzie się rzuca (nie zmienia tej pułapki dla INNYCH, późniejszych `when().thenReturn()` na
tym samym mocku) — `reset()` w `@BeforeEach` jest prostsze i pewniejsze.

Powiązane: [[project_be123_platform_horizon_reclaim]], [[feedback_mockito_nested_beforeeach]].
