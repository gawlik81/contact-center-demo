package com.contactcenter.security;

import com.contactcenter.domain.tenant.TenantStatusGate;
import com.contactcenter.security.JwtParser.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("JwtAuthFilter (BE-249): znacznik 'uniewaznione od' i bramka statusu tenanta na sciezce HTTP")
class JwtAuthFilterRevocationTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID SID = UUID.randomUUID();
    private static final Instant IAT = Instant.parse("2026-11-26T10:00:00Z");

    private JwtParser jwtParser;
    private TokenBlacklistService blacklist;
    private UserDetailsServiceImpl userDetailsService;
    private TokenRevocationService revocation;
    private TenantStatusGate gate;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtParser = mock(JwtParser.class);
        blacklist = mock(TokenBlacklistService.class);
        userDetailsService = mock(UserDetailsServiceImpl.class);
        revocation = mock(TokenRevocationService.class);
        gate = mock(TenantStatusGate.class);
        filter = new JwtAuthFilter(jwtParser, blacklist, userDetailsService, revocation, gate);
        when(jwtParser.parse("tok")).thenReturn(new JwtClaims(TENANT, USER, "AGENT", "a@t.com",
                IAT.plusSeconds(900), IAT, SID));
        UserDetails details = mock(UserDetails.class);
        when(details.getAuthorities()).thenReturn(List.of());
        when(userDetailsService.loadUserByUsername(anyString())).thenReturn(details);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockFilterChain run() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agents/me");
        request.addHeader("Authorization", "Bearer tok");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    @Test
    @DisplayName("nieuniewazniony token z aktywnego tenanta uwierzytelnia zadanie")
    void validTokenAuthenticates() throws Exception {
        run();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(revocation).isBlacklistedOrRevoked(any(), org.mockito.ArgumentMatchers.eq(USER), org.mockito.ArgumentMatchers.eq(SID),
                org.mockito.ArgumentMatchers.eq(IAT));
        verify(gate).isBlocked(TENANT);
    }

    @Test
    @DisplayName("token wystawiony przed znacznikiem 'uniewaznione od': brak uwierzytelnienia (-> 401), UserDetails nie ladowane")
    void tokenBeforeMarkerIsNotAuthenticated() throws Exception {
        when(revocation.isBlacklistedOrRevoked(any(), org.mockito.ArgumentMatchers.eq(USER),
                org.mockito.ArgumentMatchers.eq(SID), org.mockito.ArgumentMatchers.eq(IAT))).thenReturn(true);

        MockFilterChain chain = run();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).as("lancuch filtrow kontynuowany (Spring Security odpowie 401)").isNotNull();
        verify(userDetailsService, never()).loadUserByUsername(anyString());
    }

    @Test
    @DisplayName("W2/N2: blacklista i znacznik w JEDNYM wywolaniu (jeden MGET); awaria Redis = zadanie bez uwierzytelnienia (401)")
    void blacklistAndMarkerShareOneCallAndRedisFailureIsFailClosed() throws Exception {
        when(blacklist.blacklistKey("tok")).thenReturn("jwt:blacklist:abc");
        when(revocation.isBlacklistedOrRevoked(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("redis down"));

        run();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(revocation).isBlacklistedOrRevoked(org.mockito.ArgumentMatchers.eq("jwt:blacklist:abc"), any(), any(), any());
        verify(blacklist, never()).isBlacklisted(anyString()); // no second round trip
    }

    @Test
    @DisplayName("tenant nieaktywny (bramka): brak uwierzytelnienia; SUPER_ADMIN (bez tenanta) nie jest blokowany")
    void inactiveTenantIsRejectedButSuperAdminIsNot() throws Exception {
        when(gate.isBlocked(TENANT)).thenReturn(true);
        run();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        SecurityContextHolder.clearContext();
        when(jwtParser.parse("tok")).thenReturn(new JwtClaims(null, USER, "SUPER_ADMIN", "root@t.com",
                IAT.plusSeconds(900), IAT, null));
        when(gate.isBlocked(null)).thenReturn(false);
        run();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("awaria bazy w bramce statusu: brak uwierzytelnienia (fail-closed jak blacklista)")
    void gateFailureFailsClosed() throws Exception {
        when(gate.isBlocked(TENANT)).thenThrow(new IllegalStateException("db down"));

        run();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
