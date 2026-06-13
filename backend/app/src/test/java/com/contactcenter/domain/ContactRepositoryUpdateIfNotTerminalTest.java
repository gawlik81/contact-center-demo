package com.contactcenter.domain;

import com.contactcenter.domain.contact.ContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link ContactRepository#updateContactStatusIfNotTerminal}.
 *
 * <p>Metoda wykonuje atomowy conditional UPDATE:
 * <pre>
 * UPDATE contact
 *    SET status = ?, ended_at = ?
 *  WHERE contact_id = ? AND tenant_id = ?
 *    AND status NOT IN ('COMPLETED', 'ABANDONED', 'NOT_REACHED', 'ERROR', 'TRANSFERRED')
 * </pre>
 *
 * <p>Testy mockują {@link JdbcTemplate} na poziomie wywołania {@code update()},
 * symulując zachowanie bazy danych (ile wierszy zostało zaktualizowanych).
 * Podejście jednostkowe – bez bazy danych – pozwala precyzyjnie zweryfikować:
 * <ul>
 *   <li>Czy SQL zawiera klauzulę {@code NOT IN} z listą statusów terminalnych.</li>
 *   <li>Czy wartość zwracana ({@code true}/{@code false}) odpowiada wyniku UPDATE.</li>
 *   <li>Czy null-safety dla {@code contactId}/{@code tenantId} działa prawidłowo.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ContactRepository.updateContactStatusIfNotTerminal – conditional UPDATE")
class ContactRepositoryUpdateIfNotTerminalTest {

    private static final UUID CONTACT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_ID  = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant ENDED_AT = Instant.parse("2025-05-28T10:00:00Z");

    @Mock private JdbcTemplate jdbcTemplate;

    private ContactRepository contactRepository;

    @BeforeEach
    void setUp() {
        // ContactRepository nie jest beanem Spring w testach jednostkowych –
        // tworzymy instancję ręcznie i wstrzykujemy mock przez ReflectionTestUtils.
        // ObjectMapper jest potrzebny do konstruktora, ale nie jest używany przez testowaną metodę.
        contactRepository = new ContactRepository(jdbcTemplate, new com.fasterxml.jackson.databind.ObjectMapper());
        // EntityManager jest wymagany przez TenantAwareRepository – wstrzykujemy mock
        jakarta.persistence.EntityManager em = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
        ReflectionTestUtils.setField(contactRepository, "em", em);
    }

    // =========================================================================
    // Scenariusz: baza zwraca 1 (update wykonany) → metoda zwraca true
    // =========================================================================

    @Nested
    @DisplayName("Baza zwraca 1 zaktualizowany wiersz → metoda zwraca true")
    class DatabaseUpdatedOneRow {

        @Test
        @DisplayName("status QUEUED → baza akceptuje UPDATE, metoda zwraca true")
        void queued_databaseUpdates_returnsTrue() {
            // JdbcTemplate.update() zwraca 1 – symulacja statusu QUEUED (nie terminalny)
            when(jdbcTemplate.update(anyString(), any(), any(), any(), any()))
                .thenReturn(1);

            boolean result = contactRepository.updateContactStatusIfNotTerminal(
                CONTACT_ID, TENANT_ID, "ABANDONED", ENDED_AT);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("status ASSIGNED → baza akceptuje UPDATE, metoda zwraca true")
        void assigned_databaseUpdates_returnsTrue() {
            when(jdbcTemplate.update(anyString(), any(), any(), any(), any()))
                .thenReturn(1);

            boolean result = contactRepository.updateContactStatusIfNotTerminal(
                CONTACT_ID, TENANT_ID, "ABANDONED", ENDED_AT);

            assertThat(result).isTrue();
        }
    }

    // =========================================================================
    // Scenariusz: baza zwraca 0 (WHERE odfiltrował) → metoda zwraca false
    // =========================================================================

    @Nested
    @DisplayName("Baza zwraca 0 zaktualizowanych wierszy → metoda zwraca false")
    class DatabaseUpdatedZeroRows {

        @Test
        @DisplayName("status terminalny → WHERE NOT IN odfiltrował wiersz, metoda zwraca false")
        void terminalStatus_databaseSkipsUpdate_returnsFalse() {
            // JdbcTemplate.update() zwraca 0 – symulacja statusu COMPLETED (terminalny)
            // Klauzula AND status NOT IN (...) odfiltruje wiersz w bazie
            when(jdbcTemplate.update(anyString(), any(), any(), any(), any()))
                .thenReturn(0);

            boolean result = contactRepository.updateContactStatusIfNotTerminal(
                CONTACT_ID, TENANT_ID, "ABANDONED", ENDED_AT);

            assertThat(result).isFalse();
        }
    }

    // =========================================================================
    // Scenariusz: SQL zawiera klauzulę NOT IN z wszystkimi statusami terminalnymi
    // =========================================================================

    @Nested
    @DisplayName("SQL zawiera klauzulę NOT IN z wszystkimi statusami terminalnymi")
    class SqlContainsNotInClause {

        @Test
        @DisplayName("SQL przekazany do JdbcTemplate zawiera NOT IN z 5 statusami terminalnymi")
        void sql_containsAllTerminalStatuses() {
            when(jdbcTemplate.update(anyString(), any(), any(), any(), any()))
                .thenReturn(0);

            contactRepository.updateContactStatusIfNotTerminal(
                CONTACT_ID, TENANT_ID, "ABANDONED", ENDED_AT);

            // Przechwytujemy SQL przekazany do jdbcTemplate.update()
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), any(), any(), any(), any());

            String sql = sqlCaptor.getValue();
            // Weryfikujemy że SQL zawiera ochronę przed wszystkimi statusami terminalnymi
            assertThat(sql).containsIgnoringCase("NOT IN");
            assertThat(sql).contains("COMPLETED");
            assertThat(sql).contains("ABANDONED");
            assertThat(sql).contains("NOT_REACHED");
            assertThat(sql).contains("ERROR");
            assertThat(sql).contains("TRANSFERRED");
        }

        @ParameterizedTest(name = "status terminalny ''{0}'' jest w klauzuli NOT IN")
        @ValueSource(strings = {"COMPLETED", "ABANDONED", "NOT_REACHED", "ERROR", "TRANSFERRED"})
        @DisplayName("każdy status terminalny pojawia się w SQL NOT IN")
        void eachTerminalStatus_appearsInSql(String terminalStatus) {
            when(jdbcTemplate.update(anyString(), any(), any(), any(), any()))
                .thenReturn(0);

            contactRepository.updateContactStatusIfNotTerminal(
                CONTACT_ID, TENANT_ID, "ABANDONED", ENDED_AT);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), any(), any(), any(), any());

            assertThat(sqlCaptor.getValue()).contains(terminalStatus);
        }
    }

    // =========================================================================
    // Scenariusz: null-safety dla contactId i tenantId
    // =========================================================================

    @Nested
    @DisplayName("Null-safety – brak wywołania JdbcTemplate gdy ID jest null")
    class NullSafety {

        @Test
        @DisplayName("contactId null → natychmiastowy zwrot false, brak wywołania JdbcTemplate")
        void nullContactId_returnsFalse_withoutDbCall() {
            boolean result = contactRepository.updateContactStatusIfNotTerminal(
                null, TENANT_ID, "ABANDONED", ENDED_AT);

            assertThat(result).isFalse();
            verify(jdbcTemplate, org.mockito.Mockito.never())
                .update(anyString(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("tenantId null → natychmiastowy zwrot false, brak wywołania JdbcTemplate")
        void nullTenantId_returnsFalse_withoutDbCall() {
            boolean result = contactRepository.updateContactStatusIfNotTerminal(
                CONTACT_ID, null, "ABANDONED", ENDED_AT);

            assertThat(result).isFalse();
            verify(jdbcTemplate, org.mockito.Mockito.never())
                .update(anyString(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("oba null → natychmiastowy zwrot false, brak wywołania JdbcTemplate")
        void bothNull_returnsFalse_withoutDbCall() {
            boolean result = contactRepository.updateContactStatusIfNotTerminal(
                null, null, "ABANDONED", ENDED_AT);

            assertThat(result).isFalse();
            verify(jdbcTemplate, org.mockito.Mockito.never())
                .update(anyString(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("endedAt null → wywołanie JdbcTemplate z null (dopuszczalne)")
        void nullEndedAt_callsJdbcTemplate_withNullTimestamp() {
            when(jdbcTemplate.update(anyString(), any(), any(), any(), any()))
                .thenReturn(1);

            boolean result = contactRepository.updateContactStatusIfNotTerminal(
                CONTACT_ID, TENANT_ID, "ABANDONED", null);

            assertThat(result).isTrue();
            verify(jdbcTemplate).update(anyString(), any(), any(), any(), any());
        }
    }
}
