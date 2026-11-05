package com.contactcenter.domain.email;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-163 (CR FE): {@code findByThreadRootMessageId} musi miec deterministyczny porzadek przy rownych
 * {@code created_at} (tie-breaker po {@code id}), inaczej stronicowanie OFFSET/LIMIT moze zdublowac
 * lub zgubic wiadomosc na granicy stron, a FE scala strony po id.
 */
@DisplayName("EmailMessageRepository#findByThreadRootMessageId - stabilna kolejnosc (tie-breaker po id)")
@SuppressWarnings({"unchecked", "rawtypes"})
class EmailMessageRepositoryThreadOrderTest {

    @Test
    @DisplayName("JPQL sortuje po createdAt ASC, a przy remisie po id ASC")
    void orderByHasIdTieBreaker() {
        EntityManager em = mock(EntityManager.class);
        Query nativeQuery = mock(Query.class, Mockito.RETURNS_SELF);
        when(em.createNativeQuery(anyString())).thenReturn(nativeQuery);
        TypedQuery listQuery = mock(TypedQuery.class, Mockito.RETURNS_SELF);
        when(listQuery.getResultList()).thenReturn(List.of());
        TypedQuery countQuery = mock(TypedQuery.class, Mockito.RETURNS_SELF);
        when(countQuery.getSingleResult()).thenReturn(0L);
        when(em.createQuery(anyString(), eq(EmailMessage.class))).thenReturn(listQuery);
        when(em.createQuery(anyString(), eq(Long.class))).thenReturn(countQuery);

        EmailMessageRepository repository = new EmailMessageRepository();
        ReflectionTestUtils.setField(repository, "em", em);

        repository.findByThreadRootMessageId("<root@x>", UUID.randomUUID(), PageRequest.of(0, 50));

        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(em).createQuery(jpql.capture(), eq(EmailMessage.class));
        assertThat(jpql.getValue()).contains("ORDER BY m.createdAt ASC, m.id ASC");
    }
}
