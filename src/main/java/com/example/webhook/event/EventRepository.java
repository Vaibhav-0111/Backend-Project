package com.example.webhook.event;

import com.example.webhook.tenant.TenantAwareRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventRepository extends TenantAwareRepository<Event, UUID> {
    Optional<Event> findByTenantIdAndEventIdExternal(String tenantId, String eventIdExternal);
}
