package com.contactcenter.domain.user;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * Cykliczne czyszczenie tabeli {@code refresh_token} (BE-122) - zastępuje nieaktywną funkcję SQL
 * {@code cleanup_expired_refresh_tokens()} (wymagała pg_cron, niedostępnego w obrazie
 * postgres:16-alpine).
 *
 * <p><strong>Brak TenantContext (WP-2):</strong> tabela {@code refresh_token} jest globalna (bez RLS),
 * a job działa na wątku schedulera bez {@code TenantContext}. Jedno zapytanie obejmuje wszystkich
 * tenantów oraz tokeny SUPER_ADMIN ({@code tenant_id IS NULL}); nie ma pętli per tenant, więc nie
 * ustawiamy ani nie czyścimy kontekstu.
 *
 * <p><strong>Semantyka cutoff:</strong> {@code cutoff = now - grace-days}; usuwane są tokeny wygasłe
 * przed cutoff oraz unieważnione wystawione przed cutoff (patrz
 * {@link RefreshTokenRepository#deleteExpiredAndRevoked}). Natychmiastowe kasowanie wszystkich
 * unieważnionych gubiłoby ślad replay w {@code AuthServiceImpl#refresh} (stary token po rotacji ->
 * "unieważniony" zamiast "nieznany"). Po upływie karencji token znika i zachowuje się jak
 * nieistniejący (401 w obu przypadkach). Zastrzeżenie: ślad replay zostaje zachowany tylko gdy
 * {@code grace-days} >= TTL refresh tokenu ({@code jwt.refresh-token-ttl-seconds}); przy mniejszej
 * wartości unieważnione, a jeszcze niewygasłe tokeny starsze niż karencja znikają od razu.
 *
 * <p><strong>Wolumen:</strong> ok. 1,3 tys. wierszy na local-demo (tabela zwykła, niepartycjonowana),
 * dzienny DELETE to pojedyncza krótka transakcja - batching po PK nie jest potrzebny; do rozważenia
 * dopiero przy rzędach wielkości setek tysięcy wierszy dziennie.
 *
 * <p>Transakcja przez {@link TransactionTemplate} (zamiast {@code @Transactional}), aby wyjątek
 * został złapany i zalogowany wewnątrz joba bez {@code UnexpectedRollbackException} i bez
 * self-invocation.
 */
@Slf4j
@Component
class RefreshTokenCleanupJob {

    static final int DEFAULT_GRACE_DAYS = 7;

    private final RefreshTokenRepository refreshTokenRepository;
    private final TransactionTemplate transactionTemplate;
    private final int graceDays;

    RefreshTokenCleanupJob(RefreshTokenRepository refreshTokenRepository,
                           PlatformTransactionManager transactionManager,
                           @Value("${auth.refresh-token-cleanup.grace-days:" + DEFAULT_GRACE_DAYS + "}")
                           int graceDays) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        if (graceDays < 0) {
            log.warn("[RefreshTokenCleanup] Niepoprawne auth.refresh-token-cleanup.grace-days={} "
                    + "(< 0) - używam domyślnej wartości {}", graceDays, DEFAULT_GRACE_DAYS);
            this.graceDays = DEFAULT_GRACE_DAYS;
        } else {
            this.graceDays = graceDays;
        }
    }

    /** Punkt wejścia schedulera; nigdy nie rzuca wyjątku (błąd jest logowany, kolejny przebieg jutro). */
    @Scheduled(cron = "${auth.refresh-token-cleanup.cron:0 30 3 * * *}", zone = "UTC")
    void run() {
        try {
            Instant cutoff = Instant.now().minus(Duration.ofDays(graceDays));
            Integer deleted = transactionTemplate.execute(
                    status -> refreshTokenRepository.deleteExpiredAndRevoked(cutoff));
            log.info("[RefreshTokenCleanup] Usunięto {} refresh tokenów (cutoff={}, graceDays={})",
                    deleted, cutoff, graceDays);
        } catch (RuntimeException e) {
            log.error("[RefreshTokenCleanup] Czyszczenie refresh tokenów nie powiodło się", e);
        }
    }
}
