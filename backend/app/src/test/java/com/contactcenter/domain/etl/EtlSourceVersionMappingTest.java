package com.contactcenter.domain.etl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-228: the CDC mapper carries the source row version ({@code COALESCE(updated_at, created_at)}) into
 * {@link ContactDwRow#sourceUpdatedAt()}, which {@code ClickHouseDwWriter} stores as the {@code argMax} version.
 */
@DisplayName("ETL: wersja źródłowa wiersza trafia do ContactDwRow (BE-228)")
class EtlSourceVersionMappingTest {

    @Test
    @DisplayName("ContactDwRowMapper: effective_updated_at -> sourceUpdatedAt i effectiveAt (znacznik CDC)")
    void contactMapperCarriesSourceVersion() throws Exception {
        Instant version = Instant.parse("2026-11-20T10:15:30.123Z");
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("contact_id")).thenReturn(UUID.randomUUID().toString());
        when(rs.getString("tenant_id")).thenReturn(UUID.randomUUID().toString());
        when(rs.getString("status")).thenReturn("COMPLETED");
        when(rs.getTimestamp("effective_updated_at")).thenReturn(Timestamp.from(version));

        EtlSyncServiceImpl.CdcRow<ContactDwRow> mapped = new EtlSyncServiceImpl.ContactDwRowMapper().mapRow(rs, 0);

        assertThat(mapped.row().sourceUpdatedAt()).isEqualTo(version);
        assertThat(mapped.effectiveAt()).isEqualTo(version);
    }

    @Test
    @DisplayName("konstruktor zgodności wstecznej zostawia sourceUpdatedAt = null (writer użyje zegara zapisu)")
    void legacyConstructorHasNoSourceVersion() {
        ContactDwRow row = new ContactDwRow(UUID.randomUUID(), UUID.randomUUID(), null, null, null, "PHONE", "INBOUND",
                "ACTIVE", "", null, null, null, null);
        assertThat(row.sourceUpdatedAt()).isNull();
    }
}
