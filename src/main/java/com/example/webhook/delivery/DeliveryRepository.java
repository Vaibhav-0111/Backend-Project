package com.example.webhook.delivery;

import com.example.webhook.tenant.TenantAwareRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeliveryRepository extends TenantAwareRepository<Delivery, UUID> {
    Optional<Delivery> findByIdAndTenantId(UUID id, String tenantId);
    List<Delivery> findByEventIdAndTenantId(UUID eventId, String tenantId);
    List<Delivery> findByEndpointIdAndTenantId(UUID endpointId, String tenantId);
}
