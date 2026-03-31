---
name: IvrEngineService — niezaescapowany & w URL atrybutu action TwiML (Error 12100)
description: buildTwimlForNode buduje dtmfActionUrl z & (nie &amp;) i wstawia go do atrybutu XML bez escapeXml() — Twilio 12100 Document parse failure
type: project
---

W `IvrEngineService.buildTwimlForNode()` URL akcji DTMF jest budowany z surowym `&` jako separatorem query params, a następnie wstawiany do atrybutu `action` elementu `<Gather>` bez wywołania `escapeXml()`. Metoda `escapeXml()` istnieje i jest używana dla tekstu promptu w `<Say>`, ale jest pominięta dla wartości URL atrybutu.

Konkretna linia problemu: linia 366 `+ "&callId=" + callId;` — separator `&` zamiast `&amp;`.
Konkretna linia wstawiania do XML: linia 391 `sb.append("<Gather action=\"").append(dtmfActionUrl).append("\"");` — brak `escapeXml(dtmfActionUrl)`.

Symptom: Twilio Error 12100 "The reference to entity callId must end with the ; delimiter" — parser XML interpretuje `&callId=` jako niezdefiniowaną encję XML. Połączenie rozłączane natychmiast.

**Why:** Znak & jest poprawny w URL (separator parametrów), ale w wartości atrybutu XML musi być zaescapowany jako &amp;. To klasyczna pułapka przy ręcznym budowaniu XML przez StringBuilder.

**How to apply:** Przy każdym miejscu gdzie URL wieloparametrowy jest wstawiany do XML/HTML atrybutu — zawsze wywołaj escapeXml() lub użyj &amp; zamiast & w samym URL. Dotyczy TwiML, ale też każdego innego szablonu XML budowanego ręcznie.
