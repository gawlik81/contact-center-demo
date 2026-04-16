---
name: @Transactional bez blokującego I/O – wzorzec podziału
description: Metody @Transactional nie mogą trzymać blokady DB podczas synchronicznego HTTP do zewnętrznych API
type: feedback
---

Metoda `@Transactional` trzyma połączenie z puli HikariCP przez cały czas jej trwania. Synchroniczne wywołanie zewnętrznego HTTP API (np. Graph API) wewnątrz `@Transactional` może wyczerpać pulę połączeń przy dużym obciążeniu.

Wzorzec naprawy: podziel metodę na trzy etapy:
1. `@Transactional(readOnly = true)` – odczyt i walidacja danych (krótka transakcja)
2. `@Transactional` – zapis/usunięcie z DB (krótka transakcja)
3. Bez `@Transactional` – wywołanie zewnętrznego API (poza transakcją)

Metody pomocnicze muszą być `protected` (nie `private`) – `@Transactional` przez proxy nie działa na `private` (self-invocation problem).

**Why:** deleteIntegration w SocialIntegrationService trzymało transakcję podczas DELETE do Graph API. Ryzyko wyczerpania HikariCP przy dużej liczbie concurrent delete.

**How to apply:** Zawsze gdy metoda @Transactional wywołuje synchroniczny HTTP client (java.net.http.HttpClient, RestTemplate, WebClient.block()).
