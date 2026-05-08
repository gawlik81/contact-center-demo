package com.contactcenter.domain;

import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.model.TenantTwilioConfig;
import com.contactcenter.domain.repository.TenantTwilioConfigRepository;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("TenantTwilioConfigRepository – izolacja multi-tenant")
@ExtendWith(MockitoExtension.class)
class TenantTwilioConfigRepositoryTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query mockQuery;

    private TenantTwilioConfigRepository repository;

    @BeforeEach
    void setUp() {
        repository = new TenantTwilioConfigRepository();
        ReflectionTestUtils.setField(repository, "em", entityManager);
        // setTenantContextInDb() calls createNativeQuery(String) – no class arg
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
        // findByTenantId() calls createNativeQuery(String, Class)
        lenient().when(entityManager.createNativeQuery(anyString(), any(Class.class))).thenReturn(mockQuery);
        lenient().when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
        lenient().when(mockQuery.getSingleResult()).thenReturn(null);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("findByTenantId()")
    class FindByTenantIdTests {

        @Test
        @DisplayName("zwraca Optional.empty() gdy brak konfiguracji")
        void findByTenantId_noConfig_returnsEmpty() {
            TenantContext.setTenantId(TENANT_A);
            when(mockQuery.getResultList()).thenReturn(Collections.emptyList());

            Optional<TenantTwilioConfig> result = repository.findByTenantId(TENANT_A);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("zwraca konfigurację gdy istnieje")
        void findByTenantId_configExists_returnsConfig() {
            TenantContext.setTenantId(TENANT_A);
            TenantTwilioConfig config = TenantTwilioConfig.builder()
                    .configId(UUID.randomUUID())
                    .tenantId(TENANT_A)
                    .accountSid("encrypted-sid")
                    .authToken("encrypted-token")
                    .build();
            when(mockQuery.getResultList()).thenReturn(List.of(config));

            Optional<TenantTwilioConfig> result = repository.findByTenantId(TENANT_A);

            assertThat(result).isPresent();
            assertThat(result.get().getTenantId()).isEqualTo(TENANT_A);
        }
    }

    @Nested
    @DisplayName("save() – walidacja cross-tenant")
    class SaveTests {

        @Test
        @DisplayName("save() z obcym tenant_id rzuca CrossTenantAccessException")
        void save_foreignTenantId_throwsCrossTenantAccessException() {
            TenantContext.setTenantId(TENANT_A);
            TenantTwilioConfig config = TenantTwilioConfig.builder()
                    .tenantId(TENANT_B)
                    .accountSid("encrypted-sid")
                    .authToken("encrypted-token")
                    .build();

            assertThatThrownBy(() -> repository.save(config))
                    .isInstanceOf(CrossTenantAccessException.class);
        }

        @Test
        @DisplayName("save() nowej encji wywołuje persist")
        void save_newEntity_callsPersist() {
            TenantContext.setTenantId(TENANT_A);
            TenantTwilioConfig config = TenantTwilioConfig.builder()
                    .tenantId(TENANT_A)
                    .accountSid("encrypted-sid")
                    .authToken("encrypted-token")
                    .build();

            TenantTwilioConfig saved = repository.save(config);

            verify(entityManager).persist(config);
            assertThat(saved).isSameAs(config);
        }

        @Test
        @DisplayName("save() istniejącej encji wywołuje merge")
        void save_existingEntity_callsMerge() {
            TenantContext.setTenantId(TENANT_A);
            UUID configId = UUID.randomUUID();
            TenantTwilioConfig config = TenantTwilioConfig.builder()
                    .configId(configId)
                    .tenantId(TENANT_A)
                    .accountSid("encrypted-sid")
                    .authToken("encrypted-token")
                    .build();
            when(entityManager.merge(config)).thenReturn(config);

            TenantTwilioConfig saved = repository.save(config);

            verify(entityManager).merge(config);
            assertThat(saved.getConfigId()).isEqualTo(configId);
        }
    }
}
