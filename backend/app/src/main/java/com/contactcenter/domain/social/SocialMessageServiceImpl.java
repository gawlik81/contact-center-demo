package com.contactcenter.domain.social;

import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.routing.ContactQueuedMessage;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.infrastructure.social.SocialAdapterRegistry;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class SocialMessageServiceImpl implements SocialMessageService {

    private final SocialIntegrationRepository socialIntegrationRepository;
    private final SocialMessageRepository socialMessageRepository;
    private final ContactService contactService;
    private final SocialAdapterRegistry adapterRegistry;
    private final RabbitTemplate rabbitTemplate;

    // =========================================================================
    // Przetwarzanie przychodzących wiadomości
    // =========================================================================

    @Override
    @Transactional
    public void processIncomingMessage(IncomingSocialMessage incoming) {
        log.info("[SocialMessage] Przetwarzam przychodzącą wiadomość: platform={}, pageId={}, " +
                 "sender={}, externalMsgId={}",
                incoming.platform(), incoming.pageId(), incoming.senderExternalId(),
                incoming.externalMessageId());

        // 1. Znajdź integrację (cross-tenant lookup – brak JWT)
        Optional<SocialIntegration> integrationOpt = socialIntegrationRepository
                .findByPlatformAndPageId(incoming.platform(), incoming.pageId());

        if (integrationOpt.isEmpty()) {
            log.warn("[SocialMessage] Brak integracji dla platform={}, pageId={} – ignoruję wiadomość",
                    incoming.platform(), incoming.pageId());
            return;
        }

        SocialIntegration integration = integrationOpt.get();
        UUID tenantId = integration.getTenantId();

        // 2. Ustaw TenantContext (wymagany przez TenantAwareRepository)
        TenantContext.Snapshot snapshot = new TenantContext.Snapshot(tenantId, null, null, "SYSTEM");
        TenantContext.restore(snapshot);

        try {
            processIncomingWithTenantContext(incoming, integration, tenantId);
        } finally {
            // KRYTYCZNE: zawsze czyść TenantContext po zakończeniu przetwarzania
            TenantContext.clear();
        }
    }

    /**
     * Wewnętrzna logika przetwarzania – wywoływana po ustawieniu TenantContext.
     */
    private void processIncomingWithTenantContext(IncomingSocialMessage incoming,
                                                   SocialIntegration integration,
                                                   UUID tenantId) {
        // 3. Sprawdź idempotentność
        Optional<SocialMessage> existing = socialMessageRepository
                .findByExternalMessageId(incoming.externalMessageId(), tenantId);
        if (existing.isPresent()) {
            log.info("[SocialMessage] Duplikat pominięty: externalMsgId={}, tenant={}",
                    incoming.externalMessageId(), tenantId);
            return;
        }

        // 4. Znajdź lub utwórz kontakt
        String channel = platformToChannel(incoming.platform());
        Optional<Contact> activeContact = contactService
                .findActiveSocialContact(tenantId, incoming.senderExternalId(), channel);

        boolean isNewContact = activeContact.isEmpty();
        UUID contactId;

        if (isNewContact) {
            contactId = createSocialContact(tenantId, incoming, channel);
            log.info("[SocialMessage] Nowy kontakt social: contactId={}, channel={}, sender={}",
                    contactId, channel, incoming.senderExternalId());
        } else {
            contactId = activeContact.get().getContactId();
            log.info("[SocialMessage] Dołączam do istniejącego kontaktu: contactId={}, sender={}",
                    contactId, incoming.senderExternalId());
        }

        // 5. Zapisz wiadomość (messageId nadawany w Javie PRZED zapisem – BE-132, wzorzec
        // ContactEventServiceImpl#buildEvent – klucz złożony (message_id, sent_at) na tabeli
        // partycjonowanej od V100 nie wspiera @GeneratedValue)
        String attachmentsJson = serializeAttachments(incoming.attachments());
        SocialMessage message = SocialMessage.builder()
                .messageId(UUID.randomUUID())
                .tenantId(tenantId)
                .contactId(contactId)
                .integrationId(integration.getIntegrationId())
                .platform(incoming.platform())
                .direction(SocialMessage.Direction.INBOUND.name())
                .externalMessageId(incoming.externalMessageId())
                .senderExternalId(incoming.senderExternalId())
                .content(incoming.content())
                .attachments(attachmentsJson)
                .sentAt(incoming.sentAt() != null ? incoming.sentAt() : Instant.now())
                .receivedAt(Instant.now())
                .build();

        Optional<SocialMessage> saved = socialMessageRepository.save(message);
        if (saved.isEmpty()) {
            // BE-132: DRUGA linia obrony (constraint DB) złapała duplikat, który umknął dedupowi
            // aplikacyjnemu powyżej (krok 3) – prawdopodobny race dwóch równoległych deliveries tego
            // samego zdarzenia. Celowo NIE publikujemy contact.queued ani nie logujemy błędu – inny
            // wątek/instancja konsumenta już zapisał tę wiadomość i (jeśli isNewContact było true po
            // jego stronie) opublikował własny ContactQueuedMessage. Znany, pre-istniejący brak:
            // kontakt utworzony w kroku 4 PRZEZ TEN wątek (jeśli isNewContact) pozostaje osierocony
            // (bez wiadomości) – ten edge case istniał już przed BE-132 (dotyczy dowolnej rasy dwóch
            // webhooków, niezależnie od partycjonowania) i jest poza zakresem tego ticketu.
            log.info("[SocialMessage] Duplikat złapany przez constraint DB (druga linia obrony): " +
                     "externalMsgId={}, tenant={}, sentAt={} – pomijam dalsze przetwarzanie",
                    incoming.externalMessageId(), tenantId, message.getSentAt());
            return;
        }

        log.info("[SocialMessage] Wiadomość zapisana: messageId={}, contactId={}, tenant={}",
                message.getMessageId(), contactId, tenantId);

        // 6. Dla nowego kontaktu – opublikuj do routingu (brak kolejki dla social → null queueId)
        if (isNewContact) {
            publishContactQueued(contactId, null, tenantId);
        }
    }

    // =========================================================================
    // Wysyłka wiadomości przez agenta
    // =========================================================================

    /**
     * Wysyła wiadomość przez adapter platformy social media i zapisuje rekord OUTBOUND.
     *
     * <p><strong>Granice transakcji (naprawa CRITICAL, code review 2026-08-29):</strong>
     * ta metoda celowo NIE jest {@code @Transactional} – rozdzielona jest na 3 etapy,
     * analogicznie do {@code SocialIntegrationServiceImpl.deleteIntegration()}:
     * <ol>
     *   <li>{@link #loadSendContext(UUID, UUID)} – krótka transakcja readOnly: odczyt kontaktu
     *       i integracji, wybór adaptera.</li>
     *   <li>{@code adapter.sendMessage(...)} – wywołanie synchroniczne, blokujące HTTP do
     *       zewnętrznego API (np. WhatsApp Cloud API) wykonywane POZA jakąkolwiek transakcją,
     *       żeby nie trzymać połączenia z puli HikariCP podczas oczekiwania na sieć.</li>
     *   <li>{@link #saveOutboundMessage} – krótka transakcja zapisująca wiadomość OUTBOUND,
     *       wykonywana TYLKO gdy wysyłka się powiodła.</li>
     * </ol>
     *
     * <p>W przeciwieństwie do {@code revokeTokenAtProvider()} (gdzie błąd zewnętrznego API tylko
     * loguje WARN, bo operacja nadrzędna – usunięcie integracji – już się powiodła), błąd etapu 2
     * tutaj MUSI się propagować do wywołującego bez zapisu w DB: to jest właściwa, dotychczasowa
     * semantyka biznesowa (wiadomość, która nie dotarła do klienta, nie powinna wyglądać w historii
     * jak wysłana) – zachowana bez zmian względem wersji sprzed refaktoryzacji.
     *
     * <p><strong>BE-132 – korekta {@code @Transactional} (self-invocation):</strong>
     * {@link #loadSendContext} i {@link #saveOutboundMessage} MIAŁY adnotację {@code @Transactional},
     * ale wywołane przez {@code this.} z TEJ SAMEJ instancji (self-invocation) – klasyczna pułapka
     * Spring AOP (proxy-based): adnotacja działa tylko przy wywołaniu przez proxy bean-a, nie przy
     * wywołaniu wewnętrznym. Adnotacje były więc faktycznie nieskuteczne – usunięto je (nie dodano
     * {@code @Lazy self}/wywołania przez proxy, bo to niepotrzebne tutaj): obie metody tylko
     * ORKIESTRUJĄ wywołania na INNYCH bean-ach ({@code contactService}, {@code socialIntegrationRepository},
     * {@code socialMessageRepository}) – każdy z nich ma WŁASNĄ, poprawnie działającą granicę
     * transakcyjną (wywołanie przez jego własny proxy), więc brak adnotacji na tych dwóch metodach
     * serwisu nie zmienia zachowania, tylko usuwa mylący, martwy kod.
     */
    @Override
    public void sendMessage(UUID contactId, UUID tenantId, String content, List<String> attachmentUrls) {
        log.info("[SocialMessage] Wysyłam wiadomość: contactId={}, tenant={}", contactId, tenantId);

        // Etap 1: odczyt i walidacja kontekstu wysyłki (krótka transakcja readOnly)
        SendContext context = loadSendContext(contactId, tenantId);

        // Etap 2: wywołanie adaptera POZA transakcją – nie blokuje puli HikariCP podczas
        // synchronicznego wywołania HTTP do zewnętrznego API. Błąd (np. WhatsAppApiException)
        // propaguje się do wywołującego – etap 3 (zapis) celowo nie zostanie wykonany.
        context.adapter().sendMessage(context.integrationId(), context.recipientExternalId(), content,
                attachmentUrls != null ? attachmentUrls : List.of());

        // Etap 3: zapis wiadomości OUTBOUND (krótka transakcja) – tylko po udanej wysyłce
        saveOutboundMessage(tenantId, contactId, context.integrationId(), context.platform(),
                context.pageId(), content);

        log.info("[SocialMessage] Wiadomość OUTBOUND zapisana: contactId={}, platform={}",
                contactId, context.platform());
    }

    /**
     * Etap 1 wysyłki: odczyt kontaktu i aktywnej integracji, wybór adaptera. Żadnego I/O sieciowego.
     *
     * <p>Bez {@code @Transactional} – patrz Javadoc {@link #sendMessage} ("korekta self-invocation").
     * Odczyty wewnątrz ({@code contactService.findContactEntity},
     * {@code socialIntegrationRepository.findByTenantIdAndPlatform}) mają własną granicę transakcyjną
     * na swoich bean-ach.
     */
    protected SendContext loadSendContext(UUID contactId, UUID tenantId) {
        Contact contact = contactService.findContactEntity(contactId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Kontakt nie istnieje: contactId=" + contactId));

        String channel = contact.getChannel();
        SocialPlatform platform = channelToPlatform(channel);
        if (platform == null) {
            throw new IllegalArgumentException(
                    "Kontakt nie jest kanałem social media: channel=" + channel);
        }

        List<SocialIntegration> integrations = socialIntegrationRepository
                .findByTenantIdAndPlatform(tenantId, platform);

        if (integrations.isEmpty()) {
            throw new IllegalStateException(
                    "Brak aktywnej integracji social dla platformy: " + platform + ", tenant=" + tenantId);
        }

        // Użyj pierwszej aktywnej integracji
        SocialIntegration integration = integrations.stream()
                .filter(i -> "ACTIVE".equals(i.getWebhookStatus()))
                .findFirst()
                .orElse(integrations.get(0));

        return new SendContext(
                adapterRegistry.getAdapter(platform),
                integration.getIntegrationId(),
                platform,
                integration.getPageId(),
                contact.getRemoteAddress());
    }

    /**
     * Etap 3 wysyłki: zapis wiadomości OUTBOUND, wywoływany dopiero po udanym wywołaniu adaptera
     * (poza transakcją) w {@link #sendMessage}.
     *
     * <p>Bez {@code @Transactional} – patrz Javadoc {@link #sendMessage} ("korekta self-invocation").
     * Rzeczywista granica transakcyjna zapisu żyje w {@code socialMessageRepository.save} (klasa
     * {@code SocialMessageRepository} ma {@code @Transactional} na poziomie klasy, wywołana tu przez
     * jej własny proxy – to DZIAŁA poprawnie, w odróżnieniu od adnotacji na tej metodzie serwisu).
     *
     * <p>{@code externalMessageId} jest losowym UUID (prefiks {@code "OUTBOUND-"}) – praktycznie
     * nigdy nie koliduje z unikalnością złożoną {@code (tenant_id, external_message_id, sent_at)},
     * więc wynik {@code empty()} z {@link SocialMessageRepository#save} jest tu tylko logowany, bez
     * dodatkowej logiki idempotentności (w odróżnieniu od ścieżki INBOUND, gdzie duplikat jest
     * zdarzeniem oczekiwanym przy redelivery webhooka).
     */
    protected void saveOutboundMessage(UUID tenantId, UUID contactId, UUID integrationId,
                                        SocialPlatform platform, String pageId, String content) {
        SocialMessage outbound = SocialMessage.builder()
                .messageId(UUID.randomUUID())
                .tenantId(tenantId)
                .contactId(contactId)
                .integrationId(integrationId)
                .platform(platform)
                .direction(SocialMessage.Direction.OUTBOUND.name())
                .externalMessageId("OUTBOUND-" + UUID.randomUUID())
                .senderExternalId(pageId)
                .content(content)
                .sentAt(Instant.now())
                .build();

        if (socialMessageRepository.save(outbound).isEmpty()) {
            log.warn("[SocialMessage] Zapis OUTBOUND wyciszony przez constraint DB (nieoczekiwane – "
                            + "externalMessageId jest losowym UUID): contactId={}, tenant={}",
                    contactId, tenantId);
        }
    }

    /**
     * Kontekst wysyłki wiadomości – wynik etapu 1 ({@link #loadSendContext}), przekazywany do
     * wywołania adaptera (etap 2, poza transakcją) i zapisu wiadomości (etap 3).
     */
    private record SendContext(
            SocialMediaAdapter adapter,
            UUID integrationId,
            SocialPlatform platform,
            String pageId,
            String recipientExternalId) {
    }

    // =========================================================================
    // Odczyt historii wiadomości
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public List<SocialMessage> getRecentMessagesForContact(UUID contactId, UUID tenantId) {
        return socialMessageRepository.findByContactId(contactId, tenantId);
    }

    // =========================================================================
    // BE-113: Retencja – odcięcie referencji (EPIC-29)
    // =========================================================================

    @Override
    @Transactional
    @Deprecated // EPIC-30 (BE-125): zastąpione przez purgeByContactIds; usunięcie w BE-126
    public int detachContactReferences(UUID tenantId, List<UUID> contactIds) {
        return socialMessageRepository.detachContactReferences(tenantId, contactIds);
    }

    // =========================================================================
    // BE-125: Retencja – usuwanie wiadomości (EPIC-30)
    // =========================================================================

    /**
     * Bez {@code @Transactional} na serwisie: całość to jedno {@code DELETE} w transakcji
     * repozytorium (brak I/O do S3, więc nie ma czego dzielić) — spójnie z
     * {@code EmailMessageService#purgeByContactIds}.
     */
    @Override
    public int purgeByContactIds(UUID tenantId, List<UUID> contactIds) {
        if (contactIds == null || contactIds.isEmpty()) {
            return 0;
        }
        return socialMessageRepository.purgeByContactIds(tenantId, contactIds);
    }

    // =========================================================================
    // BE-127: Retencja – sweep wiadomości OSIEROCONYCH (contact_id IS NULL) wg wieku (EPIC-30)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public long countOrphansOlderThan(UUID tenantId, Instant cutoff) {
        return socialMessageRepository.countOrphansOlderThan(tenantId, cutoff);
    }

    // =========================================================================
    // BE-128: Retencja – liczenie wiadomości POWIĄZANYCH z kontaktami kwalifikującymi się (EPIC-30)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public long countLinkedToContactsOlderThan(UUID tenantId, Instant cutoff) {
        return socialMessageRepository.countLinkedToContactsOlderThan(tenantId, cutoff);
    }

    @Override
    public OrphanSocialPurgeBatch purgeOrphansOlderThan(
            UUID tenantId, SocialOrphanCursor cursor, Instant cutoff, int batchSize) {
        List<SocialMessageRepository.OrphanCandidate> page =
                socialMessageRepository.findOrphansOlderThan(tenantId, cutoff, cursor, batchSize);
        if (page.isEmpty()) {
            return new OrphanSocialPurgeBatch(0, 0, cursor);
        }

        List<UUID> ids = page.stream().map(SocialMessageRepository.OrphanCandidate::messageId).toList();
        Set<UUID> deleted = socialMessageRepository.deleteOrphansByIds(tenantId, ids);

        SocialMessageRepository.OrphanCandidate last = page.get(page.size() - 1);
        SocialOrphanCursor nextCursor = new SocialOrphanCursor(last.messageAt(), last.messageId());
        return new OrphanSocialPurgeBatch(deleted.size(), page.size(), nextCursor);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Tworzy nowy kontakt dla kanału social media.
     *
     * @param tenantId UUID tenanta
     * @param incoming dane przychodzącego zdarzenia
     * @param channel  kanał (np. "SOCIAL_FACEBOOK")
     * @return UUID nowo utworzonego kontaktu
     */
    private UUID createSocialContact(UUID tenantId, IncomingSocialMessage incoming, String channel) {
        UUID contactId = UUID.randomUUID();
        Instant now = Instant.now();

        Contact contact = Contact.builder()
                .contactId(contactId)
                .tenantId(tenantId)
                .channel(channel)
                .direction("INBOUND")
                .status("QUEUED")
                .remoteAddress(incoming.senderExternalId())
                .queuedAt(now)
                .startedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();

        contactService.insertContact(contact);
        return contactId;
    }

    /**
     * Publikuje event contact.queued do kolejki routingu.
     *
     * <p>Dla kanałów social media kolejka może być null (brak konfiguracji routingu
     * przez reguły kolejkowe). RoutingService obsłuży brak kolejki przez domyślną kolejkę.
     *
     * @param contactId UUID kontaktu
     * @param queueId   UUID kolejki (może być null)
     * @param tenantId  UUID tenanta
     */
    private void publishContactQueued(UUID contactId, UUID queueId, UUID tenantId) {
        ContactQueuedMessage msg = new ContactQueuedMessage(contactId, queueId, tenantId);
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_EVENTS, "contact.queued", msg);
        log.info("[SocialMessage] Opublikowano contact.queued: contactId={}, queueId={}, tenant={}",
                contactId, queueId, tenantId);
    }

    /**
     * Mapuje platformę social media na kanał kontaktu.
     *
     * @param platform platforma
     * @return nazwa kanału (np. "SOCIAL_FACEBOOK")
     */
    private String platformToChannel(SocialPlatform platform) {
        return switch (platform) {
            case FACEBOOK -> "SOCIAL_FACEBOOK";
            case INSTAGRAM -> "SOCIAL_INSTAGRAM";
            case WHATSAPP -> "SOCIAL_WHATSAPP";
        };
    }

    /**
     * Mapuje kanał kontaktu na platformę social media.
     *
     * @param channel kanał kontaktu
     * @return platforma lub null gdy kanał nie jest social
     */
    private SocialPlatform channelToPlatform(String channel) {
        if (channel == null) return null;
        return switch (channel) {
            case "SOCIAL_FACEBOOK" -> SocialPlatform.FACEBOOK;
            case "SOCIAL_INSTAGRAM" -> SocialPlatform.INSTAGRAM;
            case "SOCIAL_WHATSAPP" -> SocialPlatform.WHATSAPP;
            default -> null;
        };
    }

    /**
     * Serializuje listę załączników do JSON string dla kolumny JSONB.
     *
     * @param attachments lista map z metadanymi załączników (może być null)
     * @return JSON string lub "[]" gdy pusta/null
     */
    private String serializeAttachments(List<java.util.Map<String, Object>> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "[]";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(attachments);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[SocialMessage] Błąd serializacji załączników: {}", e.getMessage());
            return "[]";
        }
    }
}
