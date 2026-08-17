package com.example.webhook.tenant;

import org.springframework.stereotype.Component;

@Component
public class TenantContext {
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    public String getTenantId() {
        return CURRENT_TENANT.get();
    }

    public void setTenantId(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }
    
    public void clear() {
        CURRENT_TENANT.remove();
    }
}
