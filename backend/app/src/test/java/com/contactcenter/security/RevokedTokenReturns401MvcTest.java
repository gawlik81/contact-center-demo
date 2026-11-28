package com.contactcenter.security;

import com.contactcenter.api.telephony.AgentSelfController;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.tenant.TenantStatusGate;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.security.JwtParser.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Token poprawnie podpisany, ale uniewazniony (blacklista, znacznik revoked-since per user / per sid, tenant
 * nieaktywny, uzytkownik nieistniejacy) musi dawac 401 (ProblemDetail), nie domyslne 403 Spring Security.
 * Uwierzytelniony bez roli nadal dostaje 403. PRAWDZIWY lancuch: SecurityConfig + JwtAuthFilter + TenantFilter.
 */
@WebMvcTest(controllers = AgentSelfController.class)
@Import({AgentSelfController.class, SecurityConfig.class, PasswordEncoderConfig.class, JwtAuthFilter.class,
        TenantFilter.class, UserDetailsServiceImpl.class, com.contactcenter.api.GlobalExceptionHandler.class})
@DisplayName("Uniewazniony token -> 401 (authenticationEntryPoint), brak uprawnien -> 403")
class RevokedTokenReturns401MvcTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class MinimalBootConfig {
    }

    private static final String UNIFORM_DETAIL = "Brak uwierzytelnienia lub token nieważny. Zaloguj się ponownie.";
    private static final String URL = "/api/agent/me/active-contacts";
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final Instant IAT = Instant.parse("2026-11-26T10:00:00Z");

    @Autowired private MockMvc mockMvc;

    @MockBean private JwtParser jwtParser;
    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private TokenRevocationService tokenRevocationService;
    @MockBean private TenantStatusGate tenantStatusGate;
    @MockBean private UserService userService;
    @MockBean private UserDetailsServiceImpl userDetailsService;
    @MockBean private ContactService contactService;
    @MockBean private QueueService queueService;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private String bearer(String role) {
        // SUPER_ADMIN w prawdziwym tokenie nie ma tenant_id (null), pozostale role maja
        return bearer(role, "SUPER_ADMIN".equals(role) ? null : TENANT);
    }

    private String bearer(String role, UUID tenantId) {
        when(jwtParser.parse("tok")).thenReturn(new JwtClaims(tenantId, USER, role, "u@example.com",
                IAT.plusSeconds(900), IAT, SID));
        when(tokenBlacklistService.blacklistKey("tok")).thenReturn("bl:tok");
        when(userDetailsService.loadUserByUsername(anyString())).thenReturn(
                new User("u@example.com", "x", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        return "Bearer tok";
    }

    private void expect401(String header) throws Exception {
        mockMvc.perform(get(URL).header("Authorization", header))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://contactcenter.io/errors/unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.detail").value(UNIFORM_DETAIL));
        verifyNoInteractions(contactService);
    }

    @Test
    @DisplayName("kontrola pozytywna: wazny AGENT -> 200 (mocki ustawione poprawnie)")
    void validAgent_returns200() throws Exception {
        String h = bearer("AGENT");
        mockMvc.perform(get(URL).header("Authorization", h)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("brak naglowka Authorization -> 401 (TenantFilter) z WWW-Authenticate")
    void missingAuthorization_returns401() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://contactcenter.io/errors/unauthorized"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        verifyNoInteractions(contactService);
    }

    @Test
    @DisplayName("token niepoprawny/wygasly (JwtValidationException) -> 401")
    void invalidToken_returns401() throws Exception {
        when(jwtParser.parse("tok")).thenThrow(new JwtParser.JwtValidationException("Token JWT wygasl"));
        mockMvc.perform(get(URL).header("Authorization", "Bearer tok"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://contactcenter.io/errors/unauthorized"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        verifyNoInteractions(contactService);
    }

    @Test
    @DisplayName("awaria Redis w isBlacklistedOrRevoked -> 401 (fail-closed)")
    void redisFailure_failsClosedWith401() throws Exception {
        String h = bearer("AGENT");
        when(tokenRevocationService.isBlacklistedOrRevoked(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("redis down"));
        expect401(h);
    }

    @Test
    @DisplayName("unieważniony token na sciezce permitAll (/api/auth/login, /ws/info) nie daje 401")
    void revokedTokenOnPublicPath_isNot401() throws Exception {
        String h = bearer("AGENT");
        when(tokenRevocationService.isBlacklistedOrRevoked(any(), any(), any(), any())).thenReturn(true);
        for (String path : List.of("/api/auth/login", "/ws/info")) {
            int st = mockMvc.perform(get(path).header("Authorization", h)).andReturn().getResponse().getStatus();
            org.assertj.core.api.Assertions.assertThat(st).as(path).isNotEqualTo(401).isNotEqualTo(403);
        }
    }

    @Test
    @DisplayName("dispatch ERROR (/error) po bledzie poza MVC nie zamienia sie w 401/403 - anonim")
    void errorDispatch_anonymous_isNotRewrittenTo401() throws Exception {
        int st = mockMvc.perform(errorDispatch()).andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(st).isNotEqualTo(401).isNotEqualTo(403);
    }

    @Test
    @DisplayName("dispatch ERROR (/error) z naglowkiem uniewaznionego tokenu nie daje 401")
    void errorDispatch_withRevokedToken_isNotRewrittenTo401() throws Exception {
        String h = bearer("AGENT");
        when(tokenRevocationService.isBlacklistedOrRevoked(any(), any(), any(), any())).thenReturn(true);
        int st = mockMvc.perform(errorDispatch().header("Authorization", h)).andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(st).isNotEqualTo(401).isNotEqualTo(403);
    }

    /** Odtwarza forward kontenera na /error po wyjatku poza MVC (status 500). */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder errorDispatch() {
        return get("/error")
                .with(r -> {
                    r.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
                    r.setAttribute("jakarta.servlet.error.status_code", 500);
                    r.setAttribute("jakarta.servlet.error.request_uri", URL);
                    return r;
                });
    }

    @Test
    @DisplayName("token na blackliscie (logout) -> 401")
    void blacklistedToken_returns401() throws Exception {
        String h = bearer("AGENT");
        when(tokenRevocationService.isBlacklistedOrRevoked(eq("bl:tok"), any(), any(), any())).thenReturn(true);
        expect401(h);
    }

    @Test
    @DisplayName("iat < znacznik revoked-since (user / sid) -> 401")
    void revokedSince_returns401() throws Exception {
        String h = bearer("AGENT");
        when(tokenRevocationService.isBlacklistedOrRevoked(any(), eq(USER), eq(SID), eq(IAT))).thenReturn(true);
        expect401(h);
    }

    @Test
    @DisplayName("tenant INACTIVE (TenantStatusGate) -> 401")
    void inactiveTenant_returns401() throws Exception {
        String h = bearer("AGENT");
        when(tenantStatusGate.isBlocked(TENANT)).thenReturn(true);
        expect401(h);
    }

    @Test
    @DisplayName("uzytkownik nieaktywny/usuniety (loadUserByUsername rzuca) -> 401")
    void unknownUser_returns401() throws Exception {
        String h = bearer("AGENT");
        when(userDetailsService.loadUserByUsername(anyString()))
                .thenThrow(new org.springframework.security.core.userdetails.UsernameNotFoundException("gone"));
        expect401(h);
    }

    @Test
    @DisplayName("uwierzytelniony bez wymaganej roli (SUPER_ADMIN na endpoint agenta) nadal -> 403")
    void authenticatedWithoutRole_stillReturns403() throws Exception {
        mockMvc.perform(get(URL).header("Authorization", bearer("SUPER_ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(contactService);
    }

    @Test
    @DisplayName("preflight CORS (OPTIONS) nie jest odrzucany 401")
    void corsPreflight_notRejected() throws Exception {
        mockMvc.perform(options(URL)
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk());
    }
}
