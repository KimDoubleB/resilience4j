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

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
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

/**
 * Tests the integration of the {@link Resilience4jHttpExchange} with {@link Retry}
 */
class Resilience4jHttpExchangeRetryTest {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private MockWebServer server;
    private RestClient restClient;
    private TestService testService;
    private Retry retry;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        restClient = RestClient.builder()
            .baseUrl(server.url("/").toString())
            .build();

        retry = Retry.ofDefaults("test");
        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withRetry(retry)
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

        String result = testService.greeting();

        assertThat(result).isEqualTo("hello world");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void testSuccessfulCallWithDefaultMethod() {
        setupStub(200);

        String result = testService.defaultGreeting();

        assertThat(result).isEqualTo("hello world");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void testFailedHttpCall() {
        setupMultipleStubs(500, DEFAULT_MAX_ATTEMPTS);

        assertThatThrownBy(() -> testService.greeting())
            .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void testFailedHttpCallWithRetry() {
        retry = Retry.of("test", RetryConfig.custom()
            .retryExceptions(HttpServerErrorException.class)
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(50))
            .build());
        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withRetry(retry)
            .build();
        testService = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        setupMultipleStubs(500, 3);

        assertThatThrownBy(() -> testService.greeting())
            .isInstanceOf(HttpServerErrorException.class);

        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void testSuccessAfterRetry() {
        retry = Retry.of("test", RetryConfig.custom()
            .retryExceptions(HttpServerErrorException.class)
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(50))
            .build());
        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withRetry(retry)
            .build();
        testService = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        // First two calls fail, third succeeds
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse()
            .setBody("success after retry")
            .setHeader("Content-Type", "text/plain"));

        String result = testService.greeting();

        assertThat(result).isEqualTo("success after retry");
        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()).isEqualTo(1);
    }

    @Test
    void testRetryOnResult() {
        retry = Retry.of("test", RetryConfig.<String>custom()
            .retryOnResult(s -> s.equalsIgnoreCase("retry me"))
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(50))
            .build());
        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withRetry(retry)
            .build();
        testService = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        // First two return "retry me", third returns success
        server.enqueue(new MockResponse()
            .setBody("retry me")
            .setHeader("Content-Type", "text/plain"));
        server.enqueue(new MockResponse()
            .setBody("retry me")
            .setHeader("Content-Type", "text/plain"));
        server.enqueue(new MockResponse()
            .setBody("success")
            .setHeader("Content-Type", "text/plain"));

        String result = testService.greeting();

        assertThat(result).isEqualTo("success");
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void testRetryMetrics() {
        retry = Retry.of("test", RetryConfig.custom()
            .retryExceptions(HttpServerErrorException.class)
            .maxAttempts(2)
            .waitDuration(Duration.ofMillis(50))
            .build());
        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withRetry(retry)
            .build();
        testService = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        // First call fails, second succeeds
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse()
            .setBody("success")
            .setHeader("Content-Type", "text/plain"));

        testService.greeting();

        Retry.Metrics metrics = retry.getMetrics();
        assertThat(metrics.getNumberOfSuccessfulCallsWithRetryAttempt()).isEqualTo(1);
        assertThat(metrics.getNumberOfSuccessfulCallsWithoutRetryAttempt()).isEqualTo(0);
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
