---
name: project-be175-be176-ws-fanout
description: BE-175/BE-176 (EPIC-33) fan-out zdarzen WS miedzy instancjami: Redis pub/sub -> lokalny SimpleBroker, dedykowany kontener 1 watek, koperta, degradacja bez Redis, pulapki testowe
metadata:
  type: project
---

Fan-out WS (ADR-14 opcja B) zrobiony 2026-11-14: `infrastructure/websocket/*` (Publisher, Subscriber, LocalDispatcher, Metrics, Properties, HealthIndicator), `infrastructure/config/WebSocketFanoutConfig`, `security/StompDestinations` (wspolna allow-lista z interceptorem BE-229). `WebSocketEventBroadcasterImpl` -> `WebSocketFanoutPublisher` (lokalna dostawa synchronicznie + koperta do Redis przez 1 watek, backoff po bledzie).

**Why:** wspolny kontener BE-179 ma 2 watki dispatchu (przestawia kolejnosc), wiec fan-out ma WLASNA instancje `PubSubListenerContainer` (1 watek, kolejka 8192); kontener BE-179 oznaczony `@Primary`, WS bean wstrzykiwac przez `@Qualifier("wsFanoutListenerContainer")`. RabbitMQ relay bez zmian (kolejki `cc.queue.ws-relay-*` o stalych nazwach = competing consumers, zdarzenie obsluguje 1 instancja, potem Redis).

**How to apply / pulapki:**
- Klient STOMP w testach musi akceptowac `application/json` (`StringMessageConverter` domyslnie tylko text/plain -> cicho zero wiadomosci): nadpisz `supportsMimeType`.
- `SpringApplicationBuilder.properties(...)` przegrywa z `application.yml` (server.port 8080 -> BindException); uzyj argumentow `--k=v` w `run(...)`.
- Kolejnosc po stronie klienta nie jest gwarantowana (wielowatkowy `brokerChannel` SimpleBroker, tak samo jak przed BE-175); asercja kolejnosci tylko na `brokerChannel` przez `ChannelInterceptor.preSend`.
- `TenantContext` to `InheritableThreadLocal` - watki robocze fan-outu czyszcza kontekst przy starcie.
- Scratchpad `/tmp/claude-.../scratchpad` jest WSPOLNY z innymi agentami: nazwy plikow `verify.log/verify.exit` kolidowaly z agentem BE-230 -> uzywaj prefiksu ticketu (`be175-verify.log`).
- `TcpForwarder` (`support/`) przelacza Redis w testach bez ruszania wspolnego kontenera.
- Pytanie 3 ADR (5000 sesji) nadal otwarte; test 5000 sesji nie zrobiony (BE-184).
