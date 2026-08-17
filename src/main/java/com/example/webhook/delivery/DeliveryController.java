package com.example.webhook.delivery;

import com.example.webhook.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/deliveries")
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

    @GetMapping
    public ResponseEntity<List<Delivery>> listDeliveries() {
        return ResponseEntity.ok(deliveryRepository.findAllByTenantId(tenantContext.getTenantId()));
    }

    @GetMapping("/{id}/attempts")
    public ResponseEntity<Iterable<DeliveryAttempt>> listAttempts(@PathVariable UUID id) {
        // Validate tenant owns the delivery
        return deliveryRepository.findByIdAndTenantId(id, tenantContext.getTenantId())
                .map(delivery -> ResponseEntity.ok((Iterable<DeliveryAttempt>) deliveryAttemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(id)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/redrive")
    public ResponseEntity<Void> redrive(@PathVariable UUID id) {
        boolean success = deliveryService.redrive(id, tenantContext.getTenantId());
        return success ? ResponseEntity.accepted().build() : ResponseEntity.notFound().build();
    }
}
