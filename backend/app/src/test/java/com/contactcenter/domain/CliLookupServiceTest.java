package com.contactcenter.domain;

import com.contactcenter.domain.model.Customer;
import com.contactcenter.domain.repository.CustomerRepository;
import com.contactcenter.domain.service.CliLookupService;
import com.contactcenter.domain.service.CustomerCliResult;
import com.contactcenter.infrastructure.config.RedisConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link CliLookupService}.
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>Cache hit – dane z Redis, brak wywołania DB</li>
 *   <li>Cache miss – zapytanie do DB, wynik zapisany w Redis</li>
 *   <li>Nieznany numer – null sentinel cache'owany, wynik empty</li>
 *   <li>Awaria Redis – fallback do DB, brak propagacji wyjątku</li>
 *   <li>Invalidacja cache – usunięcie kluczy dla wszystkich numerów klienta</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CliLookupService – CLI lookup klienta po numerze telefonu")
class CliLookupServiceTest {

    private static final UUID TENANT_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String PHONE     = "+48501234567";
    private static final String CACHE_KEY = "cache:customer:phone:" + PHONE;

    @Mock private CustomerRepository customerRepository;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;

    private CliLookupService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new CliLookupService(customerRepository, redisTemplate);
    }

    // =========================================================================
    // Cache hit scenariusze
    // =========================================================================

    @Nested
    @DisplayName("Cache hit")
    class CacheHitTests {

        @Test
        @DisplayName("Zwraca dane klienta z Redis bez odpytywania bazy danych")
        void shouldReturnCustomerFromCacheWithoutDbCall() {
            // given
            CustomerCliResult cached = buildCustomerCliResult();
            when(valueOperations.get(CACHE_KEY)).thenReturn(cached);

            // when
            Optional<CustomerCliResult> result = service.lookupCustomer(PHONE, TENANT_ID);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(result.get().firstName()).isEqualTo("Jan");
            assertThat(result.get().lastName()).isEqualTo("Kowalski");

            // Brak wywołania bazy danych
            verifyNoInteractions(customerRepository);
        }

        @Test
        @DisplayName("Zwraca empty dla null sentinel w cache (nieznany numer)")
        void shouldReturnEmptyForNullSentinelInCache() {
            // given
            when(valueOperations.get(CACHE_KEY)).thenReturn(CliLookupService.CACHE_NULL_SENTINEL);

            // when
            Optional<CustomerCliResult> result = service.lookupCustomer(PHONE, TENANT_ID);

            // then
            assertThat(result).isEmpty();
            verifyNoInteractions(customerRepository);
        }

        @Test
        @DisplayName("Ignoruje nieoczekiwany typ w cache i odpytuje bazę danych")
        void shouldFallbackToDbOnUnexpectedCacheType() {
            // given
            when(valueOperations.get(CACHE_KEY)).thenReturn(42); // Nieoczekiwany typ Integer
            when(customerRepository.findByPhoneNumber(PHONE, TENANT_ID)).thenReturn(Optional.empty());

            // when
            Optional<CustomerCliResult> result = service.lookupCustomer(PHONE, TENANT_ID);

            // then
            assertThat(result).isEmpty();
            verify(customerRepository).findByPhoneNumber(PHONE, TENANT_ID);
        }
    }

    // =========================================================================
    // Cache miss scenariusze
    // =========================================================================

    @Nested
    @DisplayName("Cache miss – zapytanie do bazy danych")
    class CacheMissTests {

        @Test
        @DisplayName("Odpytuje bazę, zapisuje wynik w Redis i zwraca CustomerCliResult")
        void shouldQueryDbAndCacheResultOnCacheMiss() {
            // given
            Customer customer = buildCustomer();
            when(valueOperations.get(CACHE_KEY)).thenReturn(null); // cache miss
            when(customerRepository.findByPhoneNumber(PHONE, TENANT_ID))
                    .thenReturn(Optional.of(customer));
            when(customerRepository.findLastContactsForCustomer(CUSTOMER_ID, TENANT_ID, 3))
                    .thenReturn(buildContactRows());

            // when
            Optional<CustomerCliResult> result = service.lookupCustomer(PHONE, TENANT_ID);

            // then
            assertThat(result).isPresent();
            CustomerCliResult cli = result.get();
            assertThat(cli.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(cli.firstName()).isEqualTo("Jan");
            assertThat(cli.lastName()).isEqualTo("Kowalski");
            assertThat(cli.phoneNumber()).isEqualTo(PHONE);
            assertThat(cli.lastContacts()).hasSize(2);

            // Wynik zapisany w Redis z prawidłowym TTL
            verify(valueOperations).set(CACHE_KEY, cli, RedisConfig.TTL_CLI_LOOKUP);
        }

        @Test
        @DisplayName("Cache'uje null sentinel gdy numer nieznany")
        void shouldCacheNullSentinelForUnknownNumber() {
            // given
            when(valueOperations.get(CACHE_KEY)).thenReturn(null); // cache miss
            when(customerRepository.findByPhoneNumber(PHONE, TENANT_ID))
                    .thenReturn(Optional.empty());

            // when
            Optional<CustomerCliResult> result = service.lookupCustomer(PHONE, TENANT_ID);

            // then
            assertThat(result).isEmpty();
            verify(valueOperations).set(
                    eq(CACHE_KEY),
                    eq(CliLookupService.CACHE_NULL_SENTINEL),
                    eq(RedisConfig.TTL_CLI_LOOKUP)
            );
        }

        @Test
        @DisplayName("Zwraca dane klienta bez kontaktów gdy tabela contact jest pusta")
        void shouldReturnCustomerWithEmptyContactsWhenNoneFound() {
            // given
            Customer customer = buildCustomer();
            when(valueOperations.get(CACHE_KEY)).thenReturn(null);
            when(customerRepository.findByPhoneNumber(PHONE, TENANT_ID))
                    .thenReturn(Optional.of(customer));
            when(customerRepository.findLastContactsForCustomer(CUSTOMER_ID, TENANT_ID, 3))
                    .thenReturn(List.of());

            // when
            Optional<CustomerCliResult> result = service.lookupCustomer(PHONE, TENANT_ID);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().lastContacts()).isEmpty();
        }
    }

    // =========================================================================
    // Obsługa błędów i edge cases
    // =========================================================================

    @Nested
    @DisplayName("Obsługa błędów i edge cases")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Fallback do DB gdy Redis rzuca wyjątek przy odczycie")
        void shouldFallbackToDbWhenRedisThrowsOnRead() {
            // given
            when(valueOperations.get(CACHE_KEY))
                    .thenThrow(new RuntimeException("Redis connection refused"));
            when(customerRepository.findByPhoneNumber(PHONE, TENANT_ID))
                    .thenReturn(Optional.empty());

            // when – nie rzuca wyjątku
            assertThatCode(() -> service.lookupCustomer(PHONE, TENANT_ID))
                    .doesNotThrowAnyException();

            // Fallback do DB
            verify(customerRepository).findByPhoneNumber(PHONE, TENANT_ID);
        }

        @Test
        @DisplayName("Zwraca dane klienta gdy zapis do Redis nie powiedzie się")
        void shouldReturnResultEvenWhenCacheWriteFails() {
            // given
            Customer customer = buildCustomer();
            when(valueOperations.get(CACHE_KEY)).thenReturn(null);
            when(customerRepository.findByPhoneNumber(PHONE, TENANT_ID))
                    .thenReturn(Optional.of(customer));
            when(customerRepository.findLastContactsForCustomer(CUSTOMER_ID, TENANT_ID, 3))
                    .thenReturn(List.of());
            doThrow(new RuntimeException("Redis write failed"))
                    .when(valueOperations).set(anyString(), any(), any());

            // when – nie rzuca wyjątku
            Optional<CustomerCliResult> result = service.lookupCustomer(PHONE, TENANT_ID);

            // then – wynik zwrócony mimo błędu Redis
            assertThat(result).isPresent();
            assertThat(result.get().customerId()).isEqualTo(CUSTOMER_ID);
        }

        @Test
        @DisplayName("Zwraca empty dla null/blank numeru telefonu")
        void shouldReturnEmptyForNullPhone() {
            // when
            Optional<CustomerCliResult> resultNull  = service.lookupCustomer(null, TENANT_ID);
            Optional<CustomerCliResult> resultBlank = service.lookupCustomer("  ", TENANT_ID);

            // then
            assertThat(resultNull).isEmpty();
            assertThat(resultBlank).isEmpty();
            verifyNoInteractions(customerRepository, redisTemplate);
        }

        @Test
        @DisplayName("Zwraca dane klienta gdy fetchLastContacts rzuca wyjątek")
        void shouldReturnCustomerWithEmptyContactsWhenContactsFetchFails() {
            // given
            Customer customer = buildCustomer();
            when(valueOperations.get(CACHE_KEY)).thenReturn(null);
            when(customerRepository.findByPhoneNumber(PHONE, TENANT_ID))
                    .thenReturn(Optional.of(customer));
            when(customerRepository.findLastContactsForCustomer(CUSTOMER_ID, TENANT_ID, 3))
                    .thenThrow(new RuntimeException("DB error"));

            // when – nie rzuca wyjątku
            Optional<CustomerCliResult> result = service.lookupCustomer(PHONE, TENANT_ID);

            // then – klient zwrócony z pustą listą kontaktów
            assertThat(result).isPresent();
            assertThat(result.get().lastContacts()).isEmpty();
        }
    }

    // =========================================================================
    // Cache invalidation
    // =========================================================================

    @Nested
    @DisplayName("Cache invalidation")
    class CacheInvalidationTests {

        @Test
        @DisplayName("Usuwa klucze z Redis dla wszystkich numerów klienta")
        void shouldDeleteCacheKeysForAllPhoneNumbers() {
            // given
            Customer customer = buildCustomerWithMultiplePhones();
            when(redisTemplate.delete("cache:customer:phone:+48501234567")).thenReturn(true);
            when(redisTemplate.delete("cache:customer:phone:+48601234567")).thenReturn(true);

            // when
            service.invalidateCacheForCustomer(customer);

            // then
            verify(redisTemplate).delete("cache:customer:phone:+48501234567");
            verify(redisTemplate).delete("cache:customer:phone:+48601234567");
        }

        @Test
        @DisplayName("Obsługuje błąd Redis przy invalidacji bez propagacji wyjątku")
        void shouldNotThrowWhenCacheInvalidationFails() {
            // given
            Customer customer = buildCustomerWithMultiplePhones();
            when(redisTemplate.delete(anyString()))
                    .thenThrow(new RuntimeException("Redis unavailable"));

            // when – nie rzuca wyjątku
            assertThatCode(() -> service.invalidateCacheForCustomer(customer))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Nie wywołuje Redis dla klienta bez numerów telefonu")
        void shouldDoNothingForCustomerWithoutPhones() {
            // given
            Customer customer = Customer.builder()
                    .customerId(CUSTOMER_ID)
                    .tenantId(TENANT_ID)
                    .phone(new ArrayList<>())
                    .build();

            // when
            service.invalidateCacheForCustomer(customer);

            // then
            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("Obsługuje null customer bez wyjątku")
        void shouldHandleNullCustomer() {
            assertThatCode(() -> service.invalidateCacheForCustomer(null))
                    .doesNotThrowAnyException();
            verifyNoInteractions(redisTemplate);
        }
    }

    // =========================================================================
    // Cache key builder
    // =========================================================================

    @Test
    @DisplayName("buildCacheKey generuje klucz zgodny z konwencją Redis")
    void shouldBuildCorrectCacheKey() {
        assertThat(CliLookupService.buildCacheKey("+48501234567"))
                .isEqualTo("cache:customer:phone:+48501234567");
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private Customer buildCustomer() {
        return Customer.builder()
                .customerId(CUSTOMER_ID)
                .tenantId(TENANT_ID)
                .firstName("Jan")
                .lastName("Kowalski")
                .phone(List.of(PHONE))
                .email(List.of("jan@example.com"))
                .deleted(false)
                .build();
    }

    private Customer buildCustomerWithMultiplePhones() {
        return Customer.builder()
                .customerId(CUSTOMER_ID)
                .tenantId(TENANT_ID)
                .firstName("Jan")
                .lastName("Kowalski")
                .phone(List.of("+48501234567", "+48601234567"))
                .email(List.of())
                .deleted(false)
                .build();
    }

    private CustomerCliResult buildCustomerCliResult() {
        return new CustomerCliResult(
                CUSTOMER_ID,
                "Jan",
                "Kowalski",
                PHONE,
                List.of(
                        new CustomerCliResult.ContactSummary(
                                UUID.randomUUID(), "PHONE", "COMPLETED", Instant.now().minusSeconds(3600))
                )
        );
    }

    /**
     * Buduje rows jak zwraca {@code em.createNativeQuery().getResultList()} –
     * tablice Object[] z polami: [contact_id, channel, status, started_at].
     */
    private List<Object[]> buildContactRows() {
        UUID contact1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID contact2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        return List.of(
                new Object[]{contact1, "PHONE", "COMPLETED",
                        java.sql.Timestamp.from(Instant.now().minusSeconds(3600))},
                new Object[]{contact2, "EMAIL", "COMPLETED",
                        java.sql.Timestamp.from(Instant.now().minusSeconds(7200))}
        );
    }
}
