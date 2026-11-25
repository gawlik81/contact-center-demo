---
name: project-be233-ws-session-lifecycle
description: BE-233 (EPIC-33) - rejestr sesji WS po userId, blacklista JWT przy CONNECT, zamykanie sesji przez Redis control channel, max lifetime; kody ERROR bez zmian w FE
metadata:
  type: project
---

BE-233 (gałąź feature/epic33-ws-session-lifecycle-shedlock-metrics, niezacommitowane): `infrastructure/websocket/WebSocketSession{Properties,Registry,Closer,ControlBus,ControlEnvelope,TerminatorImpl}` + `domain/websocket/{SessionCloseReason,WebSocketSessionTerminator}`.

- Zamknięcie sesji = wysłanie ramki STOMP ERROR (`code`) przez `clientOutboundChannel` z samym `sessionId`; Spring (`StompSubProtocolHandler.sendToClient`) zamyka transport po każdym wychodzącym ERROR - NIE potrzeba WebSocketHandlerDecorator ani uchwytu WebSocketSession.
- Kody: `WS_TOKEN_INVALID` (logout/dezaktywacja/rola/reset hasła/tenant) - FE robi 1 refresh (rola: nowy token, logout: odmowa -> SESSION_EXPIRED); `WS_TOKEN_EXPIRED` (exp / max-lifetime). FE niezmieniony.
- Rejestr: mutacje `synchronized` (dwa indeksy muszą zmieniać się razem, inaczej osierocony id w indeksie usera); `Closer` zależy od `clientOutboundChannel`, a interceptor tylko od rejestru - inaczej cykl beanów z WebSocketConfig.
- `@Scheduled` sweep bez ShedLock => wpis w `UNLOCKED_BY_DESIGN` (SchedulerLockCoverageTest), wiersz #27 klasy I w `documentation/tech/04-backend.md`, licznik 27 w `FullApplicationContextStartupTest`, 4. kontener pub/sub.
- Testy IT z 2 kontekstami współdzielą instancje A/B: asercje na rejestrze/licznikach muszą być względne (baseline), nie absolutne. Argumenty command-line `--x=1 --x=2`: nie dubluj kluczy (zwycięża pierwszy) - env kanału przekazuj jednym argumentem.
- Wspólny scratchpad sesji jest dzielony z drugim agentem: logi mvn nazywaj unikalnie (np. `be233-*.log`), inaczej się mieszają.
