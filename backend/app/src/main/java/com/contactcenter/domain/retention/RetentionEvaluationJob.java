package com.contactcenter.domain.retention;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron job (codziennie, domyślnie 01:00 UTC, nadpisywalne przez {@code retention.evaluation-cron})
 * liczący, ile rekordów kwalifikuje się do usunięcia per tenant/kategoria retencji (EPIC-29,
 * BE-112).
 *
 * <p><strong>Cienki wrapper od refaktoru "wydziel serwis" (rozszerzenie BE-112/BE-118, ręczne
 * przeliczenie na żądanie administratora):</strong> cała logika ewaluacji (algorytm
 * partition-aware, zarządzanie {@link com.contactcenter.security.TenantContext}, auto-purge)
 * została przeniesiona do {@link RetentionEvaluationServiceImpl} — patrz Javadoc
 * {@link RetentionEvaluationService} po pełny opis. Ta klasa jest wyłącznie triggerem
 * {@code @Scheduled} delegującym do {@link RetentionEvaluationService#runForAllActiveTenants()},
 * dokładnie tym samym wzorcem "cienki `@Component` + logika w `@Service`" co reszta modułu
 * {@code domain.retention} ({@code RetentionPurgeService}/{@code RetentionPurgeServiceImpl}).
 *
 * <p>REST API ({@code POST /api/tenants/{tenantId}/retention/recompute}, {@code RetentionController})
 * woła TĘ SAMĄ logikę ewaluacji przez {@link RetentionEvaluationService#runForTenant(java.util.UUID)}
 * bezpośrednio, z pominięciem tej klasy — różnica: jeden tenant zamiast wszystkich, auto-purge
 * zawsze wyłączony.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RetentionEvaluationJob {

    private final RetentionEvaluationService evaluationService;

    @Scheduled(cron = "${retention.evaluation-cron:0 0 1 * * *}", zone = "UTC")
    public void runEvaluationJob() {
        log.debug("[RetentionEvaluationJob] Trigger schedulera — delegacja do RetentionEvaluationService.runForAllActiveTenants()");
        evaluationService.runForAllActiveTenants();
    }
}
