package com.example.webhook.delivery;

import com.example.webhook.tenant.TenantAwareRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DeliveryRepository extends TenantAwareRepository<Delivery, UUID> {
}
