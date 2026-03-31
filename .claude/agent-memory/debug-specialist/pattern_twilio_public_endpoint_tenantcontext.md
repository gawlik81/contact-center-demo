---
name: Twilio webhook + TenantContext — pułapka publicznego endpointu
description: ContactRepository.insert() wywołuje assertSameTenant() z ThreadLocal, co rzuca ISE gdy endpoint jest publiczny (brak JWT/TenantContext w wątku). Typowy scenariusz: TwilioWebhookController → TwilioTelephonyAdapter.persistContact() → ContactRepository.insert() → assertSameTenant() → ISE.
type: project
---

Każde repozytorium rozszerzające `TenantAwareRepository` które wywołuje `assertSameTenant(entity.getTenantId())` (bez jawnego contextTenantId) używa `TenantContext.getTenantId()` z ThreadLocal. Gdy żądanie przychodzi przez publiczny endpoint (np. `/api/telephony/webhook/twilio`), `TenantFilter` pomija ten endpoint i ThreadLocal jest pusty.

**Why:** Odkryto w sesji 2026-03-27 przy analizie logów 07:50–07:51. `persistContact()` w `TwilioTelephonyAdapter` (linia 711) wywołuje `contactRepository.insert()` (linia 238), która wywołuje `assertSameTenant()` rzucając `IllegalStateException`, złapany przez Spring JPA jako `InvalidDataAccessApiUsageException`. Metoda `persistContact()` łapie to w bloku `catch` i zwraca `null` — kontakt nie trafia do DB.

**How to apply:** Gdy implementujesz zapis do DB z wątku publicznego endpointu (bez JWT):
- Użyj `setTenantContextInDb(explicitTenantId)` zamiast `setTenantContextInDb()` (ThreadLocal)
- Usuń lub zastąp `assertSameTenant()` inline-walidacją gdy contextTenantId nie jest w ThreadLocal
- Alternatywnie: dodaj do `TenantAwareRepository` przeciążoną metodę `assertSameTenant(entityId, explicitContextId, resourceId)`
