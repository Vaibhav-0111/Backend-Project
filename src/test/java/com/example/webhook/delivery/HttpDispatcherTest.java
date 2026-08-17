package com.example.webhook.delivery;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.example.webhook.delivery.HttpDispatcher.DispatchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests HTTP dispatch behaviour using a plain WireMock server (no Spring context).
 * HttpDispatcher only depends on SignatureGenerator, so no ApplicationContext needed.
 */
class HttpDispatcherTest {

    private WireMockServer wireMock;
    private HttpDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        dispatcher = new HttpDispatcher(new SignatureGenerator());
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void successfulDelivery() {
        wireMock.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

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

        DispatchResult result = dispatcher.dispatch(
                wireMock.baseUrl() + "/webhook", "{}", "test-secret");

        assertThat(result.statusCode()).isEqualTo(500);
        assertThat(result.error()).isNull();
    }

    @Test
    void endpointHangsAndTimesOut() {
        // Delay longer than our 10s read timeout
        wireMock.stubFor(post(urlEqualTo("/hang"))
                .willReturn(aResponse().withFixedDelay(15_000).withStatus(200)));

        long start = System.currentTimeMillis();
        DispatchResult result = dispatcher.dispatch(
                wireMock.baseUrl() + "/hang", "{}", "test-secret");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.statusCode()).isNull();
        assertThat(result.error()).isNotNull();
        // Should bail before 15s
        assertThat(elapsed).isLessThan(14_000L);
    }
}
