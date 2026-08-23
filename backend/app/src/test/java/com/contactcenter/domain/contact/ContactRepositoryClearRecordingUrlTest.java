package com.contactcenter.domain.contact;

import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test regresyjny dla {@link ContactRepository#clearRecordingUrl} — bug wykryty przy ręcznym
 * uruchomieniu {@code RecordingRetentionJob} na żywym kontenerze (EPIC-29, BE-116).
 *
 * <p><strong>Scenariusz buga:</strong> {@code clearRecordingUrl} jest wywoływana WYŁĄCZNIE z wątku
 * {@code @Scheduled} ({@code RecordingRetentionJob}), który nigdy nie przechodzi przez
 * {@code TenantFilter} (brak JWT) — {@link TenantContext} (ThreadLocal) nie jest ustawiany
 * automatycznie. Metoda woła {@code TenantAwareRepository#assertSameTenant}, które BEZWARUNKOWO
 * czyta {@code TenantContext.getTenantId()}. Przed naprawą buga w {@code RecordingRetentionJob}
 * (patrz jego javadoc) ten odczyt rzucał {@link IllegalStateException}: plik nagrania był
 * poprawnie usuwany z S3, ale {@code recording_url} w DB NIGDY nie było czyszczone — ten sam
 * kontakt wracał jako "wygasły" przy KAŻDYM kolejnym przebiegu jobu, w nieskończoność.
 *
 * <p>Repozytorium jest PRAWDZIWE (nie mock) — mockowane są tylko {@link JdbcTemplate}/
 * {@link EntityManager} (brak H2/Testcontainers dla testów jednostkowych repozytoriów w tym
 * projekcie — wzorzec z {@code ContactRepositoryPurgeTest}/{@code TenantRetentionPolicyRepositoryTest}).
 * Dzięki temu test faktycznie przechodzi przez {@code assertSameTenant}, czego mockowany
 * {@code ContactService} w {@code RecordingRetentionJobTest} nigdy nie mógłby wychwycić.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ContactRepository.clearRecordingUrl – wymaganie TenantContext dla wątku schedulera (regresja BE-116)")
class ContactRepositoryClearRecordingUrlTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CONTACT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query rlsQuery;

    private ContactRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ContactRepository(jdbcTemplate, new com.fasterxml.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(repository, "em", entityManager);

        when(entityManager.createNativeQuery(argThat(sql -> sql != null && sql.contains("set_tenant_context"))))
                .thenReturn(rlsQuery);
        when(rlsQuery.setParameter(anyString(), any())).thenReturn(rlsQuery);
    }

    @AfterEach
    void tearDown() {
        // Izolacja testów – wątek testowy jest reużywany między metodami testowymi w tej klasie
        // (analogicznie do wątku puli schedulera w produkcji), TenantContext MUSI zostać wyczyszczony.
        TenantContext.clear();
    }

    @Test
    @DisplayName("bez TenantContext (symulacja wątku schedulera PRZED naprawą BE-116) rzuca ISE, recording_url NIE jest czyszczone")
    void withoutTenantContext_throwsIllegalStateException_neverTouchesDb() {
        // Wątek testu startuje bez kontekstu – dokładnie jak wątek @Scheduled przed naprawą buga.
        assertThat(TenantContext.isSet()).isFalse();

        assertThatThrownBy(() -> repository.clearRecordingUrl(CONTACT_ID, TENANT_A))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TenantContext");

        // Krytyczne dla zrozumienia buga: S3 (poza tym testem) już usunąłby plik, ale bez tej
        // naprawy DB NIGDY nie zostałaby zaktualizowana – żadnej interakcji z jdbcTemplate.
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("z TenantContext ustawionym na tenantId (naprawiony job) czyści recording_url w DB")
    void withTenantContextSet_clearsRecordingUrlInDb() {
        // Symuluje naprawiony RecordingRetentionJob.processRetentionForTenant:
        // TenantContext.setTenantId(tenantId) wywołane PRZED clearRecordingUrl.
        TenantContext.setTenantId(TENANT_A);

        repository.clearRecordingUrl(CONTACT_ID, TENANT_A);

        verify(jdbcTemplate).update(
                contains("UPDATE contact SET recording_url = NULL"),
                eq(CONTACT_ID),
                eq(TENANT_A)
        );
    }

    @Test
    @DisplayName("TenantContext ustawiony na INNY tenant niż parametr -> CrossTenantAccessException, brak zapisu (obrona defensywna)")
    void tenantContextMismatch_throwsCrossTenantAccessException() {
        TenantContext.setTenantId(TENANT_B);

        assertThatThrownBy(() -> repository.clearRecordingUrl(CONTACT_ID, TENANT_A))
                .isInstanceOf(CrossTenantAccessException.class);

        verifyNoInteractions(jdbcTemplate);
    }
}
