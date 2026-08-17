package com.example.webhook.endpoint;

import com.example.webhook.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/endpoints")
public class EndpointController {

    private final EndpointRepository endpointRepository;
    private final TenantContext tenantContext;
    private final UrlValidator urlValidator;

    public EndpointController(EndpointRepository endpointRepository, TenantContext tenantContext, UrlValidator urlValidator) {
        this.endpointRepository = endpointRepository;
        this.tenantContext = tenantContext;
        this.urlValidator = urlValidator;
    }

    @PostMapping
    public ResponseEntity<EndpointRegistrationResponse> register(@Valid @RequestBody EndpointRegistrationRequest request) {
        urlValidator.validateUrl(request.getUrl());

        Endpoint endpoint = new Endpoint();
        endpoint.setId(UUID.randomUUID());
        endpoint.setTenantId(tenantContext.getTenantId());
        endpoint.setUrl(request.getUrl());
        endpoint.setSubscribedEventTypes(request.getSubscribedEventTypes());
        endpoint.setStatus("ACTIVE");
        endpoint.setCircuitState("CLOSED");
        endpoint.setCreatedAt(OffsetDateTime.now());
        
        byte[] secretBytes = new byte[32];
        new SecureRandom().nextBytes(secretBytes);
        String secret = Base64.getEncoder().encodeToString(secretBytes);
        endpoint.setSecret(secret);

        Endpoint saved = endpointRepository.save(endpoint);
        return ResponseEntity.status(HttpStatus.CREATED).body(new EndpointRegistrationResponse(saved, secret));
    }

    @GetMapping
    public List<Endpoint> listEndpoints() {
        return endpointRepository.findAllByTenantId(tenantContext.getTenantId());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Endpoint> getEndpoint(@PathVariable UUID id) {
        return endpointRepository.findByIdAndTenantId(id, tenantContext.getTenantId())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEndpoint(@PathVariable UUID id) {
        return endpointRepository.findByIdAndTenantId(id, tenantContext.getTenantId())
                .map(endpoint -> {
                    endpoint.setStatus("DISABLED");
                    endpointRepository.save(endpoint);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
