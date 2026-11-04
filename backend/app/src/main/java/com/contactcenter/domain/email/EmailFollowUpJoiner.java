package com.contactcenter.domain.email;

import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.Customer;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.websocket.ContactMessageReceivedPublisher;
import jakarta.mail.internet.InternetAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Joins a follow-up inbound e-mail to an existing open contact (BE-154) and notifies its agent.
 *
 * <p>All of the following must hold, otherwise the caller routes the message as a new contact (the
 * pre-BE-154 behaviour, always safe):
 * <ol>
 *   <li>{@code In-Reply-To} points to a message of the same tenant that belongs to a contact;</li>
 *   <li>the contact is EMAIL, {@code ASSIGNED/ACTIVE/ON_HOLD} and has an agent;</li>
 *   <li>the normalised sender ({@code From}) is a known party of that contact (anti-injection, From is
 *       spoofable): contact remote address / channel metadata sender, the customer's e-mail, or the
 *       customer-side addresses of the thread (inbound senders, outbound recipients) - never our own mailbox;</li>
 *   <li>one atomic conditional UPDATE re-checks the contact state and attaches the message
 *       ({@link EmailMessageRepository#attachToOpenAssignedContact}).</li>
 * </ol>
 * Any exception results in "not joined". Addresses are never logged in clear text.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class EmailFollowUpJoiner {

    private final EmailMessageRepository emailMessageRepository;
    private final ContactService contactService;
    private final CustomerService customerService;
    private final ContactMessageReceivedPublisher messageReceivedPublisher;
    private final EmailEmlService emailEmlService;

    /**
     * @param ownMailbox the tenant's own mailbox address (email config username); never a customer party
     * @return true when the message was attached to an existing contact (routing must be skipped)
     */
    boolean tryJoin(EmailMessage saved, UUID tenantId, String ownMailbox) {
        String inReplyTo = saved.getInReplyTo();
        if (inReplyTo == null || inReplyTo.isBlank()) {
            return false;
        }
        Contact joined;
        try {
            joined = attach(saved, tenantId, inReplyTo, ownMailbox);
        } catch (Exception e) {
            log.warn("[EmailFollowUp] Nie udało się dołączyć follow-upu (routing jak nowy): messageId={}, error={}",
                    saved.getId(), e.getMessage());
            return false;
        }
        if (joined == null) {
            return false;
        }
        // Past this point the row is committed: nothing below may turn into "not joined" (that would route
        // the same message into a second contact).
        saved.setContactId(joined.getContactId());
        try {
            log.info("[EmailFollowUp] Follow-up dołączony do kontaktu: contactId={}, messageId={}, tenant={}",
                    joined.getContactId(), saved.getId(), tenantId);
            messageReceivedPublisher.publish(tenantId, joined.getContactId(), joined.getAgentId(),
                    "EMAIL", saved.getId(), saved.getReceivedAt());
        } catch (Exception e) {
            log.warn("[EmailFollowUp] Błąd powiadomienia po dołączeniu: messageId={}, error={}",
                    saved.getId(), e.getMessage());
        }
        // The agent notification goes out FIRST: the EML archive does S3 I/O (attachment downloads + upload) on the
        // poller thread and must never delay it. It stays synchronous (same thread = TenantContext in place, no
        // thread hop) and best-effort: archiveEmlAfterJoin never throws.
        archiveEmlAfterJoin(saved, joined.getContactId(), tenantId);
        return true;
    }

    /**
     * Generates the per-message EML for a joined follow-up and stores its key in {@code email_message.eml_s3_key}
     * (never touches {@code contact.recording_url}, which keeps pointing at the first message's EML).
     *
     * <p>Best-effort: any failure is only logged - the join is already committed and must stay valid. S3 I/O
     * happens after the join committed: the attach UPDATE runs in its own repository transaction, which has
     * ended when {@link #attach} returns ({@code tryJoin} must NOT be called inside an enclosing transaction -
     * its "row committed" contract already requires this; the only caller is the non-transactional IMAP
     * poller). Same thread, so the poller's {@code TenantContext} is still in place - no thread hop.
     */
    private void archiveEmlAfterJoin(EmailMessage saved, UUID contactId, UUID tenantId) {
        String key = null;
        try {
            key = emailEmlService.generateAndUploadForMessage(saved, contactId, tenantId);
            if (emailMessageRepository.updateEmlS3Key(saved.getId(), saved.getMessageAt(), tenantId, key)) {
                saved.setEmlS3Key(key);
                return;
            }
            log.error("[EmailFollowUp] Klucz EML nie został zapisany (0 wierszy) - usuwam wgrany obiekt: "
                    + "contactId={}, messageId={}", contactId, saved.getId());
        } catch (Exception e) {
            log.error("[EmailFollowUp] Nie udało się zarchiwizować EML follow-upu (join pozostaje ważny): "
                    + "contactId={}, messageId={}, error={}", contactId, saved.getId(), e.getMessage(), e);
        }
        if (key != null) {
            // Object uploaded but not referenced from the row: best-effort removal so no orphan stays in S3.
            try {
                emailEmlService.deleteQuietly(key);
            } catch (Exception e) {
                log.error("[EmailFollowUp] Nie udało się usunąć osieroconego EML: messageId={}, error={}",
                        saved.getId(), e.getMessage());
            }
        }
    }

    /** @return the contact the message was atomically attached to, or null when it must be routed as new */
    private Contact attach(EmailMessage saved, UUID tenantId, String inReplyTo, String ownMailbox) {
        Optional<EmailMessage> parent = emailMessageRepository.findByMessageIdHeader(inReplyTo, tenantId);
        if (parent.isEmpty() || parent.get().getContactId() == null) {
            return null;
        }
        Optional<Contact> contactOpt = contactService.findContactEntity(parent.get().getContactId(), tenantId);
        if (contactOpt.isEmpty()) {
            return null;
        }
        Contact contact = contactOpt.get();
        boolean open = "ASSIGNED".equals(contact.getStatus()) || "ACTIVE".equals(contact.getStatus())
                || "ON_HOLD".equals(contact.getStatus());
        if (!open || !"EMAIL".equals(contact.getChannel()) || contact.getAgentId() == null
                || contact.getStartedAt() == null) {
            return null;
        }
        if (!senderIsKnownParty(saved.getFromAddress(), contact, tenantId, ownMailbox)) {
            log.info("[EmailFollowUp] Nadawca {} nie jest stroną kontaktu {} - wiadomość {} rutowana jak nowa",
                    mask(saved.getFromAddress()), contact.getContactId(), saved.getId());
            return null;
        }
        if (!emailMessageRepository.attachToOpenAssignedContact(saved.getId(), saved.getMessageAt(),
                contact.getContactId(), tenantId, contact.getAgentId(), contact.getStartedAt())) {
            log.info("[EmailFollowUp] Kontakt {} nie spełnia już warunków dołączenia - wiadomość {} rutowana jak nowa",
                    contact.getContactId(), saved.getId());
            return null;
        }
        return contact;
    }

    private boolean senderIsKnownParty(String rawFrom, Contact contact, UUID tenantId, String ownMailbox) {
        Set<String> sender = normalise(rawFrom);
        // exactly one sender address (fail-closed on lists / unparsable values)
        if (sender.size() != 1) {
            return false;
        }
        Set<String> own = normalise(ownMailbox);
        if (sender.stream().anyMatch(own::contains)) {
            return false; // our own mailbox is never a customer
        }
        Set<String> parties = new HashSet<>();
        parties.addAll(normalise(contact.getRemoteAddress()));
        if (contact.getChannelMetadata() != null && contact.getChannelMetadata().get("fromAddress") != null) {
            parties.addAll(normalise(String.valueOf(contact.getChannelMetadata().get("fromAddress"))));
        }
        if (matches(sender, parties)) {
            return true;
        }
        if (contact.getCustomerId() != null) {
            Optional<Customer> customer = customerService.findById(contact.getCustomerId(), tenantId);
            if (customer.isPresent() && customer.get().getEmail() != null) {
                customer.get().getEmail().forEach(e -> parties.addAll(normalise(e)));
            }
            if (matches(sender, parties)) {
                return true;
            }
        }
        emailMessageRepository.findThreadCustomerSideAddresses(contact.getContactId(), tenantId)
                .forEach(a -> parties.addAll(normalise(a)));
        parties.removeAll(own);
        return matches(sender, parties);
    }

    private static boolean matches(Set<String> sender, Set<String> parties) {
        return sender.stream().anyMatch(parties::contains);
    }

    /** Address-only, trimmed, lower-case; handles "Name &lt;a@b&gt;" and comma-separated lists. */
    static Set<String> normalise(String header) {
        Set<String> out = new HashSet<>();
        if (header == null || header.isBlank()) {
            return out;
        }
        try {
            for (InternetAddress a : InternetAddress.parseHeader(header, false)) {
                add(out, a.getAddress());
            }
        } catch (Exception e) {
            add(out, header);
        }
        return out;
    }

    private static void add(Set<String> out, String address) {
        if (address != null && !address.isBlank()) {
            out.add(address.trim().toLowerCase(Locale.ROOT));
        }
    }

    /** First char of the local part + domain only, e.g. {@code k***@example.com}. */
    static String mask(String header) {
        Set<String> a = normalise(header);
        if (a.isEmpty()) {
            return "***";
        }
        String addr = a.iterator().next();
        int at = addr.indexOf('@');
        return at <= 0 ? "***" : addr.charAt(0) + "***" + addr.substring(at);
    }
}
