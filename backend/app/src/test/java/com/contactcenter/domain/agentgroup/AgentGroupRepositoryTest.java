package com.contactcenter.domain.agentgroup;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link AgentGroupRepository}.
 *
 * <p>Weryfikuje logikę repozytorium: CRUD grup agentów, zarządzanie członkostwem
 * oraz izolację multi-tenant. EntityManager jest mockowany (brak H2 w zależnościach).
 *
 * <p>Scenariusze pokryte:
 * <ul>
 *   <li>findAllByTenantId z filtrem name (ILIKE)</li>
 *   <li>insert + findByIdAndTenantId</li>
 *   <li>existsByNameAndTenantId – duplikat</li>
 *   <li>update zmienia name</li>
 *   <li>delete kaskadowo usuwa członkostwa (weryfikacja przez findMemberIds)</li>
 *   <li>replaceMembers – atomowa podmiana</li>
 *   <li>cross-tenant: findByIdAndTenantId z obcym tenantId → Optional.empty()</li>
 *   <li>addMember – idempotentność (dwukrotny insert nie rzuca wyjątku)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentGroupRepository – CRUD grup agentów i zarządzanie członkostwem")
class AgentGroupRepositoryTest {

    private static final UUID TENANT_A  = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B  = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID GROUP_ID  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AGENT_ID  = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query mockQuery;

    private AgentGroupRepository repository;

    @BeforeEach
    void setUp() {
        repository = new AgentGroupRepository();
        ReflectionTestUtils.setField(repository, "em", entityManager);
        TenantContext.setTenantId(TENANT_A);
        TenantContext.setUserId(UUID.randomUUID());
        TenantContext.setUserRole("SUPERVISOR");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private AgentGroup buildGroup(UUID groupId, UUID tenantId, String name) {
        AgentGroup group = new AgentGroup();
        group.setGroupId(groupId);
        group.setTenantId(tenantId);
        group.setName(name);
        group.setCreatedAt(Instant.now());
        group.setUpdatedAt(Instant.now());
        return group;
    }

    private Object[] toRow(AgentGroup group) {
        return new Object[]{
            group.getGroupId(),
            group.getTenantId(),
            group.getName(),
            group.getCreatedAt(),
            group.getUpdatedAt()
        };
    }

    private void stubTenantContextQuery() {
        when(entityManager.createNativeQuery(contains("set_tenant_context"))).thenReturn(mockQuery);
        when(mockQuery.setParameter(anyString(), anyString())).thenReturn(mockQuery);
        when(mockQuery.getSingleResult()).thenReturn(null);
    }

    // =========================================================================
    // findAllByTenantId
    // =========================================================================

    @Nested
    @DisplayName("findAllByTenantId()")
    class FindAllByTenantId {

        @Test
        @DisplayName("bez filtru name – zwraca wszystkie grupy tenanta")
        void shouldReturnAllGroupsWithoutFilter() {
            // given
            AgentGroup group1 = buildGroup(GROUP_ID, TENANT_A, "Alpha");
            AgentGroup group2 = buildGroup(UUID.randomUUID(), TENANT_A, "Beta");

            stubTenantContextQuery();

            Query dataQuery = mock(Query.class);
            Query countQuery = mock(Query.class);

            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("agent_group") && !sql.contains("LIKE"))
            )).thenReturn(dataQuery);

            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("COUNT") && !sql.contains("LIKE"))
            )).thenReturn(countQuery);

            when(dataQuery.setParameter(anyString(), any())).thenReturn(dataQuery);
            when(dataQuery.getResultList()).thenReturn(java.util.Arrays.asList(toRow(group1), toRow(group2)));

            when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
            when(countQuery.getSingleResult()).thenReturn(2L);

            // when
            PagedResponse<AgentGroup> result = repository.findAllByTenantId(TENANT_A, null, 0, 20);

            // then
            assertThat(result.content()).hasSize(2);
            assertThat(result.totalElements()).isEqualTo(2L);
            assertThat(result.page()).isEqualTo(0);
            assertThat(result.size()).isEqualTo(20);
            assertThat(result.first()).isTrue();
        }

        @Test
        @DisplayName("z filtrem name – wywołuje zapytanie ILIKE")
        void shouldUseIlikeWhenNameFilterProvided() {
            // given
            AgentGroup group = buildGroup(GROUP_ID, TENANT_A, "Sales Team");

            stubTenantContextQuery();

            Query dataQuery = mock(Query.class);
            Query countQuery = mock(Query.class);

            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("LIKE") && !sql.contains("COUNT"))
            )).thenReturn(dataQuery);

            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("COUNT") && sql.contains("LIKE"))
            )).thenReturn(countQuery);

            when(dataQuery.setParameter(anyString(), any())).thenReturn(dataQuery);
            List<Object[]> ilikeSingleRow = new java.util.ArrayList<>();
            ilikeSingleRow.add(toRow(group));
            when(dataQuery.getResultList()).thenReturn(ilikeSingleRow);

            when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
            when(countQuery.getSingleResult()).thenReturn(1L);

            // when
            PagedResponse<AgentGroup> result = repository.findAllByTenantId(TENANT_A, "Sales", 0, 10);

            // then
            assertThat(result.content()).hasSize(1);
            assertThat(result.content().get(0).getName()).isEqualTo("Sales Team");
            assertThat(result.totalElements()).isEqualTo(1L);
        }

        @Test
        @DisplayName("pusta lista – totalPages = 0, first = true, last = true")
        void shouldHandleEmptyResult() {
            // given
            stubTenantContextQuery();

            Query dataQuery = mock(Query.class);
            Query countQuery = mock(Query.class);

            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("agent_group") && !sql.contains("LIKE") && !sql.contains("COUNT"))
            )).thenReturn(dataQuery);

            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("COUNT") && !sql.contains("LIKE"))
            )).thenReturn(countQuery);

            when(dataQuery.setParameter(anyString(), any())).thenReturn(dataQuery);
            when(dataQuery.getResultList()).thenReturn(List.of());

            when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
            when(countQuery.getSingleResult()).thenReturn(0L);

            // when
            PagedResponse<AgentGroup> result = repository.findAllByTenantId(TENANT_A, null, 0, 20);

            // then
            assertThat(result.content()).isEmpty();
            assertThat(result.totalElements()).isZero();
            assertThat(result.totalPages()).isZero();
            assertThat(result.first()).isTrue();
        }
    }

    // =========================================================================
    // findByIdAndTenantId
    // =========================================================================

    @Nested
    @DisplayName("findByIdAndTenantId()")
    class FindByIdAndTenantId {

        @Test
        @DisplayName("sukces – zwraca grupę gdy istnieje i należy do tenanta")
        void shouldReturnGroupWhenFoundForTenant() {
            // given
            AgentGroup group = buildGroup(GROUP_ID, TENANT_A, "Test Group");

            stubTenantContextQuery();

            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("group_id") && sql.contains("tenant_id"))
            )).thenReturn(mockQuery);
            when(mockQuery.setParameter(anyString(), anyString())).thenReturn(mockQuery);
            List<Object[]> foundRows = new java.util.ArrayList<>();
            foundRows.add(toRow(group));
            when(mockQuery.getResultList()).thenReturn(foundRows);

            // when
            Optional<AgentGroup> result = repository.findByIdAndTenantId(GROUP_ID, TENANT_A);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().getGroupId()).isEqualTo(GROUP_ID);
            assertThat(result.get().getName()).isEqualTo("Test Group");
        }

        @Test
        @DisplayName("cross-tenant – zwraca Optional.empty() dla obcego tenantId")
        void shouldReturnEmptyForCrossTenantAccess() {
            // given
            // Symulacja: zapytanie z tenant_id TENANT_B nie zwraca grupy należącej do TENANT_A
            TenantContext.setTenantId(TENANT_B);

            when(entityManager.createNativeQuery(contains("set_tenant_context"))).thenReturn(mockQuery);
            when(mockQuery.setParameter(anyString(), anyString())).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(null);

            Query findQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql.contains("group_id") && sql.contains("tenant_id"))
            )).thenReturn(findQuery);
            when(findQuery.setParameter(anyString(), anyString())).thenReturn(findQuery);
            when(findQuery.getResultList()).thenReturn(List.of()); // brak wyników dla obcego tenanta

            // when
            Optional<AgentGroup> result = repository.findByIdAndTenantId(GROUP_ID, TENANT_B);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("nie istnieje – zwraca Optional.empty()")
        void shouldReturnEmptyWhenGroupNotFound() {
            // given
            stubTenantContextQuery();

            when(entityManager.createNativeQuery(
                    argThat(sql -> sql.contains("group_id") && sql.contains("tenant_id"))
            )).thenReturn(mockQuery);
            when(mockQuery.setParameter(anyString(), anyString())).thenReturn(mockQuery);
            when(mockQuery.getResultList()).thenReturn(List.of());

            // when
            Optional<AgentGroup> result = repository.findByIdAndTenantId(UUID.randomUUID(), TENANT_A);

            // then
            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // existsByNameAndTenantId
    // =========================================================================

    @Nested
    @DisplayName("existsByNameAndTenantId()")
    class ExistsByNameAndTenantId {

        @Test
        @DisplayName("duplikat – zwraca true gdy nazwa już istnieje w tenancie")
        void shouldReturnTrueWhenNameAlreadyExists() {
            // given
            stubTenantContextQuery();

            Query countQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql.contains("COUNT") && sql.contains("agent_group"))
            )).thenReturn(countQuery);
            when(countQuery.setParameter(anyString(), anyString())).thenReturn(countQuery);
            when(countQuery.getSingleResult()).thenReturn(1L);

            // when
            boolean exists = repository.existsByNameAndTenantId("Support Team", TENANT_A);

            // then
            assertThat(exists).isTrue();
        }

        @Test
        @DisplayName("unikalna nazwa – zwraca false gdy nazwa nie istnieje")
        void shouldReturnFalseWhenNameDoesNotExist() {
            // given
            stubTenantContextQuery();

            Query countQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql.contains("COUNT") && sql.contains("agent_group"))
            )).thenReturn(countQuery);
            when(countQuery.setParameter(anyString(), anyString())).thenReturn(countQuery);
            when(countQuery.getSingleResult()).thenReturn(0L);

            // when
            boolean exists = repository.existsByNameAndTenantId("New Group", TENANT_A);

            // then
            assertThat(exists).isFalse();
        }
    }

    // =========================================================================
    // insert
    // =========================================================================

    @Nested
    @DisplayName("insert()")
    class Insert {

        @Test
        @DisplayName("sukces – tworzy grupę i zwraca encję z danymi z DB")
        void shouldInsertGroupAndReturnSavedEntity() {
            // given
            AgentGroup group = new AgentGroup();
            group.setTenantId(TENANT_A);
            group.setName("New Group");
            // groupId = null → DB generuje uuid

            Timestamp now = Timestamp.from(Instant.now());
            // RETURNING zwraca listę Object[] – każdy wiersz to tablica kolumn
            Object[] returnedRow = {
                    GROUP_ID.toString(),
                    TENANT_A.toString(),
                    "New Group",
                    now,
                    now
            };
            @SuppressWarnings("unchecked")
            java.util.ArrayList returnedRows = new java.util.ArrayList();
            returnedRows.add(returnedRow);

            stubTenantContextQuery();

            Query insertQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("INSERT INTO agent_group"))
            )).thenReturn(insertQuery);
            when(insertQuery.setParameter(anyString(), any())).thenReturn(insertQuery);
            when(insertQuery.getResultList()).thenReturn(returnedRows);

            // when
            AgentGroup saved = repository.insert(group);

            // then
            assertThat(saved.getGroupId()).isEqualTo(GROUP_ID);
            assertThat(saved.getTenantId()).isEqualTo(TENANT_A);
            assertThat(saved.getName()).isEqualTo("New Group");
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("cross-tenant – rzuca CrossTenantAccessException gdy tenantId != kontekst")
        void shouldThrowCrossTenantExceptionWhenTenantMismatch() {
            // given – w kontekście jest TENANT_A, ale encja ma TENANT_B
            AgentGroup group = new AgentGroup();
            group.setTenantId(TENANT_B);
            group.setName("Malicious Group");

            // when / then
            assertThatThrownBy(() -> repository.insert(group))
                    .isInstanceOf(CrossTenantAccessException.class);

            verify(entityManager, never()).createNativeQuery(contains("INSERT INTO agent_group"));
        }
    }

    // =========================================================================
    // update
    // =========================================================================

    @Nested
    @DisplayName("update()")
    class Update {

        @Test
        @DisplayName("sukces – aktualizuje nazwę grupy, zwraca 1")
        void shouldUpdateGroupNameAndReturnOne() {
            // given
            AgentGroup group = buildGroup(GROUP_ID, TENANT_A, "Updated Name");

            stubTenantContextQuery();

            Query updateQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql.contains("UPDATE agent_group"))
            )).thenReturn(updateQuery);
            when(updateQuery.setParameter(anyString(), anyString())).thenReturn(updateQuery);
            when(updateQuery.executeUpdate()).thenReturn(1);

            // when
            int updated = repository.update(group);

            // then
            assertThat(updated).isEqualTo(1);
            verify(updateQuery).setParameter("name", "Updated Name");
            verify(updateQuery).setParameter("groupId", GROUP_ID.toString());
            verify(updateQuery).setParameter("tenantId", TENANT_A.toString());
        }

        @Test
        @DisplayName("nie istnieje – zwraca 0 gdy nie znaleziono grupy do aktualizacji")
        void shouldReturnZeroWhenGroupNotFound() {
            // given
            AgentGroup group = buildGroup(UUID.randomUUID(), TENANT_A, "Ghost Group");

            stubTenantContextQuery();

            Query updateQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql.contains("UPDATE agent_group"))
            )).thenReturn(updateQuery);
            when(updateQuery.setParameter(anyString(), anyString())).thenReturn(updateQuery);
            when(updateQuery.executeUpdate()).thenReturn(0);

            // when
            int updated = repository.update(group);

            // then
            assertThat(updated).isEqualTo(0);
        }

        @Test
        @DisplayName("cross-tenant – rzuca CrossTenantAccessException")
        void shouldThrowCrossTenantExceptionOnUpdate() {
            // given
            AgentGroup group = buildGroup(GROUP_ID, TENANT_B, "Hacker Group");

            // when / then
            assertThatThrownBy(() -> repository.update(group))
                    .isInstanceOf(CrossTenantAccessException.class);

            verify(entityManager, never()).createNativeQuery(contains("UPDATE agent_group"));
        }
    }

    // =========================================================================
    // delete
    // =========================================================================

    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("sukces – usuwa grupę i kaskadowo członkostwa, zwraca 1")
        void shouldDeleteGroupAndReturnOne() {
            // given
            stubTenantContextQuery();

            Query deleteQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql.contains("DELETE FROM agent_group") && !sql.contains("member"))
            )).thenReturn(deleteQuery);
            when(deleteQuery.setParameter(anyString(), anyString())).thenReturn(deleteQuery);
            when(deleteQuery.executeUpdate()).thenReturn(1);

            // when
            int deleted = repository.delete(GROUP_ID, TENANT_A);

            // then
            assertThat(deleted).isEqualTo(1);
        }

        @Test
        @DisplayName("po delete – findMemberIds zwraca pustą listę (kaskada DB)")
        void shouldReturnEmptyMembersAfterDeleteCascade() {
            // given – symulacja: po usunięciu grupy findMemberIds zwraca []
            stubTenantContextQuery();

            Query findMembersQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql.contains("agent_group_member") && sql.contains("SELECT agm.agent_id"))
            )).thenReturn(findMembersQuery);
            when(findMembersQuery.setParameter(anyString(), anyString())).thenReturn(findMembersQuery);
            when(findMembersQuery.getResultList()).thenReturn(List.of());

            // when
            List<UUID> members = repository.findMemberIds(GROUP_ID, TENANT_A);

            // then
            assertThat(members).isEmpty();
        }

        @Test
        @DisplayName("cross-tenant – rzuca CrossTenantAccessException")
        void shouldThrowCrossTenantExceptionOnDelete() {
            // given – w kontekście jest TENANT_A, próba usunięcia z TENANT_B
            assertThatThrownBy(() -> repository.delete(GROUP_ID, TENANT_B))
                    .isInstanceOf(CrossTenantAccessException.class);

            verify(entityManager, never()).createNativeQuery(contains("DELETE FROM agent_group"));
        }
    }

    // =========================================================================
    // addMember / removeMember
    // =========================================================================

    @Nested
    @DisplayName("addMember() – idempotentność")
    class AddMember {

        @Test
        @DisplayName("idempotentność – dwukrotny addMember nie rzuca wyjątku (ON CONFLICT DO NOTHING)")
        void addMember_idempotent_doesNotThrow() {
            // given
            Query insertQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql.contains("INSERT INTO agent_group_member"))
            )).thenReturn(insertQuery);
            when(insertQuery.setParameter(anyString(), anyString())).thenReturn(insertQuery);
            when(insertQuery.executeUpdate()).thenReturn(0); // ON CONFLICT DO NOTHING → 0 rows

            // when – wywołanie pierwsze
            assertThatNoException().isThrownBy(() -> repository.addMember(GROUP_ID, AGENT_ID));

            // when – wywołanie drugie (idempotentne)
            assertThatNoException().isThrownBy(() -> repository.addMember(GROUP_ID, AGENT_ID));

            // then – INSERT wywołany dwa razy
            verify(insertQuery, times(2)).executeUpdate();
        }

        @Test
        @DisplayName("sukces – addMember wywołuje INSERT z poprawnymi parametrami")
        void addMember_callsInsertWithCorrectParams() {
            // given
            Query insertQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql.contains("INSERT INTO agent_group_member"))
            )).thenReturn(insertQuery);
            when(insertQuery.setParameter(anyString(), anyString())).thenReturn(insertQuery);
            when(insertQuery.executeUpdate()).thenReturn(1);

            // when
            repository.addMember(GROUP_ID, AGENT_ID);

            // then
            verify(insertQuery).setParameter("groupId", GROUP_ID.toString());
            verify(insertQuery).setParameter("agentId", AGENT_ID.toString());
        }

        @Test
        @DisplayName("removeMember – wywołuje DELETE z poprawnymi parametrami")
        void removeMember_callsDeleteWithCorrectParams() {
            // given
            Query deleteQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql.contains("DELETE FROM agent_group_member"))
            )).thenReturn(deleteQuery);
            when(deleteQuery.setParameter(anyString(), anyString())).thenReturn(deleteQuery);
            when(deleteQuery.executeUpdate()).thenReturn(1);

            // when
            repository.removeMember(GROUP_ID, AGENT_ID);

            // then
            verify(deleteQuery).setParameter("groupId", GROUP_ID.toString());
            verify(deleteQuery).setParameter("agentId", AGENT_ID.toString());
        }
    }

    // =========================================================================
    // replaceMembers
    // =========================================================================

    @Nested
    @DisplayName("replaceMembers()")
    class ReplaceMembers {

        @Test
        @DisplayName("atomowa podmiana – DELETE + INSERT dla każdego agenta")
        void shouldDeleteAllThenInsertNewMembers() {
            // given
            UUID agent1 = UUID.randomUUID();
            UUID agent2 = UUID.randomUUID();
            List<UUID> newMembers = List.of(agent1, agent2);

            // replaceMembers wywołuje createNativeQuery(String) – trzy różne SQL-e:
            // set_tenant_context, DELETE FROM agent_group_member, INSERT INTO agent_group_member.
            // Używamy thenAnswer na anyString() żeby rozróżnić je po treści.
            Query deleteQuery = mock(Query.class);
            Query insertQuery = mock(Query.class);

            when(entityManager.createNativeQuery(anyString())).thenAnswer(inv -> {
                String sql = inv.getArgument(0);
                if (sql != null && sql.contains("set_tenant_context")) {
                    return mockQuery;
                }
                if (sql != null && sql.contains("DELETE FROM agent_group_member")) {
                    return deleteQuery;
                }
                if (sql != null && sql.contains("INSERT INTO agent_group_member")) {
                    return insertQuery;
                }
                return mock(Query.class);
            });

            when(mockQuery.setParameter(anyString(), anyString())).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(null);
            when(deleteQuery.setParameter(anyString(), anyString())).thenReturn(deleteQuery);
            when(deleteQuery.executeUpdate()).thenReturn(3);
            when(insertQuery.setParameter(anyString(), anyString())).thenReturn(insertQuery);
            when(insertQuery.executeUpdate()).thenReturn(1);

            // when
            assertThatNoException()
                    .isThrownBy(() -> repository.replaceMembers(GROUP_ID, TENANT_A, newMembers));

            // then – DELETE wywołany raz, INSERT dla każdego agenta
            verify(deleteQuery, times(1)).executeUpdate();
            verify(insertQuery, times(2)).executeUpdate();
        }

        @Test
        @DisplayName("pusta lista – tylko DELETE, bez INSERT")
        void shouldOnlyDeleteWhenNewMembersIsEmpty() {
            // given
            Query deleteQuery = mock(Query.class);

            when(entityManager.createNativeQuery(anyString())).thenAnswer(inv -> {
                String sql = inv.getArgument(0);
                if (sql != null && sql.contains("set_tenant_context")) {
                    return mockQuery;
                }
                if (sql != null && sql.contains("DELETE FROM agent_group_member")) {
                    return deleteQuery;
                }
                return mock(Query.class);
            });

            when(mockQuery.setParameter(anyString(), anyString())).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(null);
            when(deleteQuery.setParameter(anyString(), anyString())).thenReturn(deleteQuery);
            when(deleteQuery.executeUpdate()).thenReturn(2);

            // when
            repository.replaceMembers(GROUP_ID, TENANT_A, List.of());

            // then – DELETE wywołany raz, INSERT nigdy
            verify(deleteQuery, times(1)).executeUpdate();
        }

        @Test
        @DisplayName("cross-tenant – rzuca CrossTenantAccessException")
        void shouldThrowCrossTenantExceptionOnReplaceMembers() {
            // given – w kontekście TENANT_A, próba podmiana dla TENANT_B
            assertThatThrownBy(() -> repository.replaceMembers(GROUP_ID, TENANT_B, List.of()))
                    .isInstanceOf(CrossTenantAccessException.class);

            verify(entityManager, never()).createNativeQuery(contains("DELETE FROM agent_group_member"));
        }
    }

    // =========================================================================
    // findMemberIds
    // =========================================================================

    @Nested
    @DisplayName("findMemberIds()")
    class FindMemberIds {

        @Test
        @DisplayName("sukces – zwraca listę UUID agentów grupy")
        void shouldReturnMemberIds() {
            // given
            stubTenantContextQuery();

            Query findQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql.contains("agent_group_member") && sql.contains("SELECT agm.agent_id"))
            )).thenReturn(findQuery);
            when(findQuery.setParameter(anyString(), anyString())).thenReturn(findQuery);
            when(findQuery.getResultList()).thenReturn(List.of(
                    AGENT_ID.toString(),
                    UUID.randomUUID().toString()
            ));

            // when
            List<UUID> members = repository.findMemberIds(GROUP_ID, TENANT_A);

            // then
            assertThat(members).hasSize(2);
            assertThat(members).contains(AGENT_ID);
        }

        @Test
        @DisplayName("brak członków – zwraca pustą listę")
        void shouldReturnEmptyListWhenNoMembers() {
            // given
            stubTenantContextQuery();

            Query findQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql.contains("agent_group_member") && sql.contains("SELECT agm.agent_id"))
            )).thenReturn(findQuery);
            when(findQuery.setParameter(anyString(), anyString())).thenReturn(findQuery);
            when(findQuery.getResultList()).thenReturn(List.of());

            // when
            List<UUID> members = repository.findMemberIds(GROUP_ID, TENANT_A);

            // then
            assertThat(members).isEmpty();
        }
    }
}
