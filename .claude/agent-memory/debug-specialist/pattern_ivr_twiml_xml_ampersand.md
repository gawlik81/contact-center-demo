---
name: IvrEngineService — niezaescapowany & w URL atrybutu action TwiML (Error 12100)
description: buildVoicebotRecordTwiml buduje voicebotRecordingActionUrl z & (nie &amp;) i wstawia go do atrybutu XML bez escapeXml() — Twilio 12100 Document parse failure
type: project
---

W `IvrEngineService.buildVoicebotRecordTwiml()` URL akcji dla `<Record>` jest budowany przez konkatenację stringów Javy z surowym `&` jako separatorem query params, a następnie wstawiany do atrybutu `action` bez wywołania `escapeXml()` na całym URL. Metoda `escapeXml()` istnieje i jest używana dla tekstu promptu w `<Say>` oraz dla wartości callId, ale ten sam znak `&` w literale `"&callId="` nie jest escapowany.

Konkretna linia problemu (IvrEngineService.java ~587): `+ "&callId=" + escapeXml(callId);` — literal `&` zamiast `&amp;`.
Konkretna linia wstawiania do XML (~603): `sb.append(" action=\"").append(voicebotRecordingActionUrl).append("\"");` — brak `escapeXml(voicebotRecordingActionUrl)`.

Symptom: Twilio Error 12100 "The reference to entity callId must end with the ; delimiter" — parser XML interpretuje `&callId=` jako niezdefiniowaną encję XML. Połączenie rozłączane natychmiast przy wejściu w węzeł VOICEBOT.

Fix (minimalna zmiana): zmienić linię wstawiającą URL na `escapeXml(voicebotRecordingActionUrl)` zamiast samego `voicebotRecordingActionUrl`. Alternatywnie użyć biblioteki Twilio TwiML SDK która escapuje XML automatycznie.

**Why:** Znak & jest poprawny w URL (separator parametrów), ale w wartości atrybutu XML musi być zaescapowany jako &amp;. To klasyczna pułapka przy ręcznym budowaniu XML przez StringBuilder — escapeXml() na samej wartości parametru nie chroni przed & w literale separatora.

**How to apply:** Przy każdym miejscu gdzie URL wieloparametrowy jest wstawiany do XML/HTML atrybutu — zawsze wywołaj escapeXml() na CAŁYM URL-u przed wstawieniem do atrybutu, nie tylko na jego poszczególnych składowych. Dotyczy TwiML, ale też każdego innego szablonu XML budowanego ręcznie w tym projekcie.
