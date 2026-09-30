---
name: project_local_demo_env_constraints
description: Ograniczenia lokalnego środowiska docker-compose.local-demo.yml — backend nie jest wystawiony na host, brak znanych danych logowania supervisora
type: project
---

W `docker-compose.local-demo.yml` backend ma tylko `expose: ["8080"]`, NIE `ports:` — port 8080 nie jest
publikowany na hosta. Jedyny publikowany port to `80:80` przez `cc-nginx`. Frontend (`cc-frontend`) to
zbudowany obraz statyczny (multi-stage Dockerfile, `ng build` + nginx), NIE dev server z hot-reload.

**Why:** `frontend/proxy.conf.json` kieruje `/api` i `/ws` na `http://localhost:8080`, więc `npm start`
(dev server Angular) nie zadziała w tym stacku — proxy nie znajdzie backendu na hoście. Weryfikacja zmian
wymaga więc przebudowania obrazu frontendu i podmiany kontenera, a nie `npm start`:
```
docker compose --env-file .env.local-demo -f docker-compose.yml -f docker-compose.local-demo.yml build frontend
docker compose --env-file .env.local-demo -f docker-compose.yml -f docker-compose.local-demo.yml up -d --remove-orphans frontend
```
Potem aplikacja jest dostępna pod `http://localhost:80/` (przez nginx).

**Konta w bazie dev** (sprawdzone `docker exec cc-postgres psql`): realne konta użytkownika (nie z
`V999__dev_seed.sql`), np. `supervisor@kmnsoftware.com` (tenant `680dc6bb-2bbd-4174-9bfe-2679d058327c`) —
**hasło nieznane, nie próbowałem go resetować bez pytania** (zmiana hasła realnego konta = "changing
account settings", wymaga jawnej zgody użytkownika). Konta z `V999__dev_seed.sql` (np.
`supervisor1@acme.dev` / hasło `Test@12345`, hash `$2a$12$b7S/mPXPbip0cNDfN5oFB.UCLXFqGaAO97oXynzYjMFlBuA.zLjt6`)
NIE są załadowane do tej konkretnej bazy — sprawdzone, 0 wierszy.

**How to apply:** Gdy zadanie wymaga wizualnej weryfikacji w przeglądarce zalogowanym jako
supervisor/agent/admin, a nie mam danych logowania: NIE zgaduj/nie resetuj hasła bez pytania. Alternatywa
zweryfikowana i zaakceptowana w praktyce: zbuduj statyczną stronę HTML łączącą (a) rzeczywisty
skompilowany CSS komponentu wyciągnięty z przebudowanego obrazu (`docker cp cc-frontend:/usr/share/nginx/html/chunk-*.js`
— dla standalone components z emulated encapsulation style jest wklejony jako string w JS chunku, trzeba
znaleźć właściwy chunk przez `grep -l "<szukana-klasa>" *.js` i wyciąć fragment tekstu CSS między
znanymi selektorami, potem usunąć `[_ngcontent-%COMP%]`) + (b) globalny arkusz stylów
(`styles-*.css` — tokeny oklch) + (c) prawdziwe dane z bazy (`docker exec cc-postgres psql`). Otwórz przez
lokalny `python3 -m http.server` w scratchpadzie (Chrome extension nie nawiguje na `file://`) i zrób
zrzut ekranu przez `mcp__claude-in-chrome`. Do testu media query na wąskim viewporcie użyj `<iframe
width="375">` z `srcdoc` (media queries reagują na viewport ramki/iframe, NIE na szerokość kontenera
div w tej samej stronie — zwykły `<div style="width:375px">` nie wyzwoli `@media(max-width:480px)`).
Zawsze jawnie ujawnij użytkownikowi że to rekonstrukcja, nie prawdziwe logowanie.

**Dostępność `claude-in-chrome` jest zmienna między sesjami — sprawdzaj za każdym razem, nie zakładaj z pamięci.**
Sesja FE-098 (wcześniejsza notatka w tym pliku): niedostępne. Sesja FE-104 (`reference_local_demo_browser_testing.md`
w pamięci użytkownika, 2026-08-12): dostępne i **działało** (zalogowano się realnie jako ADMIN). Sesja FE-112
(2026-09-30): narzędzie `mcp__claude-in-chrome__*` było dostępne i odpowiadało, ALE przeglądarka rozszerzenia nie
miała sieciowego dostępu do `localhost` tego sandboxa — `navigate` do `http://localhost/` kończył się błędem
(`Frame with ID 0 is showing error page` przy próbie screenshotu) mimo że `curl http://localhost/` z tego samego
sandboxa (shell) działał poprawnie i zwracał świeży HTML po przebudowie obrazu; dla kontrastu `http://example.com/`
w TEJ SAMEJ karcie załadował się i zrobił się z niego poprawny screenshot — czyli przeglądarka miała internet, ale
"localhost" w jej kontekście to inny host niż sandbox z docker-compose. Nie próbowałem `socat`
port-forward w tej sesji (to obejście dla portu backendu 8080 przy `npm start`, nie dla tego objawu — nie było jasne
czy pomogłoby, a task nie wymagał dalszego drążenia).
**How to apply:** (1) Zawsze najpierw spróbuj `tabs_context_mcp` + `navigate` do `http://localhost/` + `screenshot`
— jeśli się nie uda, zrób DIAGNOSTYKĘ przez nawigację do zewnętrznego URL (np. `example.com`) w tej samej karcie:
jeśli TO działa, a `localhost` nie — to jest izolacja sieciowa tej konkretnej sesji (nie błąd w kodzie), zgłoś to
użytkownikowi i idź dalej bez wizualnej weryfikacji zamiast drążyć w kółko (zgodnie z regułą skilla
`claude-in-chrome`: "stop and ask... do not keep retrying"). (2) Nie trać czasu na wielokrotne retry
nawigacji/screenshotów tego samego URL-a — jeden nieudany + jeden diagnostyczny test zewnętrznego URL-a wystarczy do
pewnej diagnozy.
