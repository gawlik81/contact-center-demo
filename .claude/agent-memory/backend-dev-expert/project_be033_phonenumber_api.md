---
name: BE-033 PhoneNumber CRUD API
description: Implementacja CRUD numerów telefonów E.164 per tenant z soft delete i blokadą przez reguły routingu
type: project
---

BE-033 zaimplementowano jako standardowy CRUD wzorowany na Campaign/Customer. Tabela `phone_number` z V039.

**Pliki:**
- `domain/model/PhoneNumber.java` – encja, `@UuidGenerator`, `@PrePersist`/`@PreUpdate`, pole `deleted` (soft delete)
- `domain/repository/PhoneNumberRepository.java` – extends TenantAwareRepository, native SQL
- `domain/repository/PhoneRoutingRuleRepository.java` – stub repozytorium (pełne CRUD w BE-034), tylko `existsActiveRulesByPhoneNumberId`
- `domain/service/PhoneNumberService.java` – walidacja E.164 Pattern, duplikat → ConflictException, soft delete z guard
- `api/phonenumber/dto/` – `CreatePhoneNumberRequest` (record z @Pattern E.164), `UpdatePhoneNumberRequest`, `PhoneNumberResponse`
- `api/phonenumber/PhoneNumberController.java` – `@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")` na poziomie klasy

**Kluczowe decyzje:**
- E.164 regex: `^\+[1-9]\d{6,14}$` – walidacja dwupoziomowa (Bean Validation + serwis)
- Soft delete blokowany gdy `phone_routing_rule.is_active = true` dla tego numeru
- Endpoint nie jest publiczny – nie wymaga zmian w SecurityConfig/TenantFilter

**Why:** tabela V039 już zaaplikowana; PhoneRoutingRuleRepository jako stub pozwala BE-034 rozszerzyć go niezależnie.

**How to apply:** przy BE-034 rozszerzaj `PhoneRoutingRuleRepository` – stub jest już zarejestrowany jako `@Repository`.
