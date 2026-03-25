---
name: BE-015 Email Adapter
description: Implementacja adaptera email – IMAP polling + SMTP wysyłka; pakiet domain.email; schemat z V010
type: project
---

Implementacja BE-015 Email Adapter w pakiecie `com.contactcenter.domain.email`.

**Why:** Funkcjonalność odbioru (IMAP) i wysyłki (SMTP) email per-tenant w platformie Contact Center.

**How to apply:** Przy zmianach w module email – patrz na istniejące wzorce w tym pakiecie.

## Kluczowe decyzje

- Schemat DB z **V010__create_email_social.sql** – tabele: `email_message`, `email_routing_rule`, `email_template`
- `email_message.message_id` = UUID PK; nagłówek RFC 2822 = kolumna `message_id_header` (VARCHAR 255)
- `to_address`, `cc_address`, `bcc_address` to TEXT (nie JSONB) – lista adresów rozdzielona przecinkami
- Brak `is_deleted` i `updated_at` w tabeli `email_message` – wiadomości są immutable
- `contact_id` jest **nullable** w encji Java (NOT NULL w DB – egzekwowane przez aplikację)
- Konfiguracja IMAP/SMTP per-tenant w `Tenant.config` JSONB, klucze z prefiksem `email_*`
- Hasła szyfrowane **AES-256-GCM** w `EmailEncryptionService`; klucz z `email.encryption-key` (ENV: `EMAIL_ENCRYPTION_KEY`)
- `@ConditionalOnProperty(name = "email.enabled", havingValue = "true")` na `EmailPollingService`
- Polling `@Scheduled(fixedDelayString = "${email.poll-delay-ms:60000}")` – fixedDelay, nie fixedRate

## Pliki

- `domain/email/EmailAccountConfig.java` – record konfiguracji (nie JPA), fromTenantConfig/toTenantConfig
- `domain/email/EmailMessage.java` – encja JPA tabeli email_message
- `domain/email/EmailMessageRepository.java` – extends TenantAwareRepository
- `domain/email/EmailRoutingRule.java` – encja JPA tabeli email_routing_rule
- `domain/email/EmailRoutingRuleRepository.java` – extends TenantAwareRepository
- `domain/email/EmailEncryptionService.java` – AES/GCM/NoPadding, IV prepended
- `domain/email/EmailPollingService.java` – @Scheduled IMAP polling, TenantContext.snapshot/restore per tenant
- `domain/email/EmailRoutingService.java` – ewaluacja reguł z email_routing_rule + fallback na email_default_queue_id
- `domain/email/EmailSendService.java` – SMTP wysyłka przez Jakarta Mail, In-Reply-To/References headers
- `domain/email/EmailEventPublisher.java` – eventy email.received/queued/sent/assigned na cc.events
- `api/email/EmailController.java` – REST /api/email/messages, /threads, /reply, /config, /config/test
- `api/email/Email*Request/Response.java` – DTOs

## Wzorzec pollingu per-tenant (WAŻNE)

```java
TenantContext.Snapshot snapshot = new TenantContext.Snapshot(tenant.getId(), null, tenant.getName(), "SYSTEM");
try {
    TenantContext.restore(snapshot);
    pollTenantInbox(tenant);
} catch (Exception e) {
    log.error("...", e); // błąd jednego tenanta nie przerywa pętli
} finally {
    TenantContext.clear();
}
```

## RabbitMQ

- Dodana kolejka `cc.queue.email-events` (stała `QUEUE_EMAIL_EVENTS`) z binding `email.#`

## Maven

- Dodana zależność `org.eclipse.angus:angus-mail` (wersja zarządzana przez Spring Boot BOM)

## Testy (26 testów)

- `EmailEncryptionServiceTest` – 11 testów (round-trip, IV losowość, błędy)
- `EmailRoutingServiceTest` – 11 testów (matchesRule, route, fallback)
- `EmailPollingServiceTest` – 4 testy (deduplicacja, konfiguracja); @MockitoSettings(LENIENT) bo spy+wiele stubów

## Pułapka: email_routing_rule.isActive w JPA

Pole `isActive` z `@Column(name = "is_active")` w Lombok @Builder – gdy nazwa pola zaczyna się od `is`, Lombok generuje getter `isActive()` a JPA Hibernate może szukać `active` zamiast `isActive`. Jeśli będą problemy – rozważ zmianę nazwy na `active` lub użycie `@Getter` ręcznego.
