package com.example.webhook.delivery;

import com.example.webhook.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DeliveryController {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final DeliveryService deliveryService;
    private final TenantContext tenantContext;

    public DeliveryController(DeliveryRepository deliveryRepository,
                              DeliveryAttemptRepository deliveryAttemptRepository,
                              DeliveryService deliveryService,
                              TenantContext tenantContext) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.deliveryService = deliveryService;
        this.tenantContext = tenantContext;
    }

    // FR-4: GET /api/v1/events/{id}/deliveries
    @GetMapping("/events/{eventId}/deliveries")
    public ResponseEntity<List<Delivery>> listDeliveriesForEvent(@PathVariable UUID eventId) {
        return ResponseEntity.ok(
            deliveryRepository.findByEventIdAndTenantId(eventId, tenantContext.getTenantId()));
    }

    // FR-4: GET /api/v1/endpoints/{id}/deliveries
    @GetMapping("/endpoints/{endpointId}/deliveries")
    public ResponseEntity<List<Delivery>> listDeliveriesForEndpoint(@PathVariable UUID endpointId) {
        return ResponseEntity.ok(
            deliveryRepository.findByEndpointIdAndTenantId(endpointId, tenantContext.getTenantId()));
    }

    // FR-4: GET /api/v1/deliveries/{id}/attempts
    @GetMapping("/deliveries/{id}/attempts")
    public ResponseEntity<List<DeliveryAttempt>> listAttempts(@PathVariable UUID id) {
        return deliveryRepository.findByIdAndTenantId(id, tenantContext.getTenantId())
                .map(delivery -> ResponseEntity.ok(
                    deliveryAttemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(id)))
                .orElse(ResponseEntity.notFound().build());
    }

    // FR-3: POST /api/v1/deliveries/{id}/redrive
    @PostMapping("/deliveries/{id}/redrive")
    public ResponseEntity<Void> redrive(@PathVariable UUID id) {
        boolean success = deliveryService.redrive(id, tenantContext.getTenantId());
        return success ? ResponseEntity.accepted().build() : ResponseEntity.notFound().build();
    }
}
