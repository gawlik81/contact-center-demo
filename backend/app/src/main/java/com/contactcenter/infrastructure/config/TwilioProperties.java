package com.contactcenter.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Właściwości konfiguracyjne dla adaptera Twilio.
 *
 * <p>Wszystkie wartości wrażliwe (accountSid, authToken) wczytywane wyłącznie
 * przez zmienne środowiskowe – nigdy nie umieszczaj ich w repozytoriach.
 *
 * <p>Prefix: {@code twilio} – mapuje na sekcję YAML:
 * <pre>
 * twilio:
 *   enabled: true
 *   account-sid: ${TWILIO_ACCOUNT_SID}
 *   auth-token: ${TWILIO_AUTH_TOKEN}
 *   phone-number: ${TWILIO_PHONE_NUMBER}
 *   status-callback-url: ${TWILIO_STATUS_CALLBACK_URL}
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "twilio")
public class TwilioProperties {

    /**
     * Włącza adapter Twilio jako primary provider telefonii.
     * Domyślnie {@code false} – MockTelephonyAdapter pozostaje aktywny.
     */
    private boolean enabled = false;

    /**
     * Account SID z Twilio Console (zaczyna się od "AC").
     * Wymagane gdy {@code enabled=true}.
     */
    private String accountSid;

    /**
     * Auth Token z Twilio Console.
     * Wymagane gdy {@code enabled=true}.
     * NIGDY nie umieszczaj wartości bezpośrednio w YAML – używaj ENV var.
     */
    private String authToken;

    /**
     * Numer telefonu Twilio w formacie E.164 (np. +48123456789).
     * Używany jako numer "from" przy połączeniach wychodzących.
     */
    private String phoneNumber;

    /**
     * URL do odbierania callbacków statusu połączeń od Twilio.
     * Musi być publicznie dostępny (np. przez ngrok w dev lub właściwa domena w prod).
     * Przykład: https://example.com/api/telephony/webhook/twilio
     */
    private String statusCallbackUrl;

    /**
     * Włącza nagrywanie konferencji przez {@code <Conference record="record-from-start">}
     * i obsługę webhooków recordingStatusCallback.
     * Domyślnie {@code true} gdy Twilio jest włączone.
     */
    private boolean recordingEnabled = true;

    /**
     * Twilio API Key SID (zaczyna się od "SK") – wymagany do generowania Access Token
     * dla Twilio Voice JS SDK. Tworzony w Twilio Console → Account → API Keys.
     * NIGDY nie umieszczaj wartości bezpośrednio w YAML – używaj ENV var.
     */
    private String apiKeySid;

    /**
     * Twilio API Key Secret – sekret pary API Key.
     * Wyświetlany tylko raz po utworzeniu klucza w Twilio Console.
     * NIGDY nie umieszczaj wartości bezpośrednio w YAML – używaj ENV var.
     */
    private String apiKeySecret;

    /**
     * Twilio TwiML App SID (zaczyna się od "AP") – identyfikator aplikacji TwiML
     * konfigurowanej w Twilio Console → Voice → TwiML Apps.
     * Wymagany przez VoiceGrant do obsługi połączeń wychodzących przez SDK.
     */
    private String twimlAppSid;
}
