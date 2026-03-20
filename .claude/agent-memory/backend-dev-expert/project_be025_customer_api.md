---
name: BE-025 Customer CRUD API
description: Szczegóły implementacji Customer CRUD API z fuzzy search i anonimizacją RODO
type: project
---

BE-025 zostało zaimplementowane (2026-03-19).

**Why:** Blokuje BE-026, BE-031, FE-018, FE-019, FE-011.

**Pliki:**
- `domain/model/Customer.java` – rozszerzono o pola `customFields`, `gdprConsent`, `source` (wcześniej brakowało)
- `domain/repository/CustomerRepository.java` – dodano CRUD: `findById`, `searchByQuery`, `findByTenantIdPaged`, `countByTenantId`, `save`, `anonymize`, `createFromUnknownCaller`
- `domain/service/CustomerService.java` – nowy serwis
- `api/customer/CustomerController.java` – nowy kontroler
- `api/customer/dto/` – CreateCustomerRequest, UpdateCustomerRequest, CustomerResponse
- `infrastructure/config/RabbitMQConfig.java` – nowa kolejka `QUEUE_UNKNOWN_CALLER = "cc.queue.unknown-caller"`, routing key `call.unknown_caller`

**Kluczowe decyzje:**
- Fuzzy search: wywołanie natywnej funkcji PostgreSQL `search_customers()` z indeksem GIN trigram
- Paginacja: offset-based (page/size), nie cursor-based – PagedResponse-like Map zamiast PagedResponse<T>, bo fuzzy search i lista mają różny kształt odpowiedzi
- Anonimizacja RODO: natywny UPDATE (nie JPA merge) – atomowa operacja bez ładowania encji
- RabbitMQ UNKNOWN_CALLER: dedykowana kolejka `cc.queue.unknown-caller` z bindingiem do `cc.events` na `call.unknown_caller` (nie condition na QUEUE_CALL_EVENTS)
- `searchCustomers` klamuje limit do max 100 przez `Math.min(limit, MAX_PAGE_SIZE)`, nie fallback do default

**How to apply:** Przy dalszych pracach nad customer (BE-026, FE-018) uwzględnij że encja Customer ma wszystkie pola ze schematu.
