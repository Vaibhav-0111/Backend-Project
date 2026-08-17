package com.example.webhook.endpoint;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public class EndpointRegistrationRequest {
    @NotNull
    private String url;
    
    @NotEmpty
    private List<String> subscribedEventTypes;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public List<String> getSubscribedEventTypes() { return subscribedEventTypes; }
    public void setSubscribedEventTypes(List<String> subscribedEventTypes) { this.subscribedEventTypes = subscribedEventTypes; }
}
