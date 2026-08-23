package com.contactcenter.domain.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Testy jednostkowe dla {@link RetentionEvaluationJob} (EPIC-29, BE-112).
 *
 * <p>Po refaktorze "wydziel serwis" (rozszerzenie BE-112/BE-118 — ręczne przeliczenie na żądanie
 * administratora) ta klasa jest cienkim wrapperem {@code @Scheduled} delegującym do
 * {@link RetentionEvaluationService#runForAllActiveTenants()}. Cała logika ewaluacji (algorytm
 * partition-aware, zarządzanie {@code TenantContext}, auto-purge) oraz jej testy regresyjne
 * (w tym {@code TenantContextRegression}) żyją teraz w {@link RetentionEvaluationServiceImplTest}
 * — patrz Javadoc tamtej klasy.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RetentionEvaluationJob – cienki wrapper @Scheduled (BE-112)")
class RetentionEvaluationJobTest {

    @Mock
    private RetentionEvaluationService evaluationService;

    @Test
    @DisplayName("runEvaluationJob deleguje do RetentionEvaluationService.runForAllActiveTenants() dokładnie raz, bez innych interakcji")
    void runEvaluationJob_delegatesToServiceExactlyOnce() {
        RetentionEvaluationJob job = new RetentionEvaluationJob(evaluationService);

        job.runEvaluationJob();

        verify(evaluationService, times(1)).runForAllActiveTenants();
        verifyNoMoreInteractions(evaluationService);
    }
}
