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
