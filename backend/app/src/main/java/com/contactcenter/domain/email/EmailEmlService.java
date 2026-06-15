package com.contactcenter.domain.email;

import com.contactcenter.infrastructure.config.S3Properties;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Properties;
import java.util.UUID;

/**
 * Serwis generujący pliki EML z encji {@link EmailMessage} i uploadujący je do MinIO/S3.
 *
 * <p>EML (Electronic Mail) to format RFC 2822 umożliwiający przechowywanie wiadomości email
 * jako pliki – pozwala na ich odtworzenie w szczegółach kontaktu analogicznie do nagrań audio.
 *
 * <p>Klucz S3: {@code {tenantId}/{year}/{month}/{contactId}.eml}
 *
 * <p>Content-Type: {@code message/rfc822} (RFC 2046)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailEmlService {

    private static final DateTimeFormatter YEAR_FMT  = DateTimeFormatter.ofPattern("yyyy").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("MM").withZone(ZoneOffset.UTC);

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties s3Properties;

    // =========================================================================
    // Publiczny API
    // =========================================================================

    /**
     * Generuje plik EML z encji {@link EmailMessage} i uploaduje do S3.
     *
     * @param message   encja wiadomości email
     * @param contactId UUID powiązanego kontaktu (część klucza S3)
     * @param tenantId  UUID tenanta (część klucza S3)
     * @return klucz S3 pliku EML (do zapisania w {@code contact.recording_url})
     * @throws EmailEmlException gdy generowanie EML lub upload do S3 nie powiedzie się
     */
    public String generateAndUpload(EmailMessage message, UUID contactId, UUID tenantId) {
        Instant timestamp = message.getReceivedAt() != null ? message.getReceivedAt() : Instant.now();
        String s3Key = buildEmlS3Key(tenantId, contactId, timestamp);

        log.debug("[EmailEml] Generowanie EML: messageId={}, contactId={}, s3Key={}",
                message.getId(), contactId, s3Key);

        byte[] emlBytes;
        try {
            emlBytes = toEmlBytes(message);
        } catch (MessagingException | IOException e) {
            throw new EmailEmlException(
                    "Błąd generowania EML dla wiadomości " + message.getId(), e);
        }

        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(s3Key)
                    .contentType("message/rfc822")
                    .build();
            s3Client.putObject(putRequest, RequestBody.fromBytes(emlBytes));
        } catch (S3Exception e) {
            throw new EmailEmlException(
                    "Błąd uploadu EML do S3 dla klucza " + s3Key, e);
        }

        log.info("[EmailEml] EML uploadowany: messageId={}, contactId={}, s3Key={}, size={}B",
                message.getId(), contactId, s3Key, emlBytes.length);

        return s3Key;
    }

    /**
     * Buduje klucz S3 pliku EML: {@code {tenantId}/{year}/{month}/{contactId}.eml}
     *
     * @param tenantId  UUID tenanta
     * @param contactId UUID kontaktu
     * @param timestamp czas zdarzenia (do wyznaczenia roku i miesiąca)
     * @return klucz S3
     */
    public String buildEmlS3Key(UUID tenantId, UUID contactId, Instant timestamp) {
        Instant ts = timestamp != null ? timestamp : Instant.now();
        String year  = YEAR_FMT.format(ts);
        String month = MONTH_FMT.format(ts);
        return String.format("%s/%s/%s/%s.eml", tenantId, year, month, contactId);
    }

    /**
     * Generuje presigned URL dla pliku EML w S3 z podanym TTL.
     *
     * @param s3Key klucz pliku EML w S3
     * @param ttl   czas ważności presigned URL
     * @return presigned URL jako String
     * @throws EmailEmlException gdy S3/MinIO jest niedostępny
     */
    public String generatePresignedUrl(String s3Key, Duration ttl) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(s3Key)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
            return presignedRequest.url().toString();

        } catch (S3Exception e) {
            throw new EmailEmlException(
                    "Błąd generowania presigned URL dla EML s3Key=" + s3Key, e);
        }
    }

    // =========================================================================
    // Konwersja EmailMessage → bajty EML
    // =========================================================================

    /**
     * Konwertuje encję {@link EmailMessage} do bajtów formatu EML (RFC 2822).
     *
     * <p>Logika budowania treści:
     * <ul>
     *   <li>Oba formaty ({@code bodyHtml} i {@code bodyText}) → {@code multipart/alternative}
     *       z częścią text/plain i text/html.</li>
     *   <li>Tylko {@code bodyHtml} → {@code multipart/alternative}: text/plain (stripped HTML)
     *       + text/html.</li>
     *   <li>Tylko {@code bodyText} → prosta wiadomość text/plain.</li>
     *   <li>Brak treści → pusta wiadomość text/plain.</li>
     * </ul>
     *
     * @param message encja wiadomości email
     * @return bajty EML
     * @throws MessagingException gdy Jakarta Mail nie może zbudować wiadomości
     * @throws IOException        gdy zapis do ByteArrayOutputStream nie powiedzie się
     */
    protected byte[] toEmlBytes(EmailMessage message) throws MessagingException, IOException {
        Session session = Session.getDefaultInstance(new Properties());
        MimeMessage mimeMessage = new MimeMessage(session);

        // Nagłówki RFC 2822
        if (message.getFromAddress() != null && !message.getFromAddress().isBlank()) {
            mimeMessage.setFrom(new InternetAddress(message.getFromAddress()));
        }
        if (message.getToAddress() != null && !message.getToAddress().isBlank()) {
            mimeMessage.setRecipients(MimeMessage.RecipientType.TO,
                    InternetAddress.parse(message.getToAddress()));
        }
        if (message.getCcAddress() != null && !message.getCcAddress().isBlank()) {
            mimeMessage.setRecipients(MimeMessage.RecipientType.CC,
                    InternetAddress.parse(message.getCcAddress()));
        }
        if (message.getSubject() != null) {
            mimeMessage.setSubject(message.getSubject(), "UTF-8");
        }
        if (message.getMessageIdHeader() != null && !message.getMessageIdHeader().isBlank()) {
            mimeMessage.setHeader("Message-ID", message.getMessageIdHeader());
        }
        if (message.getInReplyTo() != null && !message.getInReplyTo().isBlank()) {
            mimeMessage.setHeader("In-Reply-To", message.getInReplyTo());
        }

        // Nagłówek Date – receivedAt lub sentAt lub bieżący czas
        Instant dateInstant = message.getReceivedAt() != null
                ? message.getReceivedAt()
                : (message.getSentAt() != null ? message.getSentAt() : Instant.now());
        mimeMessage.setSentDate(Date.from(dateInstant));

        // Treść wiadomości
        boolean hasHtml = message.getBodyHtml() != null && !message.getBodyHtml().isBlank();
        boolean hasText = message.getBodyText() != null && !message.getBodyText().isBlank();

        if (hasHtml && hasText) {
            // Oba formaty → multipart/alternative
            MimeMultipart multipart = new MimeMultipart("alternative");
            multipart.addBodyPart(buildTextPart(message.getBodyText()));
            multipart.addBodyPart(buildHtmlPart(message.getBodyHtml()));
            mimeMessage.setContent(multipart);
        } else if (hasHtml) {
            // Tylko HTML → multipart/alternative z auto-wygenerowanym text/plain
            String plainFallback = stripHtml(message.getBodyHtml());
            MimeMultipart multipart = new MimeMultipart("alternative");
            multipart.addBodyPart(buildTextPart(plainFallback));
            multipart.addBodyPart(buildHtmlPart(message.getBodyHtml()));
            mimeMessage.setContent(multipart);
        } else if (hasText) {
            // Tylko text/plain
            mimeMessage.setText(message.getBodyText(), "UTF-8");
        } else {
            // Brak treści – pusta wiadomość
            mimeMessage.setText("", "UTF-8");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        mimeMessage.writeTo(baos);
        return baos.toByteArray();
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private MimeBodyPart buildTextPart(String text) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        part.setText(text, "UTF-8", "plain");
        return part;
    }

    private MimeBodyPart buildHtmlPart(String html) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        part.setText(html, "UTF-8", "html");
        return part;
    }

    /**
     * Usuwa tagi HTML z treści (fallback plain text gdy brak wersji tekstowej).
     * Uproszczona implementacja analogiczna do EmailSendService.stripHtml().
     */
    private String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("<[^>]+>", "").replaceAll("&nbsp;", " ").trim();
    }

    // =========================================================================
    // Wyjątek domenowy
    // =========================================================================

    /**
     * Wyjątek rzucany przy błędach operacji na plikach EML.
     */
    public static class EmailEmlException extends RuntimeException {
        public EmailEmlException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
