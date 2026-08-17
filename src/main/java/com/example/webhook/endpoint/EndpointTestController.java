package com.example.webhook.endpoint;

import com.example.webhook.delivery.HttpDispatcher;
import com.example.webhook.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/endpoints")
public class EndpointTestController {

    private final EndpointRepository endpointRepository;
    private final TenantContext tenantContext;
    private final HttpDispatcher httpDispatcher;

    public EndpointTestController(EndpointRepository endpointRepository,
                                   TenantContext tenantContext,
                                   HttpDispatcher httpDispatcher) {
        this.endpointRepository = endpointRepository;
        this.tenantContext = tenantContext;
        this.httpDispatcher = httpDispatcher;
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<?> testEndpoint(@PathVariable java.util.UUID id) {
        return endpointRepository.findByIdAndTenantId(id, tenantContext.getTenantId())
                .map(endpoint -> {
                    String testPayload = "{\"type\":\"webhook.test\",\"message\":\"Synthetic test event\"}";
                    HttpDispatcher.DispatchResult result = httpDispatcher.dispatch(
                            endpoint.getUrl(), testPayload, endpoint.getSecret());
                    boolean success = result.statusCode() != null
                            && result.statusCode() >= 200 && result.statusCode() < 300;
                    return ResponseEntity.ok(Map.of(
                            "reachable", success,
                            "statusCode", result.statusCode() != null ? result.statusCode() : 0,
                            "latencyMs", result.latencyMs(),
                            "error", result.error() != null ? result.error() : ""
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
