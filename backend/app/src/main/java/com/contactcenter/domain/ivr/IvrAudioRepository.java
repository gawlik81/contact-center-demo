package com.contactcenter.domain.ivr;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium plików audio IVR (tabela {@code ivr_audio}).
 *
 * <p>Rozszerza {@link TenantAwareRepository} – każde zapytanie ustawia kontekst RLS.
 */
@Slf4j
@Repository
public class IvrAudioRepository extends TenantAwareRepository {

    /**
     * Pobiera plik audio po identyfikatorze z weryfikacją tenanta.
     *
     * @param audioId  UUID pliku audio
     * @param tenantId UUID tenanta
     * @return Optional z plikiem audio lub empty gdy nie istnieje
     */
    @Transactional(readOnly = true)
    public Optional<IvrAudio> findByAudioIdAndTenantId(UUID audioId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        List<IvrAudio> results = em.createQuery(
                        "SELECT a FROM IvrAudio a WHERE a.audioId = :audioId AND a.tenantId = :tenantId",
                        IvrAudio.class)
                .setParameter("audioId", audioId)
                .setParameter("tenantId", tenantId)
                .setMaxResults(1)
                .getResultList();

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Pobiera wszystkie pliki audio dla danego drzewa IVR.
     *
     * @param ivrId    UUID drzewa IVR
     * @param tenantId UUID tenanta
     * @return lista plików audio
     */
    @Transactional(readOnly = true)
    public List<IvrAudio> findByIvrIdAndTenantId(UUID ivrId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        return em.createQuery(
                        "SELECT a FROM IvrAudio a WHERE a.ivrId = :ivrId AND a.tenantId = :tenantId ORDER BY a.filename ASC",
                        IvrAudio.class)
                .setParameter("ivrId", ivrId)
                .setParameter("tenantId", tenantId)
                .getResultList();
    }

    /**
     * Tworzy nowy plik audio przez natywny INSERT.
     *
     * @param audio encja pliku audio do zapisania (audioId musi być ustawiony)
     * @return zapisana encja
     */
    @Transactional
    public IvrAudio insert(IvrAudio audio) {
        assertSameTenant(audio.getTenantId());
        setTenantContextInDb(audio.getTenantId());

        log.info("[IvrAudioRepo] Tworzenie pliku audio: tenant={}, filename={}, type={}",
                audio.getTenantId(), audio.getFilename(), audio.getAudioType());

        em.createNativeQuery("""
                INSERT INTO ivr_audio (
                    audio_id, tenant_id, ivr_id, filename, s3_url,
                    duration_seconds, audio_type, tts_text, created_at
                ) VALUES (
                    CAST(:audioId AS uuid),
                    CAST(:tenantId AS uuid),
                    CAST(:ivrId AS uuid),
                    :filename,
                    :s3Url,
                    :durationSeconds,
                    :audioType,
                    :ttsText,
                    :createdAt
                )
                """)
                .setParameter("audioId", audio.getAudioId().toString())
                .setParameter("tenantId", audio.getTenantId().toString())
                .setParameter("ivrId", audio.getIvrId() != null ? audio.getIvrId().toString() : null)
                .setParameter("filename", audio.getFilename())
                .setParameter("s3Url", audio.getS3Url())
                .setParameter("durationSeconds", audio.getDurationSeconds())
                .setParameter("audioType", audio.getAudioType())
                .setParameter("ttsText", audio.getTtsText())
                .setParameter("createdAt", audio.getCreatedAt())
                .executeUpdate();

        log.info("[IvrAudioRepo] Plik audio utworzony: audioId={}, tenant={}", audio.getAudioId(), audio.getTenantId());
        return audio;
    }
}
