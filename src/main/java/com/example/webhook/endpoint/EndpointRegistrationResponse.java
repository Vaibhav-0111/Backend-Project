package com.example.webhook.endpoint;

public record EndpointRegistrationResponse(Endpoint endpoint, String secret) {
}
