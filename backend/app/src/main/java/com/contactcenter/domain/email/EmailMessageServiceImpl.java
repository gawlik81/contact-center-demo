package com.contactcenter.domain.email;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class EmailMessageServiceImpl implements EmailMessageService {

    private final EmailMessageRepository emailMessageRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<EmailMessage> findById(UUID messageId) {
        return emailMessageRepository.findById(messageId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EmailMessage> findByContactId(UUID contactId, UUID tenantId, Pageable pageable) {
        return emailMessageRepository.findByContactId(contactId, tenantId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EmailMessage> findFirstInboundByContactId(UUID contactId, UUID tenantId) {
        return emailMessageRepository.findFirstInboundByContactId(contactId, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EmailMessage> findByThreadRootMessageId(
            String originalMessageIdHeader, UUID tenantId, Pageable pageable) {
        return emailMessageRepository.findByThreadRootMessageId(originalMessageIdHeader, tenantId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EmailMessage> findAll(Pageable pageable) {
        return emailMessageRepository.findAll(pageable);
    }

    @Override
    @Transactional
    public int detachContactReferences(UUID tenantId, List<UUID> contactIds) {
        return emailMessageRepository.detachContactReferences(tenantId, contactIds);
    }
}
