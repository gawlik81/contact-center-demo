package com.contactcenter.api.admin;

import com.contactcenter.domain.etl.EtlSyncService;
import com.contactcenter.domain.etl.EtlTableStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BE-223: JSON contract of {@code GET /api/admin/etl/status} (liveness fields, names and types). */
@WebMvcTest(controllers = EtlStatusController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("EtlStatusController - kontrakt JSON zywotnosci (BE-223)")
class EtlStatusControllerContractTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    @Import({EtlStatusController.class, com.contactcenter.api.GlobalExceptionHandler.class})
    static class MinimalBootConfig {
        /** Real executor over a mocked provider: the test decides whether the ShedLock is free or held. */
        @org.springframework.context.annotation.Bean
        net.javacrumbs.shedlock.core.LockingTaskExecutor lockingTaskExecutor(
                net.javacrumbs.shedlock.core.LockProvider provider) {
            return new net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor(provider);
        }
    }

    @MockBean
    private net.javacrumbs.shedlock.core.LockProvider lockProvider;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EtlSyncService etlSyncService;

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    @DisplayName("stale/lastActivityAt/livenessThresholdMs: nazwy, typy, ISO-8601 UTC, null dla job nigdy nie uruchomionego")
    void exposesLivenessFields() throws Exception {
        Instant activity = Instant.parse("2026-11-12T10:15:30Z");
        when(etlSyncService.getStatus()).thenReturn(List.of(
                new EtlTableStatus("contact", Instant.parse("2026-11-12T10:00:00Z"), Instant.parse("2026-11-12T09:00:00Z"),
                        5L, "RUNNING", 3L, null, true, activity, 300_000L),
                new EtlTableStatus("agent_dim", Instant.EPOCH, null, 0L, "IDLE", null, null,
                        null, null, 300_000L)));

        mockMvc.perform(get("/api/admin/etl/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stale").value(true))
                .andExpect(jsonPath("$[0].lastActivityAt").value("2026-11-12T10:15:30Z"))
                .andExpect(jsonPath("$[0].livenessThresholdMs").value(300000))
                // existing fields untouched (backward compatibility with FE-165)
                .andExpect(jsonPath("$[0].tableName").value("contact"))
                .andExpect(jsonPath("$[0].status").value("RUNNING"))
                .andExpect(jsonPath("$[0].lagMinutes").value(3))
                .andExpect(jsonPath("$[1].stale").doesNotExist()) // global default-property-inclusion=non_null: null == field absent
                .andExpect(jsonPath("$[1].lastActivityAt").doesNotExist())
                .andExpect(jsonPath("$[1].livenessThresholdMs").value(300000));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ADMIN", "SUPERVISOR", "AGENT"})
    @DisplayName("rola inna niz SUPER_ADMIN -> 403, serwis nietkniety")
    void rejectsNonSuperAdmin(String role) throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.TestingAuthenticationToken(
                        "u", "p", "ROLE_" + role));
        try {
            mockMvc.perform(get("/api/admin/etl/status")).andExpect(status().isForbidden());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        org.mockito.Mockito.verifyNoInteractions(etlSyncService);
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    @DisplayName("POST /trigger: blokada wolna -> 200, syncTable(contact) wywołane, blokada tą samą nazwą co cykl zaplanowany, lockAtLeastFor=0")
    void triggerRunsUnderTheScheduledCycleLock() throws Exception {
        org.mockito.ArgumentCaptor<net.javacrumbs.shedlock.core.LockConfiguration> cfg =
                org.mockito.ArgumentCaptor.forClass(net.javacrumbs.shedlock.core.LockConfiguration.class);
        when(lockProvider.lock(cfg.capture()))
                .thenReturn(java.util.Optional.of(org.mockito.Mockito.mock(net.javacrumbs.shedlock.core.SimpleLock.class)));
        when(etlSyncService.getStatus()).thenReturn(List.of());

        mockMvc.perform(post("/api/admin/etl/trigger")).andExpect(status().isOk());

        org.mockito.Mockito.verify(etlSyncService).syncTable(EtlSyncService.TABLE_CONTACT);
        org.assertj.core.api.Assertions.assertThat(cfg.getValue().getName()).isEqualTo("EtlSyncServiceImpl_runContactSync");
        org.assertj.core.api.Assertions.assertThat(cfg.getValue().getLockAtLeastFor()).isEqualTo(java.time.Duration.ZERO);
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    @DisplayName("POST /trigger: blokada zajęta (cykl trwa) -> 409 ProblemDetail z code=ETL_SYNC_IN_PROGRESS, syncTable NIE wywołane")
    void triggerWhileCycleRunningReturns409() throws Exception {
        when(lockProvider.lock(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Optional.empty());

        mockMvc.perform(post("/api/admin/etl/trigger"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("ETL_SYNC_IN_PROGRESS"))
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("etl-sync-in-progress")))
                .andExpect(jsonPath("$.detail").isNotEmpty());

        org.mockito.Mockito.verify(etlSyncService, org.mockito.Mockito.never()).syncTable(org.mockito.ArgumentMatchers.anyString());
    }
}
