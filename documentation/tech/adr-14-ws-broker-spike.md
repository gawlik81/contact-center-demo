# ADR-14 — spike: broker WebSocket dla wielu instancji backendu (BE-174)

Data: 2026-11-13. Decyzja i uzasadnienie: `ARCHITECTURE.md` §11.14, ADR-14 (status: Zaakceptowany przez właściciela 2026-11-13 — opcja B; allow-list SUBSCRIBE realizuje BE-229; obwiednia ~5 000 sesji i test 5 000 sesji w BE-175 pozostają otwarte).
Ten dokument to raport z pomiarów; kod spike’a leży poza repo (projekt Maven w katalogu scratchpad sesji, `be174-spike`), nie jest częścią produktu.

## 1. Metoda

- Osobny projekt Spring Boot 3.3.5 (ta sama wersja co backend; Spring Framework 6.1.14), JDK 21, bez żadnych zmian w `backend/` i `frontend/`.
- RabbitMQ 3.13.7 (`rabbitmq:3.13-management-alpine`) z pluginem `rabbitmq_stomp` uruchamiany przez Testcontainers 1.20.4 (osobny kontener, nie `cc-rabbitmq`); Redis 7 (opcja B) tak samo.
  Uwaga środowiskowa: demon Dockera na tej maszynie wymaga `-Dapi.version=1.44` dla docker-java z Testcontainers 1.20.4 (bez tego „client version 1.32 is too old”).
- Dwie instancje aplikacji = dwa konteksty Spring w jednym JVM na portach 18081/18082, z tą samą konfiguracją WebSocket co produkcja (te same endpointy `/ws-native`, interceptor na kanale inbound, `/app/ping`, trzy destynacje z `WebSocketEventBroadcasterImpl`).
- Klienci: 500 sesji STOMP po `java.net.http.WebSocket` (własny minimalny klient STOMP 1.2), 10 tenantów x 50 sesji, 250 sesji na instancję; każda sesja subskrybuje `user` i `tenant/agents`.
- Obciążenie: co 100 ms na tenant 1 zdarzenie na topic tenanta (10/s/tenant, fan-out 50) + 10 unicastów (100/s); publikujące instancje na przemian (A, B), więc ~50 % dostaw jest międzyinstancyjnych; 60 s. Opóźnienie = `System.nanoTime()` przy publikacji vs odbiór w kliencie (ten sam JVM).
- Zasoby brokera: management API (`/api/overview`, `/api/nodes`) + `docker stats` własnego kontenera.
- Ograniczenia: wszystko (aplikacje, 500 klientów, generator, RabbitMQ) na jednej 4-rdzeniowej maszynie współdzielonej z innymi kontenerami, więc bezwzględne opóźnienia są zawyżone, a ogony (p95/p99) zdominowane przez harness (GC, jeden JVM). Wiarygodne są różnice względne i liczby zasobów brokera. 500 sesji udało się zrealizować w całości (bez ekstrapolacji); wartości dla 5 000 sesji to ekstrapolacja.

## 2. Kluczowa konfiguracja (opcja A, relay)

```java
// WebSocketConfig (spike) — odpowiednik przyszłego BE-175
registry.enableStompBrokerRelay("/topic", "/queue")
        .setRelayHost(host).setRelayPort(61613)
        .setClientLogin(login).setClientPasscode(passcode)
        .setSystemLogin(login).setSystemPasscode(passcode)
        .setVirtualHost("/contact-center")
        .setSystemHeartbeatSendInterval(10000).setSystemHeartbeatReceiveInterval(10000)
        .setUserDestinationBroadcast("/topic/unresolved-user-destination")
        .setUserRegistryBroadcast("/topic/simp-user-registry");
registry.setApplicationDestinationPrefixes("/app");
```

Wymaga zależności `io.projectreactor.netty:reactor-netty-http` (w backendzie jej nie ma; bez niej start kończy się `IllegalStateException: No compatible version of Reactor Netty`).

Autoryzacja subskrypcji (nowy element; dziś nie istnieje) — interceptor na kanale inbound, wzorzec:

```java
// SUBSCRIBE: allow-list wyliczona z principala
USER_TOPIC   = ^/topic/user\.(UUID)\.events$            // UUID musi == principal.userId
TENANT_TOPIC = ^/topic/tenant\.(UUID)\.(agents|supervisor|supervisor\.metrics)$  // UUID == principal.tenantId; supervisor* tylko SUPERVISOR/ADMIN
// wszystko inne (#, *, **, topiki wewnętrzne relay, stare nazwy ze '/') -> MessagingException -> ERROR + zamknięcie
```

Opcja B (Redis): `Broadcaster.send(dest, payload)` robi `PUBLISH ws "<dest>\u0001<payload>"`; subskrybent Lettuce na każdej instancji wywołuje lokalnie `SimpMessagingTemplate.convertAndSend(dest, payload)` do `SimpleBroker`.

Uprawnienia użytkownika relay, zweryfikowane w spike’u (vhost `/contact-center`, użytkownik bez tagów):
`configure ^(stomp-subscription-.*|events-user.*)$`, `write ^(amq\.topic|amq\.default|stomp-subscription-.*|events-user.*)$`, `read ^(amq\.topic|stomp-subscription-.*|events-user.*)$`.
(Pierwsza próba bez `stomp-subscription-.*` w `write` skończyła się `ACCESS_REFUSED - write access to queue 'stomp-subscription-…'`.)

## 3. Wyniki funkcjonalne

| Sprawdzenie | Wynik |
|---|---|
| `/topic/user.{id}.events` z A do sesji na B | dostarczone |
| `/topic/tenant.{id}.agents` z A i z B do sesji na A i B | dostarczone; sesja tenanta 2 nie dostała nic z tenanta 1 |
| `/user/queue/events` (`convertAndSendToUser`) A->B i B->A z `setUserDestinationBroadcast` | dostarczone; `@SendToUser` PONG po `/app/ping` też |
| Supervisor topic dla roli SUPERVISOR | dostarczony; AGENT nie może zasubskrybować |
| Autoryzacja SUBSCRIBE przy aktywnym relay | odrzucone (ERROR): cudzy tenant, cudzy user, `/topic/#`, `/topic/tenant.*.agents`, `/topic/tenant.#`, stara nazwa ze `/`, `/topic/simp-user-registry`, `/topic/unresolved-user-destination`; własny tenant przechodzi |
| Broker bez aplikacji (surowy klient STOMP na 61613) | subskrypcja `/topic/#` odbiera zdarzenia innych tenantów — broker nie egzekwuje ACL destynacji |
| Nazwy destynacji | RabbitMQ odrzuca `/topic/a/b`: `'/…/events' is not a valid topic destination` (slash niedozwolony); kropki działają, routing key = nazwa po `/topic/` (np. `tenant.<uuid>.agents`) |
| `/queue/…` w RabbitMQ | kolejka trwała, nie auto-delete (`dur=true, ad=false`), zostaje po rozłączeniu; z nagłówkiem `auto-delete:true` znika. 500 sesji z `/user/queue/events` zostawiło 500 kolejek (1504 -> 504) |
| Obecność (`SessionConnectedEvent` z principalem, `SessionSubscribeEvent`, `SessionDisconnectEvent`; zamknięcie czyste i zerwanie TCP) | wszystkie zdarzenia lokalnie na instancji trzymającej WebSocket, nigdy na drugiej; `SessionDisconnectEvent` bywa liczony podwójnie w jednym oknie |
| Heartbeat klienta (`heart-beat:1000,0`) | przekazywany do brokera: klient wysyłający `\n` co 500 ms żyje, klient bez heartbeatu odcięty („Connection to broker closed.”) |
| SimpleBroker bez autoryzacji SUBSCRIBE (odpowiednik produkcji, sonda `Probe`) | AGENT tenanta 1 z subskrypcjami `/topic/**`, `/topic/tenant/*/agents`, `/topic/user/{cudzy}/events` odebrał zdarzenia tenanta 2 i cudzego usera. Istniejąca luka, niezależna od wyboru brokera |

## 4. Wyniki wydajnościowe (500 sesji, 60 s, profil z p. 1)

| Scenariusz | Dostawy oczekiwane/odebrane | p50 | p95 | p99 | max |
|---|---|---|---|---|---|
| Baseline: 1 instancja, SimpleBroker | 314 160 / 314 160 | 50,9 ms | 204,8 ms | 431,5 ms | 972 ms |
| A: relay RabbitMQ, 2 instancje | 285 550 / 285 600 | 137,0 ms | 390,5 ms | 527,1 ms | 883 ms |
| A, dostawy w obrębie instancji | 142,7 tys. | 137,8 ms | 392,7 ms | 525,8 ms | 847 ms |
| A, dostawy międzyinstancyjne | 142,9 tys. | 136,0 ms | 388,6 ms | 528,2 ms | 883 ms |
| B: Redis pub/sub, 2 instancje | 312 120 / 312 120 | 34,5 ms | 402,8 ms | 946,2 ms | 1813 ms |
| A + `/user/queue/events` na sesję (30 s) | 164 352 / 164 352 | 90,3 ms | 334,1 ms | 509,0 ms | 792 ms |
| Lekkie obciążenie (100 unicast/s, 30 s): lokalnie | 3 010 / 3 010 | 3,0 ms | 8,5 ms | 14,4 ms | 40,5 ms |
| Lekkie obciążenie: A | 3 130 / 3 130 | 5,8 ms | 16,3 ms | 25,5 ms | 140 ms |
| Lekkie obciążenie: B (nieoptymalizowany listener) | 3 010 / 3 010 | 9,7 ms | 30,5 ms | 58,7 ms | 165 ms |

Brak utraconych zdarzeń w żadnym scenariuszu (w A odebrano o 50 więcej niż policzono — jedna publikacja zgłosiła wyjątek po faktycznym wysłaniu; nie oznacza duplikatów u klientów).

Zasoby (500 sesji):

| Stan | RabbitMQ |
|---|---|
| bez sesji (połączenia systemowe relay: 1–2) | 1–2 kanały, 4 kolejki, RAM 161–167 MB |
| 500 sesji, bez ruchu | 502 połączenia, 502 kanały, 1004 kolejki, 1004 konsumentów, ~5,7 tys. procesów Erlanga, RAM 201 MB, CPU ~15 % rdzenia |
| 500 sesji, obciążenie ~5,1 tys. dostaw/s | RAM 397–449 MB (RSS kontenera 396–429 MB), CPU ~77 % jednego rdzenia, fd 549/1024, sockety 502/832, ~10 tys. procesów |
| po zamknięciu sesji (tylko `/topic`) | kolejki sesji auto-usunięte (wraca do 2 połączeń i 4 kolejek) |
| Redis (opcja B) pod tym samym obciążeniem | 5 połączeń klientów, ~4 MB, ~1,4 % CPU |

Limit deskryptorów: domyślny `nofile`=1024 w kontenerze RabbitMQ daje `sockets_total`=832, czyli twardy sufit ~830 sesji (500 sesji = 549 fd, ~1 fd na sesję).
Ekstrapolacja na 5 000 sesji (cel PRD: 100 agentów x 50 tenantów): ~5 000 połączeń, ~10 000 kolejek, ~100 tys. procesów, +0,4…2,9 GB RAM (0,08 MB/sesję w spoczynku … 0,57 MB/sesję pod obciążeniem) wobec progu 40 % pamięci węzła (4 GB w DEPLOYMENT §20 = ~1,6 GB), po przekroczeniu którego RabbitMQ blokuje wydawców — także kolejek biznesowych.

## 5. Restart brokera (A, 500 sesji, klienci z reconnectem co 1 s jak FE, ruch 10 zdarzeń/s)

- `docker restart` zwrócił po 5,5 s; `BrokerAvailabilityEvent` false po ~0,3–0,5 s, true po ~35,4–35,6 s (rozruch RabbitMQ na tej maszynie ~25–30 s).
- Wszystkie 500 sesji ponownie połączone po 38,9 s, każda odebrała zdarzenie po restarcie po 41,2 s od komendy (3–6 s po powrocie brokera).
- W trakcie awarii: 11 155 zamknięć sesji (burza reconnectów ~320/s przy stałym opóźnieniu 1 s), po ~5,6 tys. `SessionDisconnectEvent` na każdej instancji; 350 wyjątków `MessageDeliveryException: Message broker not active` przy publikacji (w produkcji `WebSocketEventBroadcasterImpl` je połyka i loguje — ryzyko zalania logów).
- Wniosek: restart brokera zawsze zrywa wszystkie sesje WebSocket; potrzebny backoff z jitterem po stronie FE (FE-155) i indykator zdrowia na `BrokerAvailabilityEvent`. Karencja obecności 90 s > czas restartu 35 s.
- Opcja B nie rozłącza sesji przy restarcie Redisa (subskrypcja wznawia się, tracone tylko zdarzenia z luki); nie mierzono restartu Redisa — ocena z architektury.

## 6. Pliki wynikowe spike’a (poza repo)

`results-*.txt`, `run-*.log` w katalogu `be174-spike` w scratchpadzie sesji (fazy: `functional`, `load`, `baselines`, `optionB`, `restart`, `light`, sondy `Probe`, `Probe2`).
