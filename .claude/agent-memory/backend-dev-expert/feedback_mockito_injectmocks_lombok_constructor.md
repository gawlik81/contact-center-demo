---
name: Mockito @InjectMocks z Lombok @RequiredArgsConstructor – pominięte pola non-final
description: Mockito 5 używa konstruktora Lombok do @InjectMocks i pomija wstrzykiwanie pól non-final przez setter/field; fix: ręczne wywołanie settera w @BeforeEach
type: feedback
---

Gdy klasa używa `@RequiredArgsConstructor` (Lombok), Mockito 5 `@InjectMocks` wybiera konstruktor do wstrzyknięcia. Pola nieoznaczone `final` (więc nieuwzględnione w konstruktorze) **nie są wstrzykiwane** przez Mockito – pozostają `null`.

**Why:** Mockito preferuje wstrzyknięcie konstruktorem nad setter/field injection. Lombok generuje konstruktor tylko dla pól `final`, więc pola non-final nie są wstrzykiwane automatycznie.

**How to apply:** Gdy serwis ma pole non-final wstrzykiwane przez setter (`@Autowired @Lazy`), w teście w `@BeforeEach` jawnie wywołaj setter po `@InjectMocks`:
```java
@BeforeEach
void setUp() {
    // ... konfiguracja testów ...
    tenantService.setAdminMetricsService(adminMetricsService); // ręczne wstrzyknięcie
}
```

Przykład z projektu: `TenantService.adminMetricsService` jest non-final (setter injection z `@Lazy`), `TenantServiceTest` musi ręcznie wywołać `tenantService.setAdminMetricsService(adminMetricsService)` w `@BeforeEach`.
