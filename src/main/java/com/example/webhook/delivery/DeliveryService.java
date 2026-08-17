package com.example.webhook.delivery;

import com.example.webhook.endpoint.Endpoint;
import com.example.webhook.endpoint.EndpointRepository;
import com.example.webhook.event.Event;
import com.example.webhook.event.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeliveryService {
    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    private static final int MAX_SNIPPET_LENGTH = 500;

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final EndpointRepository endpointRepository;
    private final EventRepository eventRepository;
    private final HttpDispatcher httpDispatcher;
    private final BackoffCalculator backoffCalculator;
    private final JdbcTemplate jdbcTemplate;

    public DeliveryService(DeliveryRepository deliveryRepository,
                           DeliveryAttemptRepository deliveryAttemptRepository,
                           EndpointRepository endpointRepository,
                           EventRepository eventRepository,
                           HttpDispatcher httpDispatcher,
                           BackoffCalculator backoffCalculator,
                           JdbcTemplate jdbcTemplate) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.endpointRepository = endpointRepository;
        this.eventRepository = eventRepository;
        this.httpDispatcher = httpDispatcher;
        this.backoffCalculator = backoffCalculator;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void executeDelivery(Delivery delivery) {
        Optional<Event> eventOpt = eventRepository.findByIdAndTenantId(delivery.getEventId(), delivery.getTenantId());
        Optional<Endpoint> endpointOpt = endpointRepository.findByIdAndTenantId(delivery.getEndpointId(), delivery.getTenantId());

        if (eventOpt.isEmpty() || endpointOpt.isEmpty()) {
            log.warn("Missing event or endpoint for delivery {}", delivery.getId());
            markFailed(delivery, null, "Event or endpoint not found");
            return;
        }

        Event event = eventOpt.get();
        Endpoint endpoint = endpointOpt.get();

        log.info("Dispatching delivery {} to {}", delivery.getId(), endpoint.getUrl());

        HttpDispatcher.DispatchResult result = httpDispatcher.dispatch(
                endpoint.getUrl(), event.getPayload(), endpoint.getSecret());

        boolean success = result.statusCode() != null && result.statusCode() >= 200 && result.statusCode() < 300;

        // Record attempt
        DeliveryAttempt attempt = new DeliveryAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setDeliveryId(delivery.getId());
        attempt.setAttemptNumber(delivery.getAttemptCount() + 1);
        attempt.setResponseCode(result.statusCode());
        attempt.setLatencyMs((int) result.latencyMs());
        attempt.setError(result.error());
        attempt.setCreatedAt(OffsetDateTime.now());
        deliveryAttemptRepository.save(attempt);

        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setLastResponseCode(result.statusCode());

        String snippet = result.body() != null && result.body().length() > MAX_SNIPPET_LENGTH
                ? result.body().substring(0, MAX_SNIPPET_LENGTH)
                : result.body();
        delivery.setLastResponseSnippet(snippet);
        delivery.setUpdatedAt(OffsetDateTime.now());

        if (success) {
            delivery.setStatus("DELIVERED");
            delivery.setLockedBy(null);
            delivery.setLockedUntil(null);
            deliveryRepository.save(delivery);
            log.info("Delivery {} succeeded with status {}", delivery.getId(), result.statusCode());
            
            // Circuit breaker: reset on success
            updateCircuitBreakerOnSuccess(endpoint);
        } else {
            markFailed(delivery, result.statusCode(), result.error());
            
            // Circuit breaker: record failure
            updateCircuitBreakerOnFailure(endpoint);
        }
    }

    private void markFailed(Delivery delivery, Integer statusCode, String error) {
        if (backoffCalculator.isMaxAttemptsReached(delivery.getAttemptCount())) {
            delivery.setStatus("DEAD_LETTERED");
            delivery.setLockedBy(null);
            delivery.setLockedUntil(null);
            deliveryRepository.save(delivery);
            log.warn("Delivery {} dead-lettered after {} attempts", delivery.getId(), delivery.getAttemptCount());
        } else {
            long delaySec = backoffCalculator.calculateDelay(delivery.getAttemptCount());
            delivery.setStatus("PENDING");
            delivery.setNextAttemptAt(OffsetDateTime.now().plusSeconds(delaySec));
            delivery.setLockedBy(null);
            delivery.setLockedUntil(null);
            deliveryRepository.save(delivery);
            log.warn("Delivery {} failed (attempt {}), next retry in {}s", 
                    delivery.getId(), delivery.getAttemptCount(), delaySec);
        }
    }

    @Transactional
    public boolean redrive(UUID deliveryId, String tenantId) {
        Optional<Delivery> opt = deliveryRepository.findByIdAndTenantId(deliveryId, tenantId);
        if (opt.isEmpty()) return false;
        
        Delivery delivery = opt.get();
        if (!"DEAD_LETTERED".equals(delivery.getStatus())) return false;
        
        delivery.setStatus("PENDING");
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(OffsetDateTime.now());
        delivery.setLockedBy(null);
        delivery.setLockedUntil(null);
        delivery.setUpdatedAt(OffsetDateTime.now());
        deliveryRepository.save(delivery);
        log.info("Delivery {} redriven by tenant {}", deliveryId, tenantId);
        return true;
    }

    private void updateCircuitBreakerOnSuccess(Endpoint endpoint) {
        if (!"CLOSED".equals(endpoint.getCircuitState())) {
            jdbcTemplate.update(
                "UPDATE endpoints SET circuit_state = 'CLOSED', cooldown_until = NULL WHERE id = ?",
                endpoint.getId());
        }
    }

    private void updateCircuitBreakerOnFailure(Endpoint endpoint) {
        // Count consecutive recent failures for this endpoint
        Integer recentFailures = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM delivery_attempts da
            JOIN deliveries d ON d.id = da.delivery_id
            WHERE d.endpoint_id = ? AND da.created_at > now() - interval '5 minutes'
            AND (da.response_code IS NULL OR da.response_code >= 400)
        """, Integer.class, endpoint.getId());

        if (recentFailures != null && recentFailures >= 5 && "CLOSED".equals(endpoint.getCircuitState())) {
            // Trip to OPEN
            jdbcTemplate.update(
                "UPDATE endpoints SET circuit_state = 'OPEN', cooldown_until = now() + interval '60 seconds' WHERE id = ?",
                endpoint.getId());
            log.warn("Circuit breaker OPENED for endpoint {} after {} consecutive failures", 
                    endpoint.getId(), recentFailures);
        }
    }
}
