package com.contactcenter.infrastructure.social;

import com.contactcenter.domain.model.SocialPlatform;
import com.contactcenter.domain.social.SocialMediaAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rejestr adapterów social media.
 *
 * <p>Wstrzykuje wszystkie implementacje {@link SocialMediaAdapter} i buduje mapę
 * {@code SocialPlatform → adapter}. Pozwala na wybór odpowiedniego adaptera
 * dynamicznie na podstawie platformy bez użycia if/else.
 *
 * <p>Nowy adapter (np. Twitter/X) wymaga tylko dodania nowej implementacji
 * z {@code @Component} – rejestr wykryje go automatycznie.
 */
@Slf4j
@Component
public class SocialAdapterRegistry {

    private final Map<SocialPlatform, SocialMediaAdapter> adapters;

    public SocialAdapterRegistry(List<SocialMediaAdapter> adapterList) {
        adapters = new EnumMap<>(SocialPlatform.class);
        for (SocialMediaAdapter adapter : adapterList) {
            adapters.put(adapter.getPlatform(), adapter);
            log.info("[SocialAdapterRegistry] Zarejestrowano adapter: platform={}, class={}",
                    adapter.getPlatform(), adapter.getClass().getSimpleName());
        }
        log.info("[SocialAdapterRegistry] Zarejestrowano {} adapterów: {}",
                adapters.size(), adapters.keySet());
    }

    /**
     * Zwraca adapter dla podanej platformy.
     *
     * @param platform platforma social media
     * @return Optional z adapterem lub empty gdy platforma nieobsługiwana
     */
    public Optional<SocialMediaAdapter> findAdapter(SocialPlatform platform) {
        return Optional.ofNullable(adapters.get(platform));
    }

    /**
     * Zwraca adapter dla podanej platformy lub rzuca wyjątek gdy brak.
     *
     * @param platform platforma social media
     * @return adapter dla platformy
     * @throws IllegalStateException gdy platforma nieobsługiwana
     */
    public SocialMediaAdapter getAdapter(SocialPlatform platform) {
        SocialMediaAdapter adapter = adapters.get(platform);
        if (adapter == null) {
            throw new IllegalStateException(
                    "[SocialAdapterRegistry] Brak adaptera dla platformy: " + platform);
        }
        return adapter;
    }
}
