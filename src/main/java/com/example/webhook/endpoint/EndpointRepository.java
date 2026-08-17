package com.example.webhook.endpoint;

import com.example.webhook.tenant.TenantAwareRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface EndpointRepository extends TenantAwareRepository<Endpoint, UUID> {
}
