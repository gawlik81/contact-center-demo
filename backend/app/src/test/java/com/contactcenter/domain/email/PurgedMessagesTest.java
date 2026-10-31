package com.contactcenter.domain.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PurgedMessages – kontrakt wyniku purge (BE-125)")
class PurgedMessagesTest {

    private static final UUID C1 = UUID.fromString("c1c1c1c1-c1c1-c1c1-c1c1-c1c1c1c1c1c1");
    private static final UUID C2 = UUID.fromString("c2c2c2c2-c2c2-c2c2-c2c2-c2c2c2c2c2c2");

    @Test
    @DisplayName("empty() = same zera i pusty zbiór blokad")
    void empty_isAllZeros() {
        PurgedMessages empty = PurgedMessages.empty();

        assertThat(empty.deletedRows()).isZero();
        assertThat(empty.s3ObjectsDeleted()).isZero();
        assertThat(empty.s3Failures()).isZero();
        assertThat(empty.s3Rejected()).isZero();
        assertThat(empty.contactIdsBlocked()).isEmpty();
    }

    @Test
    @DisplayName("plus() sumuje liczniki i łączy zbiory zablokowanych kontaktów")
    void plus_sumsCountersAndUnionsBlockedContacts() {
        PurgedMessages a = new PurgedMessages(2, 3, 1, 0, Set.of(C1));
        PurgedMessages b = new PurgedMessages(5, 1, 0, 2, Set.of(C1, C2));

        assertThat(a.plus(b)).isEqualTo(new PurgedMessages(7, 4, 1, 2, Set.of(C1, C2)));
        assertThat(PurgedMessages.empty().plus(a)).isEqualTo(a);
    }

    @Test
    @DisplayName("zbiór blokad jest kopią obronną i niemodyfikowalny; null → pusty")
    void blockedSet_isDefensivelyCopiedAndImmutable() {
        Set<UUID> mutable = new HashSet<>(Set.of(C1));
        PurgedMessages result = new PurgedMessages(0, 0, 0, 0, mutable);
        mutable.add(C2);

        assertThat(result.contactIdsBlocked()).containsExactly(C1);
        assertThatThrownBy(() -> result.contactIdsBlocked().add(C2)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new PurgedMessages(0, 0, 0, 0, null).contactIdsBlocked()).isEmpty();
    }

    // =========================================================================
    // BE125-09: contains(null) nie może rzucać NPE (Set.of/Set.copyOf rzucają)
    // =========================================================================

    @Test
    @DisplayName("contains(null) zwraca false, a nie NPE — dla zbioru pustego, niepustego, z empty() i po plus()")
    void containsNull_returnsFalseInsteadOfThrowing() {
        PurgedMessages nonEmpty = new PurgedMessages(0, 0, 0, 0, Set.of(C1));

        assertThat(nonEmpty.contactIdsBlocked().contains(null)).isFalse();
        assertThat(PurgedMessages.empty().contactIdsBlocked().contains(null)).isFalse();
        assertThat(new PurgedMessages(0, 0, 0, 0, null).contactIdsBlocked().contains(null)).isFalse();
        assertThat(new PurgedMessages(0, 0, 0, 0, Set.of()).contactIdsBlocked().contains(null)).isFalse();
        assertThat(PurgedMessages.empty().plus(nonEmpty).contactIdsBlocked().contains(null)).isFalse();
    }

    @Test
    @DisplayName("wzorzec z BE-126/127: blocked.contains(contactId) dla wiadomości osieroconej (contactId = null) nie rzuca")
    void orphanRowLookup_doesNotThrow() {
        Set<UUID> blocked = new PurgedMessages(0, 0, 0, 0, Set.of(C1)).contactIdsBlocked();
        UUID orphanContactId = null;

        assertThat(blocked.contains(orphanContactId)).isFalse();
        assertThat(blocked.contains(C1)).isTrue();
        assertThat(blocked.contains(C2)).isFalse();
    }

    @Test
    @DisplayName("zbiór po zmianie na HashSet nadal jest niemodyfikowalny: add/remove/clear/iterator.remove rzucają")
    void blockedSet_staysImmutable() {
        Set<UUID> blocked = new PurgedMessages(0, 0, 0, 0, Set.of(C1)).contactIdsBlocked();

        assertThatThrownBy(() -> blocked.add(C2)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> blocked.remove(C1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(blocked::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> {
            var it = blocked.iterator();
            it.next();
            it.remove();
        }).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> blocked.addAll(Set.of(C2))).isInstanceOf(UnsupportedOperationException.class);
        assertThat(blocked).containsExactly(C1);
    }

    @Test
    @DisplayName("plus() zwraca zbiór odporny na contains(null) i niemodyfikowalny; operandy pozostają nietknięte")
    void plus_resultIsNullSafeAndImmutable_operandsUntouched() {
        PurgedMessages a = new PurgedMessages(1, 0, 0, 0, Set.of(C1));
        PurgedMessages b = new PurgedMessages(1, 0, 0, 0, Set.of(C2));

        PurgedMessages sum = a.plus(b);

        assertThat(sum.contactIdsBlocked()).containsExactlyInAnyOrder(C1, C2);
        assertThat(sum.contactIdsBlocked().contains(null)).isFalse();
        assertThatThrownBy(() -> sum.contactIdsBlocked().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(a.contactIdsBlocked()).containsExactly(C1);
        assertThat(b.contactIdsBlocked()).containsExactly(C2);
    }

    @Test
    @DisplayName("równość i hashCode rekordu nie zależą od implementacji zbioru (Set.of vs kopia)")
    void equalsAndHashCode_areImplementationIndependent() {
        PurgedMessages fromSetOf = new PurgedMessages(2, 1, 0, 0, Set.of(C1, C2));
        PurgedMessages fromHashSet = new PurgedMessages(2, 1, 0, 0, new HashSet<>(Set.of(C2, C1)));

        assertThat(fromSetOf).isEqualTo(fromHashSet).hasSameHashCodeAs(fromHashSet);
        assertThat(fromHashSet.contactIdsBlocked()).isEqualTo(Set.of(C1, C2));
        assertThat(Set.of(C1, C2)).isEqualTo(fromHashSet.contactIdsBlocked());
    }

    @Test
    @DisplayName("element null na wejściu to błąd wołającego — odrzucany jak dawniej (NPE), nie wchodzi do zbioru")
    void nullElementOnInput_isRejected() {
        Set<UUID> withNull = new HashSet<>();
        withNull.add(C1);
        withNull.add(null);

        assertThatThrownBy(() -> new PurgedMessages(0, 0, 0, 0, withNull))
                .isInstanceOf(NullPointerException.class);
    }
}
