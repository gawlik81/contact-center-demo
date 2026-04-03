---
name: VoicebotClient escalate=true bez opcji escalate w węźle — fallback zamiast routingu
description: Voicebot odpowiada poprawnie escalate=true, ale węzeł VOICEBOT nie ma opcji "escalate" ani queueId — silnik trafia do fallbackToDefaultQueue
type: project
---

`IvrEngineService.executeVoicebot` linia 1307: gdy `resp.escalate()=true` i węzeł nie ma
opcji `"escalate"` oraz `node.queueId()==null`, silnik wywołuje `fallbackToDefaultQueue`.
Log identyfikujący: `WARN [IVR] VOICEBOT: eskalacja bez docelowego węzła/kolejki – fallback`.

**Why:** Konfiguracja węzła VOICEBOT przez supervisora w edytorze IVR jest niekompletna —
brak połączenia do węzła eskalacyjnego lub kolejki. W połączeniu z algorytmem NLU który
strukturalnie zwraca niskie confidence (keyword matching skrzyżowany z Whisper avg_logprob),
próg `confidence_threshold=0.70` (config.py:8) jest zbyt wysoki — prawie każde połączenie
eskaluje. Formuła NLU: `min(1.0, (matched/2.0) * asr_confidence)` — przy 2 dopasowaniach
i typowym audio: `1.0 * 0.607 = 0.607`, co nadal nie przekracza progu 0.70.

**How to apply:** Przy incydentach gdzie logi VoicebotClient nie pokazują WARN (serwis odpowiedział),
ale IVR trafia na fallback — szukaj logu `eskalacja bez docelowego węzła/kolejki`.
Naprawka priorytetowa: konfiguracja węzła (opcja "escalate" lub queueId).
Naprawka strukturalna: obniżenie progu do 0.40 w `voicebot/app/config.py`.
