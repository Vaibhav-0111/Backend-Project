package com.example.webhook.endpoint;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "endpoints")
public class Endpoint {
    @Id
    private UUID id;
    private String tenantId;
    private String url;
    private String secret;
    
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> subscribedEventTypes;
    
    private String status; // ACTIVE, DISABLED
    private String circuitState;
    private OffsetDateTime cooldownUntil;
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getSecret() { return secret; }
    
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    public void setSecret(String secret) { this.secret = secret; }
    
    public List<String> getSubscribedEventTypes() { return subscribedEventTypes; }
    public void setSubscribedEventTypes(List<String> subscribedEventTypes) { this.subscribedEventTypes = subscribedEventTypes; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public String getCircuitState() { return circuitState; }
    public void setCircuitState(String circuitState) { this.circuitState = circuitState; }
    
    public OffsetDateTime getCooldownUntil() { return cooldownUntil; }
    public void setCooldownUntil(OffsetDateTime cooldownUntil) { this.cooldownUntil = cooldownUntil; }
    
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
