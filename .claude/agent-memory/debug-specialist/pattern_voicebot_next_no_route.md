---
name: VoicebotClient escalate=false bez opcji "next" w węźle — fallback po udanym rozpoznaniu
description: Po naprawie confidence_threshold voicebot zaczął zwracać escalate=false, ale brak opcji "next" w węźle VOICEBOT powoduje fallbackToDefaultQueue mimo poprawnej odpowiedzi.
type: project
---

Węzeł VOICEBOT ma cztery logiczne wyjścia wymagające konfiguracji w edytorze IVR:
- `"escalate"` — gdy `resp.escalate() == true` (IvrEngineService linia 1293)
- `"next"` — gdy `resp.escalate() == false` (linia 1312); brak → `fallbackToDefaultQueue` linia 1315
- `"fallback"` — gdy voicebot niedostępny lub błąd (linia 1327)
- `queueId` — alternatywa dla `"escalate"` bez opcji (linia 1296)

Incydent 5 (08:15): supervisor skonfigurował TYLKO `"escalate"` (bo w incydencie 4 zawsze eskalowało).
Po obniżeniu progu do 0.40 voicebot zaczął zwracać `escalate=false` — ścieżka `"next"` aktywna, ale pusta.

**Why:** Efekt uboczny naprawy incydentu 4 (obniżenie confidence_threshold). Ścieżka `escalate=false`
była wcześniej martwa (100% eskalacji przy progu 0.70) i nikt jej nie skonfigurował.

**How to apply:** Przy diagnozie fallback z `escalate=false` zawsze sprawdź czy `"next"` jest obecny
w opcjach węzła. Przy diagnozie fallback z `escalate=true` sprawdź `"escalate"` i `queueId`.
Oba wyjścia są zawsze wymagane dla poprawnego funkcjonowania węzła VOICEBOT.
