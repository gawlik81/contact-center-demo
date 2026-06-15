package com.contactcenter.domain.social;

/**
 * Enum reprezentujący obsługiwane platformy social media.
 *
 * <p>Mapuje typ PostgreSQL {@code social_platform} (ENUM) zdefiniowany w V010.
 * Wartości muszą być identyczne z wartościami w SQL.
 */
public enum SocialPlatform {
    FACEBOOK,
    INSTAGRAM,
    WHATSAPP
}
