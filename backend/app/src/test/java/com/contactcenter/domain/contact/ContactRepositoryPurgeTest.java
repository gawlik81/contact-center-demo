package com.contactcenter.domain.contact;

import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link ContactRepository#deleteBatchOlderThan} (retencja EPIC-29, BE-113).
 *
 * <p><strong>Dlaczego ten test istnieje osobno:</strong> ta metoda jest krytyczna dla izolacji
 * tenantów na tabeli partycjonowanej {@code contact} — zweryfikowano EMPIRYCZNIE (manualnie,
 * poza automatycznym zestawem testów, bezpośrednio na żywej instancji PostgreSQL z tym schematem)
 * że pozornie równoważny wzorzec {@code DELETE ... WHERE ctid IN (SELECT ctid ... LIMIT N)} jest
 * NIEBEZPIECZNY na tabelach partycjonowanych: {@code ctid} (fizyczny adres blok+offset) nie jest
 * unikalny globalnie – ta sama para współrzędnych występuje niezależnie w wielu partycjach, więc
 * DELETE dopasowuje i usuwa również niepowiązane wiersze innych tenantów w innych partycjach
 * miesięcznych. Zaobserwowano: subquery ograniczone do jednego tenanta i {@code LIMIT 100} użyte
 * przez {@code ctid IN (...)} usunęło łącznie 168 wierszy w całej tabeli (wszystkie partycje,
 * wszyscy tenanci) zamiast zamierzonych 100 dla jednego tenanta. Wzorzec {@code DELETE ... USING
 * batch WHERE contact_id = batch.contact_id AND started_at = batch.started_at} (pełny klucz
 * główny, w tym kolumnę partycjonowania) zweryfikowano jako poprawny – identyczny eksperyment
 * usunął dokładnie 100 wierszy, wyłącznie dla właściwego tenanta.
 *
 * <p>Test jednostkowy (mockowany {@link EntityManager}, zgodnie z konwencją projektu – brak H2/
 * Testcontainers dla testów repozytoriów) weryfikuje, że wygenerowany SQL faktycznie stosuje ten
 * bezpieczny wzorzec, żeby przyszła "uproszczenie" z powrotem do {@code ctid} zostało wychwycone.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ContactRepository.deleteBatchOlderThan – purge batchowany (BE-113)")
class ContactRepositoryPurgeTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant CUTOFF  = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private EntityManager entityManager;

    /** Query zwracana dla {@code set_tenant_context(...)} (wywoływana przez setTenantContextInDb). */
    @Mock
    private Query rlsQuery;

    /** Query zwracana dla właściwego {@code DELETE FROM contact ...} – ta jest przedmiotem testów. */
    @Mock
    private Query deleteQuery;

    private ContactRepository contactRepository;

    @BeforeEach
    void setUp() {
        // assertSameTenant() czyta TenantContext – w produkcji ustawiane przez TenantFilter,
        // tutaj symulowane jawnie (wzorzec CrossTenantAccessTest).
        TenantContext.setTenantId(TENANT_ID);

        contactRepository = new ContactRepository(
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(contactRepository, "em", entityManager);

        // Dwie różne natywne zapytania w deleteBatchOlderThan: setTenantContextInDb (RLS) i DELETE.
        // Oba używają nazwy parametru "tenantId" – rozróżniamy je po treści SQL, żeby uniknąć
        // kolizji przy weryfikacji setParameter na wspólnym mocku.
        when(entityManager.createNativeQuery(argThat(sql -> sql != null && sql.contains("set_tenant_context"))))
                .thenReturn(rlsQuery);
        when(entityManager.createNativeQuery(argThat(sql -> sql != null && sql.contains("DELETE FROM contact"))))
                .thenReturn(deleteQuery);
        when(rlsQuery.setParameter(anyString(), any())).thenReturn(rlsQuery);
        when(deleteQuery.setParameter(anyString(), any())).thenReturn(deleteQuery);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("Konstrukcja SQL")
    class SqlConstruction {

        @Test
        @DisplayName("SQL NIE zawiera ctid (niebezpieczne na tabeli partycjonowanej)")
        void sql_doesNotUseCtid() {
            when(deleteQuery.getResultList()).thenReturn(List.of());

            contactRepository.deleteBatchOlderThan(TENANT_ID, CUTOFF, 100);

            assertThat(capturedDeleteSql()).doesNotContainIgnoringCase("ctid");
        }

        @Test
        @DisplayName("SQL identyfikuje wiersze do usunięcia przez pełny klucz główny (contact_id + started_at)")
        void sql_usesFullPrimaryKey() {
            when(deleteQuery.getResultList()).thenReturn(List.of());

            contactRepository.deleteBatchOlderThan(TENANT_ID, CUTOFF, 100);

            String sql = capturedDeleteSql();
            assertThat(sql).contains("contact_id").contains("started_at");
            assertThat(sql).containsIgnoringCase("DELETE FROM contact");
            assertThat(sql).containsIgnoringCase("RETURNING");
        }

        @Test
        @DisplayName("parametry SQL: tenantId, cutoff, batchSize przekazane poprawnie")
        void sql_parametersAreBound() {
            when(deleteQuery.getResultList()).thenReturn(List.of());

            contactRepository.deleteBatchOlderThan(TENANT_ID, CUTOFF, 42);

            verify(deleteQuery).setParameter("tenantId", TENANT_ID.toString());
            verify(deleteQuery).setParameter("cutoff", CUTOFF);
            verify(deleteQuery).setParameter("batchSize", 42);
        }

        /**
         * Przechwytuje SQL przekazany do {@code entityManager.createNativeQuery(...)} spośród
         * wszystkich wywołań (RLS + DELETE) i zwraca ten zawierający właściwy DELETE.
         */
        private String capturedDeleteSql() {
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(entityManager, org.mockito.Mockito.atLeastOnce()).createNativeQuery(sqlCaptor.capture());
            return sqlCaptor.getAllValues().stream()
                    .filter(sql -> sql.contains("DELETE FROM contact"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Nie znaleziono zapytania DELETE FROM contact"));
        }
    }

    @Nested
    @DisplayName("Wynik")
    class ResultMapping {

        @Test
        @DisplayName("zwraca listę UUID usuniętych kontaktów z RETURNING")
        void returnsDeletedContactIds() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            when(deleteQuery.getResultList()).thenReturn(List.of(id1, id2));

            List<UUID> result = contactRepository.deleteBatchOlderThan(TENANT_ID, CUTOFF, 100);

            assertThat(result).containsExactlyInAnyOrder(id1, id2);
        }

        @Test
        @DisplayName("brak kwalifikujących się wierszy -> pusta lista")
        void noRowsMatch_returnsEmptyList() {
            when(deleteQuery.getResultList()).thenReturn(List.of());

            List<UUID> result = contactRepository.deleteBatchOlderThan(TENANT_ID, CUTOFF, 100);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("wartości RETURNING zwrócone jako String (sterownik JDBC) są konwertowane do UUID")
        void resultsReturnedAsStrings_areConvertedToUuid() {
            UUID id = UUID.randomUUID();
            when(deleteQuery.getResultList()).thenReturn(List.of(id.toString()));

            List<UUID> result = contactRepository.deleteBatchOlderThan(TENANT_ID, CUTOFF, 100);

            assertThat(result).containsExactly(id);
        }
    }
}
