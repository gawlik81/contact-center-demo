package com.contactcenter.domain.social;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.infrastructure.social.SocialAdapterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Delegacja {@link SocialMessageService#purgeByContactIds} do repozytorium (BE-125); SQL — w {@code SocialMessagePurgeIntegrationTest}. */
@ExtendWith(MockitoExtension.class)
@DisplayName("SocialMessageServiceImpl.purgeByContactIds – delegacja (BE-125)")
class SocialMessageServicePurgeTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock private SocialIntegrationRepository socialIntegrationRepository;
    @Mock private SocialMessageRepository socialMessageRepository;
    @Mock private ContactService contactService;
    @Mock private SocialAdapterRegistry adapterRegistry;
    @Mock private RabbitTemplate rabbitTemplate;

    private SocialMessageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SocialMessageServiceImpl(
                socialIntegrationRepository, socialMessageRepository, contactService, adapterRegistry, rabbitTemplate);
    }

    @Test
    @DisplayName("niepusta lista → deleguje do repozytorium i zwraca liczbę usuniętych wierszy")
    void delegatesToRepository() {
        List<UUID> contacts = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(socialMessageRepository.purgeByContactIds(TENANT, contacts)).thenReturn(7);

        assertThat(service.purgeByContactIds(TENANT, contacts)).isEqualTo(7);

        verify(socialMessageRepository).purgeByContactIds(TENANT, contacts);
    }

    @Test
    @DisplayName("null i pusta lista → 0 bez dostępu do bazy")
    void nullOrEmpty_isNoOp() {
        assertThat(service.purgeByContactIds(TENANT, null)).isZero();
        assertThat(service.purgeByContactIds(TENANT, List.of())).isZero();

        verifyNoInteractions(socialMessageRepository);
    }
}
