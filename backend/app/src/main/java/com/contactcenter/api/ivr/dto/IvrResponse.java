package com.contactcenter.api.ivr.dto;

import com.contactcenter.domain.ivr.IvrDefinition;
import com.contactcenter.domain.model.IvrTree;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO odpowiedzi dla drzewa IVR.
 *
 * @param ivrId      UUID drzewa IVR
 * @param name       nazwa drzewa IVR
 * @param definition definicja grafu węzłów IVR
 * @param version    numer wersji (inkrementowany przy zmianie definition)
 * @param isActive   czy drzewo jest aktualnie aktywne
 * @param createdAt  timestamp utworzenia
 */
public record IvrResponse(
        UUID ivrId,
        String name,
        IvrDefinition definition,
        Integer version,
        @JsonProperty("isActive") boolean isActive,
        Instant createdAt
) {
    /**
     * Tworzy DTO z encji JPA.
     *
     * @param ivr encja drzewa IVR
     * @return DTO odpowiedzi
     */
    public static IvrResponse from(IvrTree ivr) {
        return new IvrResponse(
                ivr.getIvrId(),
                ivr.getName(),
                ivr.getDefinition(),
                ivr.getVersion(),
                ivr.isActive(),
                ivr.getCreatedAt()
        );
    }
}
