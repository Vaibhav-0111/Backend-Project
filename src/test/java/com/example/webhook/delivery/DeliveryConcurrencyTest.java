package com.example.webhook.delivery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
public class DeliveryConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DeliveryClaimer deliveryClaimer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testConcurrentClaims() throws InterruptedException, ExecutionException {
        // Setup: insert 1 tenant, 1 endpoint, 1 event, and 100 deliveries
        String tenantId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantId, "Tenant 1");
        
        UUID endpointId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO endpoints (id, tenant_id, url, secret, subscribed_event_types, status) VALUES (?, ?, ?, ?, '{\"*\"}', 'ACTIVE')", 
                endpointId, tenantId, "http://localhost", "secret");

        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO events (id, tenant_id, event_id_external, type, payload) VALUES (?, ?, ?, ?, ?::jsonb)", 
                eventId, tenantId, "ext-1", "test.event", "{}");

        for (int i = 0; i < 100; i++) {
            // Use distinct endpoints to avoid UNIQUE(event_id, endpoint_id) constraint on deliveries.
            UUID epId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO endpoints (id, tenant_id, url, secret, subscribed_event_types, status) VALUES (?, ?, ?, ?, '{\"*\"}', 'ACTIVE')", 
                    epId, tenantId, "http://localhost/" + i, "secret");

            jdbcTemplate.update("""
                INSERT INTO deliveries (id, event_id, endpoint_id, tenant_id, status, next_attempt_at) 
                VALUES (?, ?, ?, ?, 'PENDING', now() - interval '1 minute')
                """, UUID.randomUUID(), eventId, epId, tenantId);
        }

        int workerCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CyclicBarrier barrier = new CyclicBarrier(workerCount);
        
        List<Future<Integer>> futures = new ArrayList<>();
        
        for (int i = 0; i < workerCount; i++) {
            String workerId = "worker-" + i;
            futures.add(executor.submit(() -> {
                barrier.await(); 
                int claimed = 0;
                while (true) {
                    List<Delivery> list = deliveryClaimer.claimDeliveries(workerId, 10);
                    if (list.isEmpty()) {
                        break;
                    }
                    claimed += list.size();
                }
                return claimed;
            }));
        }

        int totalClaimed = 0;
        for (Future<Integer> f : futures) {
            totalClaimed += f.get();
        }
        
        assertThat(totalClaimed).isEqualTo(100);
        
        Integer inProgressCount = jdbcTemplate.queryForObject("SELECT count(*) FROM deliveries WHERE status = 'IN_PROGRESS' AND tenant_id = ?", Integer.class, tenantId);
        assertThat(inProgressCount).isEqualTo(100);
        
        executor.shutdown();
    }
}
