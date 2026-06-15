package com.contactcenter.domain.email;

import jakarta.mail.MessagingException;
import jakarta.mail.Store;

/**
 * Serwis pollingu skrzynek IMAP dla wszystkich aktywnych tenantów.
 *
 * <p>Uruchamiany przez {@code @Scheduled} co {@code email.poll-delay-ms} ms (domyślnie 60s).
 * Dla każdego tenanta z {@code email_enabled=true} w config otwiera połączenie IMAP,
 * pobiera wiadomości UNSEEN i zapisuje je w DB.
 *
 * <p>Aktywny tylko gdy {@code email.enabled=true} (domyślnie true).
 */
public interface EmailPollingService {

    /**
     * Tworzy i zwraca połączoną sesję IMAP Store.
     *
     * @param config   konfiguracja IMAP
     * @param password hasło odszyfrowane (NIE logujemy)
     * @return połączony Store
     * @throws MessagingException gdy połączenie nie powiedzie się
     */
    Store connectImap(EmailAccountConfig config, String password) throws MessagingException;
}
