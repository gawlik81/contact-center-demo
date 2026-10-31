package com.contactcenter.domain.email;

/**
 * Rzucany, gdy {@code s3Key} podany w żądaniu (wysyłka odpowiedzi/nowej wiadomości e-mail)
 * nie przechodzi allow-listy prefiksu tenanta ({@link EmailAttachmentKeys#isOwnedByTenant}):
 * klucz wskazuje na obiekt innego tenanta, na obiekt spoza schematu załączników e-mail
 * (nagranie, EML), albo próbuje wyjść z prefiksu przez segment {@code .}/{@code ..} (BE-143).
 *
 * <p>Mapowany na HTTP 403 przez {@code GlobalExceptionHandler}, analogicznie do
 * {@code CrossTenantAccessException} (BE-002) i dotychczasowej ochrony IDOR w
 * {@code EmailAttachmentController#downloadAttachment}. Celowo 403, nie 400 — to próba
 * przekroczenia granicy tenanta, nie błąd formatu danych wejściowych.
 *
 * <p>Rzucany PRZED jakąkolwiek interakcją z S3 lub SMTP ({@code EmailSendServiceImpl#sendReply}/
 * {@code #sendNew}) — całe żądanie jest odrzucane, nie tylko pojedynczy wadliwy załącznik.
 *
 * <p>Wiadomość wyjątku NIE zawiera surowego {@code s3Key} (dane od klienta, potencjalnie
 * niebezpieczne dla logów/odpowiedzi) — do logowania klucza użyj {@code EmailAttachmentKeys#forLog}.
 */
public class EmailAttachmentAccessDeniedException extends RuntimeException {

    public EmailAttachmentAccessDeniedException(String message) {
        super(message);
    }
}
