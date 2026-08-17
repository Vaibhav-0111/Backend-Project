package com.example.webhook.event;

import com.example.webhook.delivery.Delivery;
import com.example.webhook.delivery.DeliveryRepository;
import com.example.webhook.endpoint.Endpoint;
import com.example.webhook.endpoint.EndpointRepository;
import com.example.webhook.tenant.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final DeliveryRepository deliveryRepository;
    private final EndpointRepository endpointRepository;
    private final TenantContext tenantContext;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public EventService(EventRepository eventRepository, DeliveryRepository deliveryRepository, EndpointRepository endpointRepository, TenantContext tenantContext, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.deliveryRepository = deliveryRepository;
        this.endpointRepository = endpointRepository;
        this.tenantContext = tenantContext;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Event ingestEvent(EventIngestionRequest request) {
        String tenantId = tenantContext.getTenantId();
        
        Optional<Event> existing = eventRepository.findByTenantIdAndEventIdExternal(tenantId, request.getEventId());
        if (existing.isPresent()) {
            return existing.get();
        }

        Event event = new Event();
        event.setId(UUID.randomUUID());
        event.setTenantId(tenantId);
        event.setEventIdExternal(request.getEventId());
        event.setType(request.getType());
        try {
            event.setPayload(objectMapper.writeValueAsString(request.getPayload()));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid payload", e);
        }
        event.setCreatedAt(OffsetDateTime.now());

        try {
            event = eventRepository.save(event);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent submission caught by unique constraint
            return eventRepository.findByTenantIdAndEventIdExternal(tenantId, request.getEventId())
                    .orElseThrow(() -> new IllegalStateException("Event disappeared after constraint violation"));
        }

        List<Endpoint> endpoints = endpointRepository.findAllByTenantId(tenantId);
        for (Endpoint endpoint : endpoints) {
            if ("ACTIVE".equals(endpoint.getStatus()) && endpoint.getSubscribedEventTypes().contains(request.getType())) {
                Delivery delivery = new Delivery();
                delivery.setId(UUID.randomUUID());
                delivery.setEventId(event.getId());
                delivery.setEndpointId(endpoint.getId());
                delivery.setTenantId(tenantId);
                delivery.setStatus("PENDING");
                delivery.setAttemptCount(0);
                delivery.setNextAttemptAt(OffsetDateTime.now());
                delivery.setCreatedAt(OffsetDateTime.now());
                delivery.setUpdatedAt(OffsetDateTime.now());
                deliveryRepository.save(delivery);
            }
        }
        
        return event;
    }
}
