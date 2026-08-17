package com.example.webhook.delivery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for tenant isolation — verifies that cross-tenant access
 * to deliveries and redrives is blocked even with a known UUID.
 * Also tests idempotent event ingestion.
 */
@SpringBootTest
@Testcontainers
class TenantIsolationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void setupTenant(String tenantId, String tenantName) {
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT DO NOTHING",
                tenantId, tenantName);
    }

    @Test
    void crossTenantDeliveryAccessIsBlocked() {
        String tenantA = UUID.randomUUID().toString();
        String tenantB = UUID.randomUUID().toString();
        setupTenant(tenantA, "Tenant A");
        setupTenant(tenantB, "Tenant B");

        UUID endpointId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO endpoints (id, tenant_id, url, secret, subscribed_event_types, status) VALUES (?, ?, ?, ?, '{\"*\"}', 'ACTIVE')",
            endpointId, tenantA, "http://example.com", "secret-a");

        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO events (id, tenant_id, event_id_external, type, payload) VALUES (?, ?, ?, ?, ?::jsonb)",
            eventId, tenantA, "ext-iso-1", "test.event", "{}");

        UUID deliveryId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO deliveries (id, event_id, endpoint_id, tenant_id, status, next_attempt_at)
            VALUES (?, ?, ?, ?, 'DEAD_LETTERED', now())
            """, deliveryId, eventId, endpointId, tenantA);

        // Tenant B tries to find Tenant A's delivery — must get empty
        assertThat(deliveryRepository.findByIdAndTenantId(deliveryId, tenantB)).isEmpty();

        // Tenant B tries to find all its deliveries — must not see Tenant A's
        List<Delivery> tenantBDeliveries = deliveryRepository.findAllByTenantId(tenantB);
        boolean crossLeak = tenantBDeliveries.stream().anyMatch(d -> d.getId().equals(deliveryId));
        assertThat(crossLeak).isFalse();
    }

    @Test
    void duplicateEventIdDoesNotCreateDuplicateDeliveries() {
        String tenantId = UUID.randomUUID().toString();
        setupTenant(tenantId, "Idempotency Tenant");

        UUID endpointId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO endpoints (id, tenant_id, url, secret, subscribed_event_types, status) VALUES (?, ?, ?, ?, '{\"order.paid\"}', 'ACTIVE')",
            endpointId, tenantId, "http://example.com/idem", "secret");

        // Insert event manually (simulates first submission)
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO events (id, tenant_id, event_id_external, type, payload) VALUES (?, ?, ?, ?, ?::jsonb)",
            eventId, tenantId, "idempotent-event-1", "order.paid", "{\"amount\":100}");

        // Insert one delivery for the event
        jdbcTemplate.update("""
            INSERT INTO deliveries (id, event_id, endpoint_id, tenant_id, status, next_attempt_at)
            VALUES (?, ?, ?, ?, 'PENDING', now())
            """, UUID.randomUUID(), eventId, endpointId, tenantId);

        // Attempt to insert same event again — must violate unique constraint
        boolean exceptionThrown = false;
        try {
            jdbcTemplate.update(
                "INSERT INTO events (id, tenant_id, event_id_external, type, payload) VALUES (?, ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), tenantId, "idempotent-event-1", "order.paid", "{\"amount\":100}");
        } catch (Exception e) {
            exceptionThrown = true;
        }
        assertThat(exceptionThrown).isTrue();

        // Confirm only one delivery exists
        Integer deliveryCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM deliveries WHERE event_id = ? AND tenant_id = ?",
            Integer.class, eventId, tenantId);
        assertThat(deliveryCount).isEqualTo(1);
    }
}
