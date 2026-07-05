package com.contactcenter.domain.plugin.runtime;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Payload wiadomości publikowanej na kolejkę RabbitMQ {@code cc.queue.plugin-invocation}
 * (ARCHITECTURE.md §11.5/§11.11, EPIC-28, BE-104) dla trzech punktów rozszerzeń
 * fire-and-forget: {@code POST_CONTACT_END}, {@code CUSTOMER_SYNC}, {@code DISPOSITION_SET}.
 *
 * <p><strong>Bez {@code installationId}:</strong> w przeciwieństwie do wywołań blokujących
 * ({@code publishManualAction}, BE-102), ta wiadomość NIE niesie identyfikatora jednej
 * konkretnej instalacji — {@link PluginRegistry#lookup} wszystkich aktywnych instalacji
 * zarejestrowanych na {@code extensionPoint} dla tego tenanta odbywa się w
 * {@link PluginInvocationConsumer}, w momencie konsumpcji, nie w
 * {@code ExtensionPointPublisherImpl} w momencie publikacji. To jest świadoma decyzja, nie
 * przeoczenie: między publikacją a konsumpcją mogła zostać zainstalowana/odinstalowana kolejna
 * instalacja na ten extension point, a wiadomość ma reprezentować zdarzenie domenowe
 * ("kontakt zakończony", "dyspozycja ustawiona"), nie zamrożoną listę odbiorców z chwili
 * publikacji.
 *
 * <p><strong>{@code eventPayload} jako mapa, nie jako jeden z trzech rekordów SDK:</strong>
 * {@code Jackson2JsonMessageConverter} (zob. {@code RabbitMQConfig#jacksonMessageConverter})
 * serializuje ten rekord do JSON wraz z typem docelowym zapisanym w nagłówku
 * {@code __TypeId__} — przy odbiorze Spring AMQP zdeserializuje {@code eventPayload} jako
 * {@code Map<String,Object>} (typ generyczny na polu rekordu), NIE jako
 * {@code ContactEvent}/{@code CustomerSyncRequest}/{@code DispositionEvent}, bo konwerter nie
 * ma w punkcie deserializacji informacji, który z trzech rekordów SDK zastosować — to zależy
 * od {@link #extensionPoint()}, który jest polem tego samego payloadu, nie metadanych
 * wiadomości. {@link PluginInvocationConsumer} mapuje tę mapę z powrotem na właściwy rekord SDK
 * (przez {@code ObjectMapper.convertValue}) na podstawie {@link #extensionPoint()} — wzorzec
 * analogiczny do tego, jak {@code PluginContextImpl}/{@code ExtensionPointPublisherImpl} budują
 * DTO SDK z danych wewnętrznych backendu, tylko tu krzyżujemy granicę JSON, nie granicę
 * classloadera.
 *
 * @param tenantId        tenant, dla którego zdarzenie wystąpiło — {@link PluginInvocationConsumer}
 *                        ustawia {@code TenantContext} na tę wartość przez
 *                        {@code TenantAwareConsumer#processWithTenant} PRZED jakimkolwiek
 *                        wywołaniem {@link PluginRegistry#lookup} lub kodu pluginu
 * @param extensionPoint  punkt rozszerzenia, dla którego ta wiadomość jest publikowana — jedna
 *                        z wartości {@code POST_CONTACT_END}/{@code CUSTOMER_SYNC}/
 *                        {@code DISPOSITION_SET} (jako {@code String}, nie
 *                        {@code com.contactcenter.domain.plugin.ExtensionPoint} — enum nie jest
 *                        gwarantowany stabilny przy deserializacji wiadomości wysłanych przed
 *                        deployem zmieniającym nazwy wartości; String jest jawnym, czytelnym
 *                        kontraktem wiadomości na kolejce, podobnie jak
 *                        {@code ContactEvent.extensionPoint()} w plugin-sdk)
 * @param eventPayload    zserializowany do mapy rekord SDK odpowiadający
 *                        {@code extensionPoint} ({@code ContactEvent} dla
 *                        {@code POST_CONTACT_END}, {@code CustomerSyncRequest} dla
 *                        {@code CUSTOMER_SYNC}, {@code DispositionEvent} dla
 *                        {@code DISPOSITION_SET}), nigdy {@code null}
 * @param publishedAt     znacznik czasu publikacji — do wglądu w logach/monitoring lagu
 *                        konsumpcji, nie używany do logiki biznesowej
 */
public record PluginInvocationMessage(
        UUID tenantId,
        String extensionPoint,
        Map<String, Object> eventPayload,
        Instant publishedAt) {
}
