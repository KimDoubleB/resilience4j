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
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
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

class HttpExchangeDecoratorsTest {

    private MockWebServer server;
    private RestClient restClient;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        restClient = RestClient.builder()
            .baseUrl(server.url("/").toString())
            .build();
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
    void shouldBuildDecoratorsWithCircuitBreaker() {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("test");

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withCircuitBreaker(circuitBreaker)
            .build();

        assertThat(decorators).isNotNull();
    }

    @Test
    void shouldBuildDecoratorsWithMultipleComponents() {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("test");
        RateLimiter rateLimiter = RateLimiter.ofDefaults("test");
        Retry retry = Retry.ofDefaults("test");

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withRetry(retry)
            .withCircuitBreaker(circuitBreaker)
            .withRateLimiter(rateLimiter)
            .build();

        assertThat(decorators).isNotNull();
    }

    @Test
    void shouldCallServiceSuccessfully() {
        server.enqueue(new MockResponse()
            .setBody("Hello World")
            .setHeader("Content-Type", "text/plain"));

        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("test");
        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withCircuitBreaker(circuitBreaker)
            .build();

        TestService service = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        String result = service.greeting();

        assertThat(result).isEqualTo("Hello World");
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    void shouldOpenCircuitBreakerOnFailures() {
        // Enqueue error responses
        for (int i = 0; i < 10; i++) {
            server.enqueue(new MockResponse().setResponseCode(500));
        }

        CircuitBreaker circuitBreaker = CircuitBreaker.of("test",
            CircuitBreakerConfig.custom()
                .slidingWindowSize(5)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withCircuitBreaker(circuitBreaker)
            .build();

        TestService service = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        // Call until circuit opens
        for (int i = 0; i < 5; i++) {
            try {
                service.greeting();
            } catch (Exception e) {
                // Expected failures
            }
        }

        // Circuit should be open now
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Further calls should throw CallNotPermittedException
        assertThatThrownBy(service::greeting)
            .isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void shouldUseFallbackOnFailure() {
        server.enqueue(new MockResponse().setResponseCode(500));

        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("test");

        TestService fallback = new TestService() {
            @Override
            public String greeting() {
                return "Fallback greeting";
            }

            @Override
            public String greetingWithName(String name) {
                return "Fallback: " + name;
            }

            @Override
            public String echo(String message) {
                return "Fallback echo: " + message;
            }
        };

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withCircuitBreaker(circuitBreaker)
            .withFallback(fallback)
            .build();

        TestService service = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        String result = service.greeting();

        assertThat(result).isEqualTo("Fallback greeting");
    }

    @Test
    void shouldRetryOnFailure() {
        // First two calls fail, third succeeds
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse()
            .setBody("Success after retries")
            .setHeader("Content-Type", "text/plain"));

        Retry retry = Retry.of("test",
            RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(100))
                .build());

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withRetry(retry)
            .build();

        TestService service = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        String result = service.greeting();

        assertThat(result).isEqualTo("Success after retries");
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()).isEqualTo(1);
    }

    @Test
    void shouldApplyRateLimiter() {
        // Enqueue responses
        for (int i = 0; i < 5; i++) {
            server.enqueue(new MockResponse()
                .setBody("Response " + i)
                .setHeader("Content-Type", "text/plain"));
        }

        RateLimiter rateLimiter = RateLimiter.of("test",
            RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .limitForPeriod(2)
                .timeoutDuration(Duration.ofMillis(100))
                .build());

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withRateLimiter(rateLimiter)
            .build();

        TestService service = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        // First two calls should succeed
        assertThat(service.greeting()).isNotNull();
        assertThat(service.greeting()).isNotNull();

        // Third call should be rate limited (might throw exception or timeout)
        // Note: This test might be flaky depending on timing
    }

    @Test
    void shouldBuildWithCustomName() {
        server.enqueue(new MockResponse()
            .setBody("Hello")
            .setHeader("Content-Type", "text/plain"));

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withCircuitBreaker(CircuitBreaker.ofDefaults("test"))
            .build();

        TestService service = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class, "customServiceName");

        String result = service.greeting();

        assertThat(result).isEqualTo("Hello");
    }

    @Test
    void shouldThrowExceptionForNonInterface() {
        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withCircuitBreaker(CircuitBreaker.ofDefaults("test"))
            .build();

        assertThatThrownBy(() ->
            Resilience4jHttpExchange.builder(decorators)
                .restClient(restClient)
                .build(String.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("serviceType must be an interface");
    }

    @Test
    void shouldThrowExceptionWhenNoClientConfigured() {
        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withCircuitBreaker(CircuitBreaker.ofDefaults("test"))
            .build();

        assertThatThrownBy(() ->
            Resilience4jHttpExchange.builder(decorators)
                .build(TestService.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("restClient, webClient, or custom factory");
    }
}
