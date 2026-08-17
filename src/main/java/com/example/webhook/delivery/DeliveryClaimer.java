package com.example.webhook.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class DeliveryClaimer {

    private final JdbcTemplate jdbcTemplate;

    public DeliveryClaimer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Delivery> claimDeliveries(String workerId, int batchSize) {
        jdbcTemplate.update("UPDATE endpoints SET circuit_state = 'HALF_OPEN', cooldown_until = NULL WHERE circuit_state = 'OPEN' AND cooldown_until <= now()");

        String sql = """
            UPDATE deliveries
            SET locked_by = ?, locked_until = now() + interval '30 seconds', status = 'IN_PROGRESS'
            WHERE id IN (
              SELECT sub.id FROM (
                SELECT d.id, d.next_attempt_at, e.circuit_state,
                       ROW_NUMBER() OVER(PARTITION BY e.id ORDER BY d.next_attempt_at) as rn
                FROM deliveries d
                JOIN endpoints e ON d.endpoint_id = e.id
                WHERE d.status = 'PENDING'
                  AND d.next_attempt_at <= now()
                  AND (d.locked_until IS NULL OR d.locked_until < now())
                  AND e.circuit_state IN ('CLOSED', 'HALF_OPEN')
                  AND NOT EXISTS (
                      SELECT 1 FROM deliveries d2
                      WHERE d2.endpoint_id = e.id AND d2.status = 'IN_PROGRESS'
                  )
              ) sub
              WHERE sub.circuit_state = 'CLOSED' OR (sub.circuit_state = 'HALF_OPEN' AND sub.rn = 1)
              ORDER BY sub.next_attempt_at
              FOR UPDATE SKIP LOCKED
              LIMIT ?
            )
            RETURNING id, event_id, endpoint_id, tenant_id, status, attempt_count, next_attempt_at, locked_by, locked_until, last_response_code, last_response_snippet, created_at, updated_at
        """;

        return jdbcTemplate.query(sql, new DeliveryRowMapper(), workerId, batchSize);
    }

    private static class DeliveryRowMapper implements RowMapper<Delivery> {
        @Override
        public Delivery mapRow(ResultSet rs, int rowNum) throws SQLException {
            Delivery d = new Delivery();
            d.setId(rs.getObject("id", UUID.class));
            d.setEventId(rs.getObject("event_id", UUID.class));
            d.setEndpointId(rs.getObject("endpoint_id", UUID.class));
            d.setTenantId(rs.getString("tenant_id"));
            d.setStatus(rs.getString("status"));
            d.setAttemptCount(rs.getInt("attempt_count"));
            d.setNextAttemptAt(rs.getObject("next_attempt_at", OffsetDateTime.class));
            d.setLockedBy(rs.getString("locked_by"));
            d.setLockedUntil(rs.getObject("locked_until", OffsetDateTime.class));
            d.setLastResponseCode(rs.getObject("last_response_code", Integer.class));
            d.setLastResponseSnippet(rs.getString("last_response_snippet"));
            d.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
            d.setUpdatedAt(rs.getObject("updated_at", OffsetDateTime.class));
            return d;
        }
    }
}
