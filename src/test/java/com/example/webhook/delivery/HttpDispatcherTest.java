package com.example.webhook.delivery;

import com.example.webhook.delivery.HttpDispatcher.DispatchResult;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@EnableWireMock
class HttpDispatcherTest {

    @InjectWireMock
    private WireMockServer wireMock;

    private final SignatureGenerator signatureGenerator = new SignatureGenerator();

    @Test
    void successfulDelivery() {
        wireMock.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        HttpDispatcher dispatcher = new HttpDispatcher(signatureGenerator);
        DispatchResult result = dispatcher.dispatch(
                wireMock.baseUrl() + "/webhook", "{\"key\":\"value\"}", "test-secret");

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.error()).isNull();
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
        wireMock.verify(postRequestedFor(urlEqualTo("/webhook"))
                .withHeader("X-Webhook-Signature", matching("sha256=.+"))
                .withHeader("X-Webhook-Timestamp", matching("\\d+")));
    }

    @Test
    void endpointReturns500() {
        wireMock.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse().withStatus(500).withBody("server error")));

        HttpDispatcher dispatcher = new HttpDispatcher(signatureGenerator);
        DispatchResult result = dispatcher.dispatch(
                wireMock.baseUrl() + "/webhook", "{}", "test-secret");

        assertThat(result.statusCode()).isEqualTo(500);
    }

    @Test
    void endpointHangsTimeout() {
        // Fixed delay longer than our 10s read timeout — use 15s
        wireMock.stubFor(post(urlEqualTo("/hang"))
                .willReturn(aResponse().withFixedDelay(15_000).withStatus(200)));

        HttpDispatcher dispatcher = new HttpDispatcher(signatureGenerator);
        long start = System.currentTimeMillis();
        DispatchResult result = dispatcher.dispatch(
                wireMock.baseUrl() + "/hang", "{}", "test-secret");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.statusCode()).isNull();
        assertThat(result.error()).isNotNull();
        // Should return within ~12s, not 15s
        assertThat(elapsed).isLessThan(14_000L);
    }
}
