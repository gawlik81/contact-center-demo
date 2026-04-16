---
name: OAuth CSRF state pattern – Redis single-use
description: Wzorzec ochrony CSRF w OAuth callback przez Redis (state → tenantId, single-use TTL 10 min)
type: feedback
---

W publicznym endpoincie OAuth callback (bez JWT) parametr `state` musi być:
1. Generowany przy `initiateOAuth()` (z JWT → tenantId znany)
2. Zapisywany do Redis: klucz `oauth:state:{state}` → wartość `tenantId.toString()`, TTL 10 minut
3. Weryfikowany w `oauthCallback()`: odczytaj tenantId z Redis, usuń klucz (single-use)
4. Użyty do ustawienia `TenantContext.setTenantId(tenantId)` przed wywołaniem serwisu

`Map.of()` nie akceptuje null — gdy `accessTokenEncrypted` może być null, zastąp null przez `new byte[0]` i sprawdzaj `encryptedToken.length > 0` zamiast `!= null`.

**Why:** Publiczny callback nie ma JWT, więc TenantContext jest pusty. State → Redis rozwiązuje dwa problemy: CSRF i propagację tenantId do wątku callback.

**How to apply:** Każdy publiczny endpoint OAuth callback w tym projekcie.
