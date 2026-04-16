package com.contactcenter.api.social.dto;

import com.contactcenter.domain.model.SocialPlatform;

/**
 * Odpowiedź inicjacji OAuth – zawiera URL do przekierowania użytkownika.
 *
 * @param platform         platforma, dla której generowany jest URL
 * @param authorizationUrl URL do którego FE powinien przekierować użytkownika
 * @param state            wartość state parametru OAuth (do weryfikacji CSRF w callbacku)
 */
public record OAuthInitiateResponse(
        SocialPlatform platform,
        String authorizationUrl,
        String state
) {
}
