---
name: project_admin_metrics_contacts_by_channel
description: GET /api/admin/metrics/contacts-by-channel – macierz kontaktów per tenant/kanał; kanoniczna definicja "kontakt dzisiaj" w AdminMetrics
metadata:
  type: project
---

Dodano `GET /api/admin/metrics/contacts-by-channel` (Super Admin) zwracający `ContactChannelMatrix`
(kanały × tenanci, zero-fill brakujących kanałów, `total` per tenant).

**Zweryfikowana, kanoniczna definicja "kontakt dzisiaj"** używana we WSZYSTKICH agregacjach
`AdminMetricsServiceImpl` (`getGlobalMetrics`, `getUsageMetrics`, teraz też `getContactChannelMatrix`) –
źródło: `ContactRepository.getDailyContactAggregate()`:
```sql
status IN ('COMPLETED', 'TRANSFERRED') AND duration_seconds IS NOT NULL
AND started_at >= dayStart AND started_at < dayEnd   -- java.sql.Date.valueOf(date)/date.plusDays(1)
```
Uwaga: to NIE jest `('COMPLETED','ABANDONED')` – ABANDONED nigdy nie wlicza się do "kontakt dzisiaj"
w tym serwisie. Każda nowa agregacja "dzisiaj" w AdminMetrics musi używać dokładnie tych warunków,
inaczej liczniki (np. suma per kanał vs `TenantMetrics.contactsToday`) się rozjadą.

**Wzorzec implementacji** (nowa metoda `ContactRepository.getDailyContactCountsByChannel(tenantId, date)`):
natywny SQL identyczny do `getDailyContactAggregate` + `GROUP BY channel`, zwraca `List<Object[]>`
`[channel(String), count(Number)]` – TYLKO kanały z >=1 kontaktem (zero-fill po stronie serwisu).

Kolumna `contact.channel` to VARCHAR z CHECK constraint (V025), zamknięty zbiór 5 wartości:
`PHONE, EMAIL, SOCIAL_FACEBOOK, SOCIAL_INSTAGRAM, SOCIAL_WHATSAPP` – brak enuma Java, stała
`AdminMetricsServiceImpl.CONTACT_CHANNELS` (`List.of(...)`) definiuje kanoniczną kolejność
kolumn macierzy i klucze do zero-fill (`LinkedHashMap` żeby zachować kolejność w JSON).

Cache: użyto ISTNIEJĄCEGO `RedisConfig.CacheNames.ADMIN_METRICS_USAGE` (TTL 5 min) z osobnym
kluczem `'channel-breakdown'` – NIE tworzono nowego cache bucketu, bo to ta sama kategoria danych
("dzisiejsze wykorzystanie platformy") co `getUsageMetrics()` (klucz `'usage'`).

Pliki: `AdminMetricsService(Impl).java`, `AdminMetricsController.java`,
`ContactRepository/Service(Impl).java`, DTO `ContactChannelMatrix`/`TenantChannelRow`
(`api/admin/dto/`), testy w `AdminMetricsServiceImplTest$GetContactChannelMatrix`.
