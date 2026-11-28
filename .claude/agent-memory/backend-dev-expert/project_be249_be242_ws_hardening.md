---
name: be249-be242-ws-hardening
description: BE-249/BE-242 (EPIC-33, tura 82) - sid (V152), znaczniki revoked-since, TenantStatusGate, HMAC cc:ws:*, async terminator; decyzje i pulapki
metadata:
  type: project
---

Decyzje projektowe (2026-11-26):
- `sid` = rodzina refresh tokenow (`refresh_token.session_id`, V152, backfill = token_id). Login zaklada, rotacja dziedziczy, claim `sid` w access tokenie i w `Entry` rejestru WS. Logout bierze sid z access tokenu, a gdy brak - z refresh tokenu TYLKO tego samego usera; nie da sie ustalic = zamknij wszystkie urzadzenia. Wpisy rejestru bez sid sa zamykane przy kazdym zamknieciu per sid.
- `TokenRevocationService`: Redis `ws:revoked-since:{userId}` / `ws:revoked-since:sid:{sid}` = epoch SEKUNDY (iat ma 1 s), odrzut `iat < marker`. JwtAuthFilter: ten sam `try` co blacklista (Redis down = wyjatek = brak auth = 401, de facto fail-closed); CONNECT fail-closed WS_INTERNAL. Czas markera = moment COMMITU (afterCommit), nie wykonania zadania.
- Terminator: ograniczony executor, kolejnosc lokalne zamkniecie -> marker -> publish (lokalne PRZED markerem, zeby wiszacy Redis nie opozniał zamkniecia); saturacja = tylko lokalne.
- HMAC: jeden sekret, nazwa kanalu w podpisie, `previous-secret` = "drugi akceptowany" (rotacja 3-krokowa: bez utraty zdarzen trzeba najpierw akceptowac, potem podpisywac). Prod: `websocket.hmac.required=true` w application-prod.yml.

**Why:** pulapki ktore juz kosztowaly czas.
**How to apply:**
- Spring `--prop=` w `SpringApplicationBuilder.run(args)` powtorzone pod ta sama nazwa LACZY wartosci przecinkiem - w IT deduplikuj (helper `overriding`).
- `@InjectMocks` + `@RequiredArgsConstructor`: argument bez mocka = null (np. MeterRegistry) -> uzyj `@Spy new SimpleMeterRegistry()`.
- Dodanie zaleznosci do `JwtAuthFilter` wymaga `@MockBean` w kazdym `@WebMvcTest` z filtrem (AgentSelfControllerTest, PluginUiSdkStaticAssetIT) i beana w `StompSubscribeAuthorizationE2ETest`.
- `-Dtest=` podane dwa razy: wygrywa ostatnie (uruchomilo caly zestaw zamiast wybranych klas).

Poprawki po review (tura 82, runda 2):
- Closer NIGDY nie porzuca sesji (W1): po limicie prob ponawia co `closeRetryMax`, `abandoned` = alarm. Terminator: local -> marker -> local (2. przebieg, W2) -> publish.
- Replay (W3): `Entry.registeredAt` + `notAfter = ts koperty + replay-tolerance (5 s)` w `take*`; `WebSocketReplayGuard` (LRU podpisow) w bus i fan-out subscriber (`reason=replay`). `CLOSE_TENANT` wola `TenantStatusGate.invalidate`.
- Znacznik monotoniczny przez Lua (`redis.execute(RedisScript)`); blacklista+znaczniki jednym MGET w JwtAuthFilter (`isBlacklistedOrRevoked`).
- Signer: podloga 32 B i sufit maxAge 15 min niezalezne od configu; gauge `ws.channel.accept_unsigned`.
- `COALESCE(session_id, id)` w zapytaniach (stare instancje wstawiaja NULL w rolling deploy); logout `revokeByTokenAndUserId`.
- SUSPENDED/nieznany tenant NIE blokuje (SUSPENDED = tylko alert w panelu admina, brak semantyki dostepu).
