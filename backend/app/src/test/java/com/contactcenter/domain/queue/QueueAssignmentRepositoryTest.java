package com.contactcenter.domain.queue;

import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link QueueAssignmentRepository}.
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>Poprawność SQL (fragmenty, parametry) dla każdej metody</li>
 *   <li>Izolację cross-tenant – zawołanie set_tenant_context przed każdym zapytaniem</li>
 *   <li>Logikę UNION w {@code resolveEligibleAgentIds} – deduplikacja i pusty wynik</li>
 *   <li>Atomowość podmian (DELETE + INSERT) w {@code replaceDirectAgents} i {@code replaceGroups}</li>
 *   <li>Odczyt i zapis flagi {@code all_agents}</li>
 * </ul>
 *
 * <p>Podejście: mockowanie {@link EntityManager} – testy jednostkowe bez połączenia z DB.
 * Weryfikujemy poprawność wywołań SQL (przez verify/contains), nie wyniki bazy.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("QueueAssignmentRepository – przypisanie agentów i grup do kolejki")
class QueueAssignmentRepositoryTest {

    private static final UUID TENANT_A  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B  = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID QUEUE_ID  = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AGENT_1   = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGENT_2   = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID GROUP_1   = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID GROUP_2   = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query mockQuery;

    private QueueAssignmentRepository repository;

    @BeforeEach
    void setUp() {
        repository = new QueueAssignmentRepository();
        ReflectionTestUtils.setField(repository, "em", entityManager);

        // Domyślne stubowanie dla set_tenant_context (używane we wszystkich metodach)
        when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
        when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
        when(mockQuery.getSingleResult()).thenReturn(null);
        when(mockQuery.getResultList()).thenReturn(List.of());
        when(mockQuery.executeUpdate()).thenReturn(1);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    // =========================================================================
    // isAllAgents
    // =========================================================================

    @Nested
    @DisplayName("isAllAgents() – odczyt i zmiana flagi")
    class IsAllAgentsTests {

        @Test
        @DisplayName("zwraca false gdy DB zwróci 0")
        void isAllAgents_whenDbReturnsZero_returnsFalse() {
            TenantContext.setTenantId(TENANT_A);
            when(mockQuery.getSingleResult()).thenReturn(0);

            boolean result = repository.isAllAgents(QUEUE_ID, TENANT_A);

            assertThat(result).isFalse();
            verify(entityManager, atLeastOnce()).createNativeQuery(contains("all_agents"));
        }

        @Test
        @DisplayName("zwraca true gdy DB zwróci 1")
        void isAllAgents_whenDbReturnsOne_returnsTrue() {
            TenantContext.setTenantId(TENANT_A);
            when(mockQuery.getSingleResult()).thenReturn(1);

            boolean result = repository.isAllAgents(QUEUE_ID, TENANT_A);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("zwraca false gdy DB zwróci null")
        void isAllAgents_whenDbReturnsNull_returnsFalse() {
            TenantContext.setTenantId(TENANT_A);
            when(mockQuery.getSingleResult()).thenReturn(null);

            boolean result = repository.isAllAgents(QUEUE_ID, TENANT_A);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("ustawia tenant context w DB przed zapytaniem")
        void isAllAgents_setsTenantContextBeforeQuery() {
            TenantContext.setTenantId(TENANT_A);
            when(mockQuery.getSingleResult()).thenReturn(0);

            repository.isAllAgents(QUEUE_ID, TENANT_A);

            verify(entityManager, atLeastOnce()).createNativeQuery(contains("set_tenant_context"));
        }
    }

    // =========================================================================
    // setAllAgents
    // =========================================================================

    @Nested
    @DisplayName("setAllAgents() – aktualizacja flagi all_agents")
    class SetAllAgentsTests {

        @Test
        @DisplayName("wykonuje UPDATE z poprawnymi parametrami gdy allAgents=true")
        void setAllAgents_executesUpdateWithCorrectParams() {
            TenantContext.setTenantId(TENANT_A);

            repository.setAllAgents(QUEUE_ID, TENANT_A, true);

            verify(entityManager, atLeastOnce()).createNativeQuery(contains("UPDATE queue"));
            verify(mockQuery, atLeastOnce()).setParameter("allAgents", true);
            verify(mockQuery, atLeastOnce()).setParameter("queueId", QUEUE_ID.toString());
            verify(mockQuery, atLeastOnce()).setParameter("tenantId", TENANT_A.toString());
        }

        @Test
        @DisplayName("wykonuje UPDATE z allAgents=false")
        void setAllAgents_withFalse_executesUpdate() {
            TenantContext.setTenantId(TENANT_A);

            repository.setAllAgents(QUEUE_ID, TENANT_A, false);

            verify(mockQuery, atLeastOnce()).setParameter("allAgents", false);
            verify(mockQuery, atLeastOnce()).executeUpdate();
        }
    }

    // =========================================================================
    // findDirectAgentIds
    // =========================================================================

    @Nested
    @DisplayName("findDirectAgentIds() – bezpośrednio przypisani agenci")
    class FindDirectAgentIdsTests {

        @Test
        @DisplayName("zwraca pustą listę gdy brak przypisań")
        void findDirectAgentIds_noAssignments_returnsEmptyList() {
            TenantContext.setTenantId(TENANT_A);
            when(mockQuery.getResultList()).thenReturn(List.of());

            List<UUID> result = repository.findDirectAgentIds(QUEUE_ID, TENANT_A);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("zwraca listę UUID agentów z queue_agent")
        void findDirectAgentIds_withAgents_returnsMappedUuids() {
            TenantContext.setTenantId(TENANT_A);
            // Mockujemy kolejne wywołania: pierwsze to set_tenant_context (getSingleResult),
            // drugie to query agentów (getResultList)
            Query tenantQuery = mock(Query.class);
            Query agentQuery = mock(Query.class);

            when(entityManager.createNativeQuery(contains("set_tenant_context"))).thenReturn(tenantQuery);
            when(tenantQuery.setParameter(anyString(), any())).thenReturn(tenantQuery);
            when(tenantQuery.getSingleResult()).thenReturn(null);

            when(entityManager.createNativeQuery(contains("queue_agent"))).thenReturn(agentQuery);
            when(agentQuery.setParameter(anyString(), any())).thenReturn(agentQuery);
            when(agentQuery.getResultList()).thenReturn(List.of(AGENT_1.toString(), AGENT_2.toString()));

            List<UUID> result = repository.findDirectAgentIds(QUEUE_ID, TENANT_A);

            assertThat(result).containsExactlyInAnyOrder(AGENT_1, AGENT_2);
        }

        @Test
        @DisplayName("przekazuje queueId jako parametr do zapytania")
        void findDirectAgentIds_passesQueueIdParameter() {
            TenantContext.setTenantId(TENANT_A);

            repository.findDirectAgentIds(QUEUE_ID, TENANT_A);

            verify(mockQuery, atLeastOnce()).setParameter("queueId", QUEUE_ID.toString());
        }
    }

    // =========================================================================
    // findGroupIds
    // =========================================================================

    @Nested
    @DisplayName("findGroupIds() – grupy przypisane do kolejki")
    class FindGroupIdsTests {

        @Test
        @DisplayName("zwraca pustą listę gdy brak grup")
        void findGroupIds_noGroups_returnsEmptyList() {
            TenantContext.setTenantId(TENANT_A);
            when(mockQuery.getResultList()).thenReturn(List.of());

            List<UUID> result = repository.findGroupIds(QUEUE_ID, TENANT_A);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("zwraca listę UUID grup z queue_agent_group")
        void findGroupIds_withGroups_returnsMappedUuids() {
            TenantContext.setTenantId(TENANT_A);

            Query tenantQuery = mock(Query.class);
            Query groupQuery = mock(Query.class);

            when(entityManager.createNativeQuery(contains("set_tenant_context"))).thenReturn(tenantQuery);
            when(tenantQuery.setParameter(anyString(), any())).thenReturn(tenantQuery);
            when(tenantQuery.getSingleResult()).thenReturn(null);

            when(entityManager.createNativeQuery(contains("queue_agent_group"))).thenReturn(groupQuery);
            when(groupQuery.setParameter(anyString(), any())).thenReturn(groupQuery);
            when(groupQuery.getResultList()).thenReturn(List.of(GROUP_1.toString(), GROUP_2.toString()));

            List<UUID> result = repository.findGroupIds(QUEUE_ID, TENANT_A);

            assertThat(result).containsExactlyInAnyOrder(GROUP_1, GROUP_2);
        }

        @Test
        @DisplayName("przekazuje queueId jako parametr do zapytania")
        void findGroupIds_passesQueueIdParameter() {
            TenantContext.setTenantId(TENANT_A);

            repository.findGroupIds(QUEUE_ID, TENANT_A);

            verify(mockQuery, atLeastOnce()).setParameter("queueId", QUEUE_ID.toString());
        }
    }

    // =========================================================================
    // resolveEligibleAgentIds – KLUCZOWE dla routingu
    // =========================================================================

    @Nested
    @DisplayName("resolveEligibleAgentIds() – UNION bezpośrednich i przez grupy")
    class ResolveEligibleAgentIdsTests {

        @Test
        @DisplayName("zwraca pusty zbiór gdy brak przypisań")
        void resolveEligibleAgentIds_noAssignments_returnsEmptySet() {
            TenantContext.setTenantId(TENANT_A);
            when(mockQuery.getResultList()).thenReturn(List.of());

            Set<UUID> result = repository.resolveEligibleAgentIds(QUEUE_ID, TENANT_A);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("UNION – zwraca agentów z obu źródeł bez duplikatów")
        void resolveEligibleAgentIds_agentFromDirectAndGroupSources_appearsOnce() {
            TenantContext.setTenantId(TENANT_A);

            // AGENT_1 – przez queue_agent; AGENT_2 – przez grupę
            // AGENT_1 jest też w grupie (oba źródła) – SQL UNION deduplikuje
            Query tenantQuery = mock(Query.class);
            Query unionQuery = mock(Query.class);

            when(entityManager.createNativeQuery(contains("set_tenant_context"))).thenReturn(tenantQuery);
            when(tenantQuery.setParameter(anyString(), any())).thenReturn(tenantQuery);
            when(tenantQuery.getSingleResult()).thenReturn(null);

            when(entityManager.createNativeQuery(contains("UNION"))).thenReturn(unionQuery);
            when(unionQuery.setParameter(anyString(), any())).thenReturn(unionQuery);
            // Symulujemy wynik UNION – SQL na poziomie DB deduplikuje, my testujemy mapowanie
            when(unionQuery.getResultList()).thenReturn(
                    List.of(AGENT_1.toString(), AGENT_2.toString())
            );

            Set<UUID> result = repository.resolveEligibleAgentIds(QUEUE_ID, TENANT_A);

            assertThat(result).containsExactlyInAnyOrder(AGENT_1, AGENT_2);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("agenci mapowani do Set – zduplikowane UUID z DB pojawia się raz")
        void resolveEligibleAgentIds_duplicatesFromDb_deduplicatedInSet() {
            TenantContext.setTenantId(TENANT_A);

            Query tenantQuery = mock(Query.class);
            Query unionQuery = mock(Query.class);

            when(entityManager.createNativeQuery(contains("set_tenant_context"))).thenReturn(tenantQuery);
            when(tenantQuery.setParameter(anyString(), any())).thenReturn(tenantQuery);
            when(tenantQuery.getSingleResult()).thenReturn(null);

            when(entityManager.createNativeQuery(contains("UNION"))).thenReturn(unionQuery);
            when(unionQuery.setParameter(anyString(), any())).thenReturn(unionQuery);
            // Gdyby z jakiegoś powodu DB zwróciło duplikat – Set go eliminuje
            when(unionQuery.getResultList()).thenReturn(
                    List.of(AGENT_1.toString(), AGENT_1.toString(), AGENT_2.toString())
            );

            Set<UUID> result = repository.resolveEligibleAgentIds(QUEUE_ID, TENANT_A);

            assertThat(result).containsExactlyInAnyOrder(AGENT_1, AGENT_2);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("cross-tenant isolation – ustawia tenant context przed zapytaniem UNION")
        void resolveEligibleAgentIds_setsTenantContextBeforeQuery() {
            TenantContext.setTenantId(TENANT_A);

            repository.resolveEligibleAgentIds(QUEUE_ID, TENANT_A);

            verify(entityManager, atLeastOnce()).createNativeQuery(contains("set_tenant_context"));
        }

        @Test
        @DisplayName("SQL zawiera JOIN agent_group_member po group_id")
        void resolveEligibleAgentIds_sqlContainsJoinOnGroupId() {
            TenantContext.setTenantId(TENANT_A);

            repository.resolveEligibleAgentIds(QUEUE_ID, TENANT_A);

            verify(entityManager, atLeastOnce())
                    .createNativeQuery(contains("agent_group_member"));
        }

        @Test
        @DisplayName("przekazuje queueId jako parametr do zapytania UNION")
        void resolveEligibleAgentIds_passesQueueIdParameter() {
            TenantContext.setTenantId(TENANT_A);

            repository.resolveEligibleAgentIds(QUEUE_ID, TENANT_A);

            verify(mockQuery, atLeastOnce()).setParameter("queueId", QUEUE_ID.toString());
        }
    }

    // =========================================================================
    // replaceDirectAgents – atomowa podmiana
    // =========================================================================

    @Nested
    @DisplayName("replaceDirectAgents() – atomowe zastąpienie listy agentów")
    class ReplaceDirectAgentsTests {

        @Test
        @DisplayName("wykonuje DELETE, a następnie INSERT dla każdego agenta")
        void replaceDirectAgents_deletesAndInsertsForEachAgent() {
            TenantContext.setTenantId(TENANT_A);

            Query tenantQuery = mock(Query.class);
            Query deleteQuery = mock(Query.class);
            Query insertQuery = mock(Query.class);

            when(entityManager.createNativeQuery(contains("set_tenant_context"))).thenReturn(tenantQuery);
            when(tenantQuery.setParameter(anyString(), any())).thenReturn(tenantQuery);
            when(tenantQuery.getSingleResult()).thenReturn(null);

            when(entityManager.createNativeQuery(contains("DELETE FROM queue_agent"))).thenReturn(deleteQuery);
            when(deleteQuery.setParameter(anyString(), any())).thenReturn(deleteQuery);
            when(deleteQuery.executeUpdate()).thenReturn(3);

            when(entityManager.createNativeQuery(contains("INSERT INTO queue_agent"))).thenReturn(insertQuery);
            when(insertQuery.setParameter(anyString(), any())).thenReturn(insertQuery);
            when(insertQuery.executeUpdate()).thenReturn(1);

            repository.replaceDirectAgents(QUEUE_ID, TENANT_A, List.of(AGENT_1, AGENT_2));

            verify(deleteQuery).executeUpdate();
            // INSERT wywołany raz per agent
            verify(insertQuery, times(2)).executeUpdate();
        }

        @Test
        @DisplayName("wykonuje tylko DELETE gdy lista agentów jest pusta")
        void replaceDirectAgents_withEmptyList_onlyExecutesDelete() {
            TenantContext.setTenantId(TENANT_A);

            Query tenantQuery = mock(Query.class);
            Query deleteQuery = mock(Query.class);

            when(entityManager.createNativeQuery(contains("set_tenant_context"))).thenReturn(tenantQuery);
            when(tenantQuery.setParameter(anyString(), any())).thenReturn(tenantQuery);
            when(tenantQuery.getSingleResult()).thenReturn(null);

            when(entityManager.createNativeQuery(contains("DELETE FROM queue_agent"))).thenReturn(deleteQuery);
            when(deleteQuery.setParameter(anyString(), any())).thenReturn(deleteQuery);
            when(deleteQuery.executeUpdate()).thenReturn(0);

            repository.replaceDirectAgents(QUEUE_ID, TENANT_A, List.of());

            verify(deleteQuery).executeUpdate();
            // Żaden INSERT nie powinien być wykonany
            verify(entityManager, never()).createNativeQuery(contains("INSERT INTO queue_agent"));
        }

        @Test
        @DisplayName("INSERT zawiera ON CONFLICT DO NOTHING")
        void replaceDirectAgents_insertUsesOnConflictDoNothing() {
            TenantContext.setTenantId(TENANT_A);

            repository.replaceDirectAgents(QUEUE_ID, TENANT_A, List.of(AGENT_1));

            verify(entityManager, atLeastOnce())
                    .createNativeQuery(contains("ON CONFLICT DO NOTHING"));
        }

        @Test
        @DisplayName("DELETE przekazuje queueId jako parametr")
        void replaceDirectAgents_passesQueueIdToDelete() {
            TenantContext.setTenantId(TENANT_A);

            repository.replaceDirectAgents(QUEUE_ID, TENANT_A, List.of());

            verify(mockQuery, atLeastOnce()).setParameter("queueId", QUEUE_ID.toString());
        }
    }

    // =========================================================================
    // replaceGroups – atomowa podmiana grup
    // =========================================================================

    @Nested
    @DisplayName("replaceGroups() – atomowe zastąpienie listy grup")
    class ReplaceGroupsTests {

        @Test
        @DisplayName("wykonuje DELETE, a następnie INSERT dla każdej grupy")
        void replaceGroups_deletesAndInsertsForEachGroup() {
            TenantContext.setTenantId(TENANT_A);

            Query tenantQuery = mock(Query.class);
            Query deleteQuery = mock(Query.class);
            Query insertQuery = mock(Query.class);

            when(entityManager.createNativeQuery(contains("set_tenant_context"))).thenReturn(tenantQuery);
            when(tenantQuery.setParameter(anyString(), any())).thenReturn(tenantQuery);
            when(tenantQuery.getSingleResult()).thenReturn(null);

            when(entityManager.createNativeQuery(contains("DELETE FROM queue_agent_group"))).thenReturn(deleteQuery);
            when(deleteQuery.setParameter(anyString(), any())).thenReturn(deleteQuery);
            when(deleteQuery.executeUpdate()).thenReturn(2);

            when(entityManager.createNativeQuery(contains("INSERT INTO queue_agent_group"))).thenReturn(insertQuery);
            when(insertQuery.setParameter(anyString(), any())).thenReturn(insertQuery);
            when(insertQuery.executeUpdate()).thenReturn(1);

            repository.replaceGroups(QUEUE_ID, TENANT_A, List.of(GROUP_1, GROUP_2));

            verify(deleteQuery).executeUpdate();
            // INSERT wywołany raz per grupę
            verify(insertQuery, times(2)).executeUpdate();
        }

        @Test
        @DisplayName("wykonuje tylko DELETE gdy lista grup jest pusta")
        void replaceGroups_withEmptyList_onlyExecutesDelete() {
            TenantContext.setTenantId(TENANT_A);

            Query tenantQuery = mock(Query.class);
            Query deleteQuery = mock(Query.class);

            when(entityManager.createNativeQuery(contains("set_tenant_context"))).thenReturn(tenantQuery);
            when(tenantQuery.setParameter(anyString(), any())).thenReturn(tenantQuery);
            when(tenantQuery.getSingleResult()).thenReturn(null);

            when(entityManager.createNativeQuery(contains("DELETE FROM queue_agent_group"))).thenReturn(deleteQuery);
            when(deleteQuery.setParameter(anyString(), any())).thenReturn(deleteQuery);
            when(deleteQuery.executeUpdate()).thenReturn(0);

            repository.replaceGroups(QUEUE_ID, TENANT_A, List.of());

            verify(deleteQuery).executeUpdate();
            verify(entityManager, never()).createNativeQuery(contains("INSERT INTO queue_agent_group"));
        }

        @Test
        @DisplayName("INSERT zawiera ON CONFLICT DO NOTHING")
        void replaceGroups_insertUsesOnConflictDoNothing() {
            TenantContext.setTenantId(TENANT_A);

            repository.replaceGroups(QUEUE_ID, TENANT_A, List.of(GROUP_1));

            verify(entityManager, atLeastOnce())
                    .createNativeQuery(contains("ON CONFLICT DO NOTHING"));
        }

        @Test
        @DisplayName("DELETE przekazuje queueId jako parametr")
        void replaceGroups_passesQueueIdToDelete() {
            TenantContext.setTenantId(TENANT_A);

            repository.replaceGroups(QUEUE_ID, TENANT_A, List.of());

            verify(mockQuery, atLeastOnce()).setParameter("queueId", QUEUE_ID.toString());
        }
    }

    // =========================================================================
    // Cross-tenant isolation
    // =========================================================================

    @Nested
    @DisplayName("Cross-tenant isolation – izolacja tenantów")
    class CrossTenantIsolationTests {

        @Test
        @DisplayName("resolveEligibleAgentIds zawsze wywołuje set_tenant_context z przekazanym tenantId")
        void resolveEligibleAgentIds_alwaysSetsTenantContext() {
            TenantContext.setTenantId(TENANT_A);

            repository.resolveEligibleAgentIds(QUEUE_ID, TENANT_A);

            // Weryfikujemy że set_tenant_context było zawołane z prawidłowym tenantId
            verify(mockQuery, atLeastOnce()).setParameter("tenantId", TENANT_A.toString());
        }

        @Test
        @DisplayName("findDirectAgentIds wywołuje set_tenant_context z przekazanym tenantId")
        void findDirectAgentIds_setsTenantContextWithProvidedTenantId() {
            TenantContext.setTenantId(TENANT_A);

            repository.findDirectAgentIds(QUEUE_ID, TENANT_A);

            verify(mockQuery, atLeastOnce()).setParameter("tenantId", TENANT_A.toString());
        }

        @Test
        @DisplayName("findGroupIds wywołuje set_tenant_context z przekazanym tenantId")
        void findGroupIds_setsTenantContextWithProvidedTenantId() {
            TenantContext.setTenantId(TENANT_A);

            repository.findGroupIds(QUEUE_ID, TENANT_A);

            verify(mockQuery, atLeastOnce()).setParameter("tenantId", TENANT_A.toString());
        }
    }
}
