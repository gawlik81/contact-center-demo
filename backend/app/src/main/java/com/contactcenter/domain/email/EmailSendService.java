package com.contactcenter.domain.email;

import com.contactcenter.domain.exception.ResourceNotFoundException;

import java.util.Map;
import java.util.UUID;

/**
 * Serwis wysyłki wiadomości email przez SMTP.
 *
 * <p>Obsługuje:
 * <ul>
 *   <li>Wysyłkę odpowiedzi na wiadomości przychodzące (In-Reply-To, References)</li>
 *   <li>Generowanie unikalnego Message-ID dla każdej wysyłanej wiadomości</li>
 *   <li>Zapis wysłanej wiadomości jako OUTBOUND {@link EmailMessage} w DB</li>
 *   <li>Publikację eventu {@code email.sent} na RabbitMQ</li>
 * </ul>
 *
 * <p>Hasła SMTP są deszyfrowane tuż przed użyciem i NIE są logowane.
 */
public interface EmailSendService {

    /**
     * Wysyła odpowiedź na istniejącą wiadomość email.
     *
     * <p>Nagłówki {@code In-Reply-To} i {@code References} są ustawiane na podstawie
     * oryginalnej wiadomości dla poprawnego wątkowania w klientach email.
     *
     * @param tenantId          UUID tenanta (z TenantContext)
     * @param originalMessageId UUID wiadomości oryginalnej (PK tabeli email_message)
     * @param bodyHtml          treść odpowiedzi w HTML
     * @param subject           temat odpowiedzi (przekazany przez klienta lub auto-generowany)
     * @param agentId           UUID agenta wysyłającego odpowiedź
     * @return zapisana encja wiadomości OUTBOUND
     * @throws ResourceNotFoundException gdy oryginalna wiadomość nie istnieje
     * @throws EmailSendException        gdy wysyłka SMTP się nie powiedzie
     */
    EmailMessage sendReply(UUID tenantId, UUID originalMessageId, String bodyHtml, String subject, UUID agentId);

    /**
     * Wysyła odpowiedź na istniejącą wiadomość email z użyciem szablonu Mustache.
     *
     * <p>Renderuje szablon (temat + treść HTML) z podanymi zmiennymi i wywołuje
     * {@link #sendReply} z wyrenderowanymi wartościami.
     *
     * @param tenantId          UUID tenanta (z TenantContext)
     * @param originalMessageId UUID wiadomości oryginalnej (PK tabeli email_message)
     * @param templateId        UUID szablonu email (z tabeli email_template)
     * @param variables         mapa zmiennych do podstawienia w szablonie
     * @param agentId           UUID agenta wysyłającego odpowiedź
     * @return zapisana encja wiadomości OUTBOUND
     * @throws ResourceNotFoundException gdy oryginalna wiadomość lub szablon nie istnieje
     * @throws TemplateRenderException   gdy brakuje wymaganych zmiennych szablonu
     * @throws EmailSendException        gdy wysyłka SMTP się nie powiedzie
     */
    EmailMessage sendReplyWithTemplate(UUID tenantId, UUID originalMessageId,
                                        UUID templateId, Map<String, Object> variables, UUID agentId);

    /**
     * Wysyła nową wiadomość email do podanego odbiorcy – bez powiązania z istniejącym wątkiem.
     *
     * <p>Nie ustawia nagłówków {@code In-Reply-To} ani {@code References}, dzięki czemu
     * wiadomość tworzy nowy wątek w kliencie email odbiorcy.
     *
     * @param tenantId  UUID tenanta (z TenantContext)
     * @param toAddress adres odbiorcy (format RFC 2822)
     * @param subject   temat wiadomości
     * @param bodyHtml  treść wiadomości w HTML
     * @param agentId   UUID agenta wysyłającego wiadomość
     * @return zapisana encja wiadomości OUTBOUND
     * @throws ResourceNotFoundException gdy tenant nie istnieje
     * @throws EmailSendException        gdy brak konfiguracji SMTP lub wysyłka się nie powiedzie
     */
    EmailMessage sendNew(UUID tenantId, String toAddress, String subject, String bodyHtml, UUID agentId);

    /** Wyjątek wysyłki email przez SMTP. */
    class EmailSendException extends RuntimeException {
        public EmailSendException(String message) {
            super(message);
        }

        public EmailSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
