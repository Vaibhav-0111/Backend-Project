package com.example.webhook.delivery;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class HttpDispatcher {

    private final HttpClient httpClient;
    private final SignatureGenerator signatureGenerator;

    public HttpDispatcher(SignatureGenerator signatureGenerator) {
        this.signatureGenerator = signatureGenerator;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public DispatchResult dispatch(String url, String payload, String secret) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String signature = signatureGenerator.generateSignature(payload, timestamp, secret);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10)) 
                .header("Content-Type", "application/json")
                .header("X-Webhook-Timestamp", timestamp)
                .header("X-Webhook-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;
            return new DispatchResult(response.statusCode(), response.body(), latency, null);
        } catch (java.net.http.HttpTimeoutException e) {
            return new DispatchResult(null, null, System.currentTimeMillis() - start, "Timeout");
        } catch (Exception e) {
            return new DispatchResult(null, null, System.currentTimeMillis() - start, e.getMessage());
        }
    }

    public record DispatchResult(Integer statusCode, String body, long latencyMs, String error) {}
}
