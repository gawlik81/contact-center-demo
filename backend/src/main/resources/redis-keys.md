# Redis Key Schema – Contact Center SaaS
## DB-016: Struktury danych Redis i polityki TTL

Stack: Redis 7.x
Polityka pamieci: `maxmemory-policy allkeys-lru`
Persistence: AOF wlaczone dla namespace `jwt:blacklist` (lub dedykowana instancja)

---

## Przestrzenie kluczy

### 1. JWT Blacklista (wylogowanie i odwolanie tokenow)

```
jwt:blacklist:{token_hash}     TTL = pozostaly czas waznosci access tokenu
```

- Wartosc: `"1"` (flaga obecnosci)
- Hash: SHA-256 (hex) access tokenu
- Uzycie: po kazdym zadaniu HTTP middleware sprawdza czy token jest na blackliscie
- Persistence: AOF (krytyczne – utrata = mozliwosc uzycia odwolanych tokenow)
- Szacunkowy rozmiar: ~100 bajtow per klucz

### 2. Sesja i obecnosc agenta

```
session:agent:{user_id}        TTL = 8 godzin (odnawiane przy aktywnosci)
```

- Typ Redis: Hash
- Pola:
  - `tenant_id` – UUID tenanta
  - `status` – AVAILABLE | BUSY | BREAK | AFTER_CONTACT
  - `active_contact_ids` – JSON array UUID aktywnych kontaktow
  - `queue_ids` – JSON array UUID przypisanych kolejek
  - `last_heartbeat` – Unix timestamp
  - `ws_session_id` – ID sesji WebSocket

### 3. CLI Lookup (szybkie dopasowanie klienta po numerze telefonu)

```
cache:customer:phone:{normalized_phone}    TTL = 5 minut
cache:customer:email:{normalized_email}    TTL = 5 minut
```

- Typ Redis: String (JSON)
- Wartosc: `{"customer_id": "uuid", "first_name": "...", "last_name": "...", "tenant_id": "uuid"}`
- Normalizacja: usun spacje, myslniki; format E.164
- Invalidacja: przy UPDATE customer

### 4. Stan kolejek (Real-time dashboard)

```
cache:queue:stats:{queue_id}    TTL = 5 sekund
```

- Typ Redis: Hash
- Pola:
  - `queued_count` – liczba kontaktow w kolejce
  - `available_agents` – liczba dostepnych agentow
  - `avg_wait_seconds` – sredni czas oczekiwania
  - `oldest_contact_age` – wiek najstarszego kontaktu (sekundy)
- Uzycie: dashboard RT supervisora (odswiezanie co 5 sekund – NFR-P07)

### 5. Metryki tenanta (dashboard admina)

```
cache:tenant:metrics            TTL = 30 sekund
```

- Typ Redis: Hash – klucz = tenant_id (UUID), wartosc = JSON metryk
- Pola per tenant: `active_agents`, `queued_contacts`, `active_contacts`, `error_count`

### 6. Rate limiting (NFR-SEC09: max 1000 req/min per token)

```
rate:api:{token_hash}           TTL = 60 sekund (okno czasowe)
rate:login:{ip_address}         TTL = 15 minut (blokada po nieudanych probach)
```

- Typ Redis: String (counter incr)
- Algorytm: Sliding window lub Fixed window
- Prog: 1000 req/min per token, 5 nieudanych logowan per IP = blokada

### 7. Lokalna blokada zasobow (distributed lock)

```
lock:campaign:{campaign_id}     TTL = 30 sekund (auto-release)
lock:dialer:{tenant_id}         TTL = 5 sekund
```

- Typ Redis: String z SET NX PX (Redlock pattern)
- Uzycie: zapobieganie race condition w dialerze progresywnym

### 8. Pub/Sub (Real-time events do WebSocket)

```
pubsub:tenant:{tenant_id}:contact.events
pubsub:tenant:{tenant_id}:queue.updates
pubsub:tenant:{tenant_id}:agent.status
```

- Typ Redis: Pub/Sub channel
- Subskrybenci: wszystkie instancje Spring Boot (fan-out do WebSocket clients)
- Bez TTL (kanalowe)

---

## Konfiguracja Redis (redis.conf)

```conf
# Maksymalna pamiec (dostosowac do srodowiska)
maxmemory 2gb

# Polityka eksmisji: usuwa dowolne klucze (LRU) gdy pamiec pelna
# Krytyczne: jwt:blacklist moze byc eksmitowane – uzyj dedykowanej instancji z noeviction
maxmemory-policy allkeys-lru

# AOF persistence dla bezpieczenstwa (jwt:blacklist)
appendonly yes
appendfsync everysec

# Clustering (opcjonalne dla HA)
# cluster-enabled yes
```

---

## Java Constants (RedisKeyConstants.java)

```java
// Namespace constants – uzywane w calym projekcie Spring Boot
public final class RedisKeyConstants {

    // JWT Blacklist
    public static final String JWT_BLACKLIST = "jwt:blacklist:%s";         // %s = token_hash

    // Agent session
    public static final String AGENT_SESSION = "session:agent:%s";         // %s = user_id

    // Customer cache
    public static final String CUSTOMER_PHONE = "cache:customer:phone:%s"; // %s = phone
    public static final String CUSTOMER_EMAIL = "cache:customer:email:%s"; // %s = email

    // Queue stats
    public static final String QUEUE_STATS = "cache:queue:stats:%s";        // %s = queue_id

    // Tenant metrics
    public static final String TENANT_METRICS = "cache:tenant:metrics";

    // Rate limiting
    public static final String RATE_API = "rate:api:%s";                    // %s = token_hash
    public static final String RATE_LOGIN = "rate:login:%s";                // %s = ip_address

    // Distributed locks
    public static final String LOCK_CAMPAIGN = "lock:campaign:%s";         // %s = campaign_id
    public static final String LOCK_DIALER = "lock:dialer:%s";             // %s = tenant_id

    // TTL constants (seconds)
    public static final int TTL_AGENT_SESSION    = 8 * 60 * 60;  // 8 hours
    public static final int TTL_CUSTOMER_CACHE   = 5 * 60;       // 5 minutes
    public static final int TTL_QUEUE_STATS      = 5;            // 5 seconds
    public static final int TTL_TENANT_METRICS   = 30;           // 30 seconds
    public static final int TTL_RATE_API         = 60;           // 1 minute window
    public static final int TTL_RATE_LOGIN       = 15 * 60;      // 15 minutes

    private RedisKeyConstants() {}
}
```
