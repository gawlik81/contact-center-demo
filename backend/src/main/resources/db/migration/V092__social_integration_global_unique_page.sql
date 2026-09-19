-- =============================================================================
-- V092__social_integration_global_unique_page.sql
-- Globalny unikalny constraint (platform, page_id) w tabeli social_integration
--
-- Problem (code review 2026-08-29, moduł WhatsApp Business – ręczne podłączenie):
-- dotychczasowy constraint uq_social_integration_tenant_platform_page (V010) jest
-- scoped PER-TENANT (UNIQUE (tenant_id, platform, page_id)) – nic nie stoi na
-- przeszkodzie, by DWAJ RÓŻNI tenanci mieli wiersz z tym samym platform+page_id.
-- Dla Facebooka/Instagrama nie było to realnym ryzykiem (page_id pochodzi z OAuth,
-- administrator fizycznie nie mógł podać cudzej strony), ale nowy endpoint
-- POST /api/integrations/WHATSAPP/connect pozwala adminowi wkleić dowolny
-- phoneNumberId bez żadnej weryfikacji własności.
--
-- Konsekwencja: SocialIntegrationRepository.findByPlatformAndPageId() – używana
-- przez webhook handler (SocialMessageServiceImpl.processIncomingMessage(),
-- endpoint bez JWT, identyfikacja tenanta WYŁĄCZNIE po parze platform+page_id)
-- – używa setMaxResults(1) bez ORDER BY, więc przy kolizji zwróciłaby
-- nieokreślony jeden z dwóch wierszy: wiadomość klienta Tenanta A mogłaby trafić
-- (trwale, dopóki kolizja istnieje) do Tenanta B.
--
-- Naprawa: globalny unikalny constraint (platform, page_id) – ściślejszy niż
-- istniejący constraint per-tenant, więc logika update-in-place dla tego samego
-- tenanta (SocialIntegrationServiceImpl.saveIntegration() aktualizuje istniejący
-- wiersz zamiast insertować nowy, gdy tenant już posiada tę integrację) nie jest
-- naruszona. Zabezpieczenie na poziomie DB, niezależne od poprawności walidacji
-- aplikacyjnej (patrz też SocialIntegrationRepository.existsByPlatformAndPageIdAndTenantIdNot()).
--
-- Migracja: Flyway V092
-- Zależności: V010 (create_email_social – tworzy tabelę social_integration)
-- UWAGA: Nie modyfikujemy V010 – Flyway zabrania edycji zastosowanych migracji.
-- =============================================================================

ALTER TABLE social_integration
    ADD CONSTRAINT uq_social_integration_global_platform_page
    UNIQUE (platform, page_id);

COMMENT ON CONSTRAINT uq_social_integration_global_platform_page ON social_integration
    IS 'Globalna unikalnosc (platform, page_id) w calym systemie - zapobiega kolizji '
       'routingu webhooka miedzy tenantami (patrz SocialIntegrationRepository.findByPlatformAndPageId). '
       'Egzekwowane na poziomie DB niezaleznie od walidacji aplikacyjnej w '
       'SocialIntegrationServiceImpl.saveIntegration().';
