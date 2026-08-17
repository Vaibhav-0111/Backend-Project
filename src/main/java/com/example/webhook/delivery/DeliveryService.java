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
    private final JdbcTemplate jdbcTemplate;

    public DeliveryService(DeliveryRepository deliveryRepository,
                           DeliveryAttemptRepository deliveryAttemptRepository,
                           EndpointRepository endpointRepository,
                           EventRepository eventRepository,
                           HttpDispatcher httpDispatcher,
                           JdbcTemplate jdbcTemplate) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.endpointRepository = endpointRepository;
        this.eventRepository = eventRepository;
        this.httpDispatcher = httpDispatcher;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void executeDelivery(Delivery delivery) {
        // Load event payload
        Optional<Event> eventOpt = eventRepository.findByIdAndTenantId(delivery.getEventId(), delivery.getTenantId());
        Optional<Endpoint> endpointOpt = endpointRepository.findByIdAndTenantId(delivery.getEndpointId(), delivery.getTenantId());

        if (eventOpt.isEmpty() || endpointOpt.isEmpty()) {
            log.warn("Missing event or endpoint for delivery {}", delivery.getId());
            markFailed(delivery, null, null, "Event or endpoint not found");
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
        } else {
            markFailed(delivery, result.statusCode(), result.error(), null);
        }
    }

    private void markFailed(Delivery delivery, Integer statusCode, String error, String reason) {
        int nextAttempt = delivery.getAttemptCount();
        int maxAttempts = 8;

        if (nextAttempt >= maxAttempts) {
            delivery.setStatus("DEAD_LETTERED");
            delivery.setLockedBy(null);
            delivery.setLockedUntil(null);
            deliveryRepository.save(delivery);
            log.warn("Delivery {} dead-lettered after {} attempts", delivery.getId(), nextAttempt);
        } else {
            // Decorrelated jitter backoff (implemented fully in Step 8)
            long baseSec = 30;
            long capSec = 4 * 3600;
            long prevSleep = baseSec;
            for (int i = 0; i < nextAttempt; i++) {
                prevSleep = Math.min(capSec, baseSec + (long) (Math.random() * (prevSleep * 3 - baseSec)));
            }
            long nextDelaySec = Math.min(capSec, baseSec + (long) (Math.random() * (prevSleep * 3 - baseSec)));

            delivery.setStatus("PENDING");
            delivery.setNextAttemptAt(OffsetDateTime.now().plusSeconds(nextDelaySec));
            delivery.setLockedBy(null);
            delivery.setLockedUntil(null);
            deliveryRepository.save(delivery);
            log.warn("Delivery {} failed (attempt {}), next retry in {}s", delivery.getId(), nextAttempt, nextDelaySec);
        }
    }
}
