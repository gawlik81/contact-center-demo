package com.contactcenter.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Encja JPA reprezentująca plik audio dla IVR (tabela {@code ivr_audio}).
 *
 * <p>Plik audio może być:
 * <ul>
 *   <li>{@code UPLOADED} – przesłany przez supervisora plik MP3/WAV</li>
 *   <li>{@code TTS}      – wygenerowany przez Text-to-Speech na podstawie {@code ttsText}</li>
 * </ul>
 *
 * <p>Tabela jest globalna dla tenanta – {@code ivr_id} może być null (audio globalne używane
 * przez wiele drzew IVR). Unikalność: (tenant_id, filename).
 */
@Entity
@Table(name = "ivr_audio")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IvrAudio {

    @Id
    @Column(name = "audio_id", updatable = false, nullable = false)
    private UUID audioId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** UUID drzewa IVR (nullable – audio globalne tenanta). */
    @Column(name = "ivr_id")
    private UUID ivrId;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    /** Pełny URL pliku w S3-compatible storage. */
    @Column(name = "s3_url", nullable = false)
    private String s3Url;

    /** Czas trwania nagrania w sekundach (nullable). */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /**
     * Typ pliku audio: UPLOADED lub TTS.
     * Sprawdź {@code CONSTRAINT chk_ivr_audio_type} w V008.
     */
    @Column(name = "audio_type", nullable = false, length = 20)
    @Builder.Default
    private String audioType = "UPLOADED";

    /** Tekst wejściowy dla TTS (null gdy audioType=UPLOADED). */
    @Column(name = "tts_text")
    private String ttsText;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
