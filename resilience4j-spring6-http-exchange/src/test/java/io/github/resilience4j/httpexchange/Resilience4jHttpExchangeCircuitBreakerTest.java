/*
 *
 * Copyright 2024
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 *
 */
package io.github.resilience4j.httpexchange;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the integration of the {@link Resilience4jHttpExchange} with {@link CircuitBreaker}
 */
class Resilience4jHttpExchangeCircuitBreakerTest {

    private static final CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
        .slidingWindowSize(3)
        .waitDurationInOpenState(Duration.ofMillis(1000))
        .build();

    private MockWebServer server;
    private RestClient restClient;
    private CircuitBreaker circuitBreaker;
    private TestService testService;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        restClient = RestClient.builder()
            .baseUrl(server.url("/").toString())
            .build();

        circuitBreaker = CircuitBreaker.of("test", circuitBreakerConfig);
        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withCircuitBreaker(circuitBreaker)
            .build();
        testService = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            try {
                server.shutdown();
            } catch (IOException e) {
                // Ignore shutdown errors
            }
        }
    }

    @Test
    void testSuccessfulCall() {
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

        setupStub(200);

        testService.greeting();

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(metrics.getNumberOfSuccessfulCalls())
            .describedAs("Successful Calls")
            .isEqualTo(1);
    }

    @Test
    void testSuccessfulCallWithDefaultMethod() {
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

        setupStub(200);

        testService.defaultGreeting();

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(metrics.getNumberOfSuccessfulCalls())
            .describedAs("Successful Calls")
            .isEqualTo(1);
    }

    @Test
    void testFailedCall() {
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

        setupStub(500);

        assertThatThrownBy(() -> testService.greeting())
            .isInstanceOf(Exception.class);

        assertThat(metrics.getNumberOfFailedCalls())
            .describedAs("Failed Calls")
            .isEqualTo(1);
    }

    @Test
    void testCircuitBreakerOpen() {
        int threshold = circuitBreaker.getCircuitBreakerConfig().getSlidingWindowSize() + 1;

        setupMultipleStubs(500, threshold);

        for (int i = 0; i < threshold - 1; i++) {
            try {
                testService.greeting();
            } catch (Exception ex) {
                // ignore
            }
        }

        // Circuit should be open now
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Further calls should throw CallNotPermittedException
        assertThatThrownBy(() -> testService.greeting())
            .isInstanceOf(CallNotPermittedException.class);

        assertThat(circuitBreaker.tryAcquirePermission())
            .describedAs("CircuitBreaker Closed")
            .isFalse();
    }

    @Test
    void testCircuitBreakerClosed() {
        int threshold = circuitBreaker.getCircuitBreakerConfig().getSlidingWindowSize() - 1;

        setupMultipleStubs(500, threshold);

        for (int i = 0; i < threshold; i++) {
            try {
                testService.greeting();
            } catch (Exception ex) {
                // ignore
            }
        }

        assertThat(circuitBreaker.tryAcquirePermission())
            .describedAs("CircuitBreaker Closed")
            .isTrue();
    }

    @Test
    void testCircuitBreakerRecordsMetricsCorrectly() {
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

        // Enqueue success, fail, success
        server.enqueue(new MockResponse()
            .setBody("success")
            .setHeader("Content-Type", "text/plain"));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse()
            .setBody("success")
            .setHeader("Content-Type", "text/plain"));

        testService.greeting();
        try {
            testService.greeting();
        } catch (Exception ignored) {
        }
        testService.greeting();

        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(2);
        assertThat(metrics.getNumberOfFailedCalls()).isEqualTo(1);
    }

    private void setupStub(int responseCode) {
        server.enqueue(new MockResponse()
            .setResponseCode(responseCode)
            .setHeader("Content-Type", "text/plain")
            .setBody("hello world"));
    }

    private void setupMultipleStubs(int responseCode, int count) {
        for (int i = 0; i < count; i++) {
            setupStub(responseCode);
        }
    }
}
