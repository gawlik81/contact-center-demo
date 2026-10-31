package com.contactcenter.domain.contact;

import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test integracyjny {@link ContactRepository#findContactIdsOlderThan} i {@link ContactRepository#deleteContacts}
 * (BE-126, EPIC-30) na PRAWDZIWYM PostgreSQL (Testcontainers, pełny łańcuch Flyway) — nowy natywny SQL
 * wprowadzony przez ten ticket (WP-1: mockowany {@code EntityManager} nie łapałby semantyki partycji ani
 * kolejności keyset, patrz {@code ContactRepository#deleteBatchOlderThan} dla precedensu problemu z
 * {@code ctid} wykrytego wyłącznie na żywej bazie).
 *
 * <p>Wszystkie kontakty w tym teście mieszczą się celowo w JEDNEJ partycji ({@code contact_2026_03},
 * zakres {@code [2026-03-01, 2026-04-01)} z V007) — kryterium akceptacji BE-126/WP-1 "tenanci A i B w
 * TEJ SAMEJ partycji contact".
 */
@DisplayName("ContactRepository.findContactIdsOlderThan / deleteContacts – prawdziwa baza (BE-126)")
class ContactRepositoryPurgeCandidatesIntegrationTest {

    private static final Instant CUTOFF = Instant.parse("2026-03-15T00:00:00Z");

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static ContactRepository repository;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeAll
    static void startContext() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
        ctx = JpaTestContext.create(
                pool,
                new Class<?>[]{Contact.class},
                new Class<?>[]{ContactRepository.class},
                c -> {
                    c.getBeanFactory().registerSingleton("jdbcTemplate", jdbc);
                    c.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
                });
        repository = ctx.getBean(ContactRepository.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        pool.close();
    }

    @BeforeEach
    void setUp() {
        tenantA = PostgresTestDatabase.insertTenant(jdbc, "Tenant A – BE-126 " + UUID.randomUUID());
        tenantB = PostgresTestDatabase.insertTenant(jdbc, "Tenant B – BE-126 " + UUID.randomUUID());
        TenantContext.setTenantId(tenantA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Seedowanie i asercje
    // =========================================================================

    private UUID insertContact(UUID tenant, Instant startedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO contact (contact_id, tenant_id, channel, direction, status, started_at, queued_at)
                        VALUES (?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?, ?)
                        """,
                id, tenant, Timestamp.from(startedAt), Timestamp.from(startedAt));
        return id;
    }

    private boolean exists(UUID contactId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM contact WHERE contact_id = ?", Long.class, contactId);
        return count != null && count > 0;
    }

    private long count(UUID tenant) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM contact WHERE tenant_id = ?", Long.class, tenant);
        return count == null ? 0 : count;
    }

    // =========================================================================
    // findContactIdsOlderThan – porządek, granica, kursor
    // =========================================================================

    @Nested
    @DisplayName("findContactIdsOlderThan")
    class FindContactIdsOlderThan {

        @Test
        @DisplayName("granica: started_at DOKŁADNIE na cutoff jest WYKLUCZONY (semantyka <), starszy o 1s dołączony")
        void cutoffBoundary_isExclusive() {
            UUID atCutoff = insertContact(tenantA, CUTOFF);
            UUID beforeCutoff = insertContact(tenantA, CUTOFF.minusSeconds(1));

            List<ContactPurgeCandidate> page = repository.findContactIdsOlderThan(tenantA, CUTOFF, null, 100);

            assertThat(page).extracting(ContactPurgeCandidate::contactId).containsExactly(beforeCutoff);
            assertThat(page).noneMatch(c -> c.contactId().equals(atCutoff));
        }

        @Test
        @DisplayName("porządek deterministyczny (started_at, contact_id) — także dla identycznego started_at (tie-break po contact_id)")
        void deterministicOrder_tieBreaksByContactId() {
            Instant same = CUTOFF.minusSeconds(10);
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                ids.add(insertContact(tenantA, same));
            }

            List<ContactPurgeCandidate> page = repository.findContactIdsOlderThan(tenantA, CUTOFF, null, 100);

            // UUID.compareTo() (Javy) porównuje most/leastSigBits jako liczby ZE ZNAKIEM i NIE jest
            // spójne z porządkiem bajtowym `uuid` w Postgresie (ORDER BY contact_id) — porównanie po
            // reprezentacji tekstowej odpowiada porządkowi bajtowemu używanemu przez bazę.
            List<UUID> expectedOrder = ids.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
            assertThat(page).extracting(ContactPurgeCandidate::contactId).containsExactlyElementsOf(expectedOrder);
        }

        @Test
        @DisplayName("stronicowanie keyset: druga strona z kursorem = ostatni kandydat pierwszej strony nie powtarza ani nie gubi wierszy")
        void keysetPagination_noOverlapNoGaps() {
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                ids.add(insertContact(tenantA, CUTOFF.minus(i + 1, ChronoUnit.MINUTES)));
            }

            List<ContactPurgeCandidate> page1 = repository.findContactIdsOlderThan(tenantA, CUTOFF, null, 10);
            assertThat(page1).hasSize(10);

            ContactPurgeCandidate cursor1 = page1.get(page1.size() - 1);
            List<ContactPurgeCandidate> page2 = repository.findContactIdsOlderThan(tenantA, CUTOFF, cursor1, 10);
            assertThat(page2).hasSize(10);

            ContactPurgeCandidate cursor2 = page2.get(page2.size() - 1);
            List<ContactPurgeCandidate> page3 = repository.findContactIdsOlderThan(tenantA, CUTOFF, cursor2, 10);
            assertThat(page3).hasSize(5);

            // strona 4. jest pusta (wyczerpanie kandydatów, page.size() < batchSize sygnalizuje ostatnią stronę)
            ContactPurgeCandidate cursor3 = page3.get(page3.size() - 1);
            List<ContactPurgeCandidate> page4 = repository.findContactIdsOlderThan(tenantA, CUTOFF, cursor3, 10);
            assertThat(page4).isEmpty();

            Set<UUID> allReturned = new HashSet<>();
            allReturned.addAll(page1.stream().map(ContactPurgeCandidate::contactId).toList());
            allReturned.addAll(page2.stream().map(ContactPurgeCandidate::contactId).toList());
            allReturned.addAll(page3.stream().map(ContactPurgeCandidate::contactId).toList());
            assertThat(allReturned).containsExactlyInAnyOrderElementsOf(ids);
            assertThat(allReturned).hasSize(25); // brak duplikatów między stronami
        }

        @Test
        @DisplayName("kursor przesuwa się POZA kandydata, nawet jeśli ten nie zostanie usunięty (obrona H-1) — nie jest zwracany ponownie")
        void cursorAdvancesPastCandidate_regardlessOfDeletion() {
            UUID blocked = insertContact(tenantA, CUTOFF.minusSeconds(20)); // symulacja: zostaje "zablokowany" przez wołającego, NIE usuwany
            UUID younger = insertContact(tenantA, CUTOFF.minusSeconds(10));

            List<ContactPurgeCandidate> page1 = repository.findContactIdsOlderThan(tenantA, CUTOFF, null, 1);
            assertThat(page1).extracting(ContactPurgeCandidate::contactId).containsExactly(blocked);
            // wywołujący NIE usuwa "blocked" (symulacja porażki S3) — ale kursor i tak przesuwa się o całą stronę

            List<ContactPurgeCandidate> page2 = repository.findContactIdsOlderThan(tenantA, CUTOFF, page1.get(0), 1);
            assertThat(page2).extracting(ContactPurgeCandidate::contactId).containsExactly(younger);
            assertThat(exists(blocked)).isTrue(); // nadal w bazie — nigdy nie usunięty w tym teście
        }

        @Test
        @DisplayName("izolacja tenantów: kandydaci tenanta B (nawet w tej samej partycji, ten sam zakres dat) nie trafiają do wyniku A")
        void tenantIsolation_sharedPartition() {
            UUID ofA = insertContact(tenantA, CUTOFF.minusSeconds(5));
            UUID ofB = insertContact(tenantB, CUTOFF.minusSeconds(5));

            List<ContactPurgeCandidate> page = repository.findContactIdsOlderThan(tenantA, CUTOFF, null, 100);

            assertThat(page).extracting(ContactPurgeCandidate::contactId).containsExactly(ofA);
            assertThat(page).noneMatch(c -> c.contactId().equals(ofB));
        }

        @Test
        @DisplayName("brak kandydatów -> pusta strona, nie null")
        void noCandidates_returnsEmptyPage() {
            List<ContactPurgeCandidate> page = repository.findContactIdsOlderThan(tenantA, CUTOFF, null, 100);
            assertThat(page).isEmpty();
        }
    }

    // =========================================================================
    // deleteContacts – RETURNING, izolacja tenantów
    // =========================================================================

    @Nested
    @DisplayName("deleteContacts")
    class DeleteContacts {

        @Test
        @DisplayName("usuwa dokładnie podane ID, zwraca faktycznie usunięte (RETURNING)")
        void deletesExactIds_returnsConfirmed() {
            UUID a = insertContact(tenantA, CUTOFF.minusSeconds(1));
            UUID b = insertContact(tenantA, CUTOFF.minusSeconds(2));
            UUID untouched = insertContact(tenantA, CUTOFF.plusSeconds(999)); // nie w liście do usunięcia

            Set<UUID> deleted = repository.deleteContacts(tenantA, List.of(a, b));

            assertThat(deleted).containsExactlyInAnyOrder(a, b);
            assertThat(exists(a)).isFalse();
            assertThat(exists(b)).isFalse();
            assertThat(exists(untouched)).isTrue();
        }

        @Test
        @DisplayName("izolacja tenantów: ID kontaktu tenanta B podane w wywołaniu dla A -> NIE usunięty, nie zwrócony jako usunięty")
        void tenantIsolation_foreignIdIsIgnored() {
            UUID ofB = insertContact(tenantB, CUTOFF.minusSeconds(1));
            UUID ofA = insertContact(tenantA, CUTOFF.minusSeconds(1));

            Set<UUID> deleted = repository.deleteContacts(tenantA, List.of(ofA, ofB));

            assertThat(deleted).containsExactly(ofA);
            assertThat(exists(ofB)).isTrue();
            assertThat(count(tenantB)).isEqualTo(1);
        }

        @Test
        @DisplayName("ID nieistniejącego kontaktu -> pomijany bez błędu, nie ma go w wyniku")
        void nonExistentId_isSilentlyIgnored() {
            UUID real = insertContact(tenantA, CUTOFF.minusSeconds(1));
            UUID fake = UUID.randomUUID();

            Set<UUID> deleted = repository.deleteContacts(tenantA, List.of(real, fake));

            assertThat(deleted).containsExactly(real);
        }

        @Test
        @DisplayName("pusta/null lista -> pusty wynik, brak zapytania do bazy (no-op)")
        void emptyOrNullList_isNoOp() {
            assertThat(repository.deleteContacts(tenantA, List.of())).isEmpty();
            assertThat(repository.deleteContacts(tenantA, null)).isEmpty();
        }
    }

    // =========================================================================
    // Plan zapytań (EXPLAIN) i brak ctid
    // =========================================================================

    @Nested
    @DisplayName("plan zapytań i brak ctid")
    class QueryPlans {

        @Test
        @DisplayName("SQL findContactIdsOlderThan/deleteContacts NIE używa ctid (niebezpieczne na tabeli partycjonowanej)")
        void sql_doesNotUseCtid() {
            assertThat(ContactRepository.FIND_CONTACT_IDS_OLDER_THAN_FIRST_PAGE_SQL).doesNotContainPattern("(?i)\\bctid\\b");
            assertThat(ContactRepository.FIND_CONTACT_IDS_OLDER_THAN_NEXT_PAGE_SQL).doesNotContainPattern("(?i)\\bctid\\b");
            assertThat(ContactRepository.DELETE_CONTACTS_SQL).doesNotContainPattern("(?i)\\bctid\\b")
                    .contains("contact_id IN").contains("tenant_id");
        }

        @Test
        @DisplayName("deleteContacts identyfikuje wiersze pełnym kluczem logicznym contact_id (nie fizycznym ctid) i potwierdza przez RETURNING")
        void deleteContacts_usesReturning() {
            assertThat(ContactRepository.DELETE_CONTACTS_SQL).containsIgnoringCase("RETURNING");
        }
    }
}
