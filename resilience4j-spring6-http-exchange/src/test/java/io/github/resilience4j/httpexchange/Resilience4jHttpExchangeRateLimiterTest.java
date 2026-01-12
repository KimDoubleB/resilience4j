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

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Tests the integration of the {@link Resilience4jHttpExchange} with {@link RateLimiter}
 */
class Resilience4jHttpExchangeRateLimiterTest {

    private MockWebServer server;
    private RestClient restClient;
    private TestService testService;
    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        restClient = RestClient.builder()
            .baseUrl(server.url("/").toString())
            .build();

        rateLimiter = mock(RateLimiter.class);
        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withRateLimiter(rateLimiter)
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
        setupStub(200);
        when(rateLimiter.acquirePermission(1)).thenReturn(true);

        testService.greeting();

        assertThat(server.getRequestCount()).isEqualTo(1);
        verify(rateLimiter).acquirePermission(anyInt());
    }

    @Test
    void testSuccessfulCallWithDefaultMethod() {
        setupStub(200);
        when(rateLimiter.acquirePermission(1)).thenReturn(true);

        testService.defaultGreeting();

        assertThat(server.getRequestCount()).isEqualTo(1);
        verify(rateLimiter).acquirePermission(anyInt());
    }

    @Test
    void testRateLimiterLimiting() {
        setupStub(200);
        when(rateLimiter.acquirePermission(1)).thenReturn(false);
        when(rateLimiter.getRateLimiterConfig()).thenReturn(RateLimiterConfig.ofDefaults());

        assertThatThrownBy(() -> testService.greeting())
            .isInstanceOf(RequestNotPermitted.class);

        assertThat(server.getRequestCount()).isEqualTo(0);
    }

    @Test
    void testFailedHttpCall() {
        setupStub(500);
        when(rateLimiter.acquirePermission(1)).thenReturn(true);

        assertThatThrownBy(() -> testService.greeting())
            .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void testRateLimiterWithRealConfig() throws IOException {
        server.shutdown();
        server = new MockWebServer();
        server.start();
        restClient = RestClient.builder()
            .baseUrl(server.url("/").toString())
            .build();

        RateLimiter realRateLimiter = RateLimiter.of("test",
            RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .limitForPeriod(2)
                .timeoutDuration(Duration.ofMillis(100))
                .build());

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withRateLimiter(realRateLimiter)
            .build();

        TestService service = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        // Enqueue responses
        for (int i = 0; i < 5; i++) {
            server.enqueue(new MockResponse()
                .setBody("Response " + i)
                .setHeader("Content-Type", "text/plain"));
        }

        // First two calls should succeed
        assertThat(service.greeting()).isNotNull();
        assertThat(service.greeting()).isNotNull();

        // Third call should be rate limited
        assertThatThrownBy(service::greeting)
            .isInstanceOf(RequestNotPermitted.class);
    }

    @Test
    void testRateLimiterMetrics() throws IOException {
        server.shutdown();
        server = new MockWebServer();
        server.start();
        restClient = RestClient.builder()
            .baseUrl(server.url("/").toString())
            .build();

        RateLimiter realRateLimiter = RateLimiter.of("test",
            RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .limitForPeriod(5)
                .timeoutDuration(Duration.ofMillis(100))
                .build());

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withRateLimiter(realRateLimiter)
            .build();

        TestService service = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        // Enqueue responses
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse()
                .setBody("Response")
                .setHeader("Content-Type", "text/plain"));
        }

        service.greeting();
        service.greeting();
        service.greeting();

        RateLimiter.Metrics metrics = realRateLimiter.getMetrics();
        assertThat(metrics.getAvailablePermissions()).isEqualTo(2);
        assertThat(metrics.getNumberOfWaitingThreads()).isEqualTo(0);
    }

    private void setupStub(int responseCode) {
        server.enqueue(new MockResponse()
            .setResponseCode(responseCode)
            .setHeader("Content-Type", "text/plain")
            .setBody("hello world"));
    }
}
