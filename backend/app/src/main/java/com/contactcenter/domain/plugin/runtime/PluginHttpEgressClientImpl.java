package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.pluginsdk.HttpEgressClient;
import com.contactcenter.pluginsdk.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Implementacja {@link HttpEgressClient} z wymuszeniem allow-list per host
 * (ARCHITECTURE.md §11.6: {@code "HttpEgressClient enforces the manifest's declared egress
 * hosts at the SDK implementation layer (host allow-list checked before every call)"}).
 *
 * <p>Allow-list pochodzi z {@code grantedPermissions} tej instalacji — uprawnienia w formie
 * {@code http:egress:<host>} (kontrakt zdefiniowany i zwalidowany w BE-098,
 * {@code PluginPermission}). Host poza allow-listą skutkuje {@link SecurityException} (zgodnie
 * z kontraktem SDK — "typically surfaced as a RuntimeException").
 *
 * <p><strong>Zakres tego ticketu (BE-101):</strong> circuit breaker per
 * {@code (tenant_id, plugin_key, host)} (ARCHITECTURE.md §11.6, ostatni akapit) jest
 * zadeklarowany jako wymóg SDK, ale jego implementacja (stan, okno czasowe, próg) jest
 * zakresem {@code PluginInvocationExecutor}/BE-102 — tutaj wymuszamy tylko statyczną
 * allow-listę hostów i prosty timeout per żądanie, bez logiki circuit breakera.
 */
@Slf4j
final class PluginHttpEgressClientImpl implements HttpEgressClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String EGRESS_PERMISSION_PREFIX = "http:egress:";

    private final HttpClient httpClient;
    private final Set<String> allowedHosts;
    private final UUID tenantId;
    private final String pluginKey;

    PluginHttpEgressClientImpl(List<String> grantedPermissions, UUID tenantId, String pluginKey) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
        this.allowedHosts = extractAllowedHosts(grantedPermissions);
        this.tenantId = tenantId;
        this.pluginKey = pluginKey;
    }

    @Override
    public HttpResponse get(String url, Map<String, String> headers) {
        assertHostAllowed(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder(toUri(url))
                .timeout(REQUEST_TIMEOUT)
                .GET();
        headers.forEach(builder::header);
        return execute(builder.build());
    }

    @Override
    public HttpResponse post(String url, Map<String, String> headers, byte[] body) {
        assertHostAllowed(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder(toUri(url))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body != null ? body : new byte[0]));
        headers.forEach(builder::header);
        return execute(builder.build());
    }

    private HttpResponse execute(HttpRequest request) {
        try {
            java.net.http.HttpResponse<byte[]> response =
                    httpClient.send(request, BodyHandlers.ofByteArray());
            Map<String, String> responseHeaders = response.headers().map().entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            e -> String.join(",", e.getValue())));
            return new HttpResponse(response.statusCode(), responseHeaders, response.body());
        } catch (Exception e) {
            log.warn("[PluginEgress] Żądanie HTTP nie powiodło się: tenant={}, plugin={}, url={}, error={}",
                    tenantId, pluginKey, request.uri(), e.getMessage());
            throw new RuntimeException("Żądanie HTTP egress pluginu nie powiodło się: " + e.getMessage(), e);
        }
    }

    private void assertHostAllowed(String url) {
        String host = toUri(url).getHost();
        if (host == null || !allowedHosts.contains(host)) {
            log.warn("[PluginEgress][Security] Odmowa egress poza allow-listą: tenant={}, plugin={}, "
                            + "host={}, allowedHosts={}",
                    tenantId, pluginKey, host, allowedHosts);
            throw new SecurityException(
                    "Host '" + host + "' nie jest w allow-liście egress tej instalacji pluginu");
        }
    }

    private static URI toUri(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL nieprawidłowy: " + url, e);
        }
    }

    private static Set<String> extractAllowedHosts(List<String> grantedPermissions) {
        if (grantedPermissions == null) {
            return Set.of();
        }
        return grantedPermissions.stream()
                .filter(p -> p != null && p.startsWith(EGRESS_PERMISSION_PREFIX))
                .map(p -> p.substring(EGRESS_PERMISSION_PREFIX.length()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
