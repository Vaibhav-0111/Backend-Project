package com.example.webhook.event;


import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public class EventIngestionRequest {
    @NotEmpty
    private String eventId;
    
    @NotEmpty
    private String type;
    
    @NotNull
    private Object payload;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    
    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }
}
