package com.contactcenter.domain.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Testy jednostkowe {@link CampaignArchiveJob} (BE-120): flaga, odporność na wyjątki. */
@DisplayName("CampaignArchiveJob - flaga i odporność (BE-120)")
class CampaignArchiveJobTest {

    private final CampaignArchiveJobRepository repository = mock(CampaignArchiveJobRepository.class);

    @Test
    @DisplayName("flaga false: zero interakcji z repozytorium (zero wywołań SQL)")
    void disabled_doesNotCallRepository() {
        new CampaignArchiveJob(repository, false).run();

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("flaga true: woła archiwizację dokładnie raz")
    void enabled_callsRepositoryOnce() {
        when(repository.archiveCompletedCampaigns()).thenReturn(3L);

        new CampaignArchiveJob(repository, true).run();

        verify(repository, times(1)).archiveCompletedCampaigns();
    }

    @Test
    @DisplayName("wyjątek z repozytorium nie jest propagowany (scheduler przeżyje)")
    void enabled_repositoryFailure_isSwallowed() {
        when(repository.archiveCompletedCampaigns())
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatNoException().isThrownBy(() -> new CampaignArchiveJob(repository, true).run());
        verify(repository, times(1)).archiveCompletedCampaigns();
    }
}
