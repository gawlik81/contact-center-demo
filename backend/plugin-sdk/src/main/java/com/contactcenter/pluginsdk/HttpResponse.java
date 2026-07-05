package com.contactcenter.pluginsdk;

import java.util.Map;

/**
 * Immutable response returned by {@link HttpEgressClient}.
 *
 * @param statusCode HTTP status code returned by the remote host
 * @param headers    response headers, never {@code null} (may be empty)
 * @param body        raw response body bytes, never {@code null} (may be empty)
 */
public record HttpResponse(int statusCode, Map<String, String> headers, byte[] body) {
}
