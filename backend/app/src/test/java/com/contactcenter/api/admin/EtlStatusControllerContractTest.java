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
    }

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
}
