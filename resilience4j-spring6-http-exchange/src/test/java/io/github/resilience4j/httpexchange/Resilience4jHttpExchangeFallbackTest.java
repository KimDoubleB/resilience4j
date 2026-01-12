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
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Tests the integration of the {@link Resilience4jHttpExchange} with fallback.
 */
class Resilience4jHttpExchangeFallbackTest {

    private MockWebServer server;
    private RestClient restClient;
    private TestService testService;
    private TestService testServiceFallback;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        restClient = RestClient.builder()
            .baseUrl(server.url("/").toString())
            .build();

        testServiceFallback = mock(TestService.class);
        when(testServiceFallback.greeting()).thenReturn("fallback");

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withFallback(testServiceFallback)
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
    void testSuccessful() {
        setupStub(200);

        String result = testService.greeting();

        assertThat(result).describedAs("Result").isEqualTo("Hello, world!");
        verify(testServiceFallback, times(0)).greeting();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void testInvalidFallback() {
        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withFallback("not a fallback")
            .build();

        assertThatThrownBy(() ->
            Resilience4jHttpExchange.builder(decorators)
                .restClient(restClient)
                .build(TestService.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Cannot use the fallback");
    }

    @Test
    void testFallback() {
        setupStub(500);

        String result = testService.greeting();

        assertThat(result).describedAs("Result").isNotEqualTo("Hello, world!");
        assertThat(result).describedAs("Result").isEqualTo("fallback");
        verify(testServiceFallback, times(1)).greeting();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void testFallbackExceptionFilter() {
        TestService testServiceExceptionFallback = mock(TestService.class);
        when(testServiceExceptionFallback.greeting()).thenReturn("exception fallback");

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withFallback(testServiceExceptionFallback, HttpServerErrorException.class)
            .withFallback(testServiceFallback)
            .build();

        testService = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);
        setupStub(500);

        String result = testService.greeting();

        assertThat(result).describedAs("Result").isNotEqualTo("Hello, world!");
        assertThat(result).describedAs("Result").isEqualTo("exception fallback");
        verify(testServiceFallback, times(0)).greeting();
        verify(testServiceExceptionFallback, times(1)).greeting();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void testFallbackExceptionFilterNotCalled() {
        TestService testServiceExceptionFallback = mock(TestService.class);
        when(testServiceExceptionFallback.greeting()).thenReturn("exception fallback");

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withFallback(testServiceExceptionFallback, CallNotPermittedException.class)
            .withFallback(testServiceFallback)
            .build();

        testService = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);
        setupStub(500);

        String result = testService.greeting();

        assertThat(result).describedAs("Result").isNotEqualTo("Hello, world!");
        assertThat(result).describedAs("Result").isEqualTo("fallback");
        verify(testServiceFallback, times(1)).greeting();
        verify(testServiceExceptionFallback, times(0)).greeting();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void testFallbackFilter() {
        TestService testServiceFilterFallback = mock(TestService.class);
        when(testServiceFilterFallback.greeting()).thenReturn("filter fallback");

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withFallback(testServiceFilterFallback, ex -> true)
            .withFallback(testServiceFallback)
            .build();

        testService = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);
        setupStub(500);

        String result = testService.greeting();

        assertThat(result).describedAs("Result").isNotEqualTo("Hello, world!");
        assertThat(result).describedAs("Result").isEqualTo("filter fallback");
        verify(testServiceFallback, times(0)).greeting();
        verify(testServiceFilterFallback, times(1)).greeting();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void testFallbackFilterNotCalled() {
        TestService testServiceFilterFallback = mock(TestService.class);
        when(testServiceFilterFallback.greeting()).thenReturn("filter fallback");

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withFallback(testServiceFilterFallback, ex -> false)
            .withFallback(testServiceFallback)
            .build();

        testService = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);
        setupStub(500);

        String result = testService.greeting();

        assertThat(result).describedAs("Result").isNotEqualTo("Hello, world!");
        assertThat(result).describedAs("Result").isEqualTo("fallback");
        verify(testServiceFallback, times(1)).greeting();
        verify(testServiceFilterFallback, times(0)).greeting();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void testRevertFallback() {
        setupStub(500);

        testService.greeting();
        setupStub(200);
        String result = testService.greeting();

        assertThat(result).describedAs("Result").isEqualTo("Hello, world!");
        verify(testServiceFallback, times(1)).greeting();
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void testFallbackWithCircuitBreaker() {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("test");

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withCircuitBreaker(circuitBreaker)
            .withFallback(testServiceFallback)
            .build();

        testService = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        setupStub(500);

        String result = testService.greeting();

        assertThat(result).isEqualTo("fallback");
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void testFallbackWithParameters() {
        when(testServiceFallback.greetingWithName(anyString())).thenReturn("fallback greeting");

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withFallback(testServiceFallback)
            .build();

        testService = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        setupStub(500);

        String result = testService.greetingWithName("John");

        assertThat(result).isEqualTo("fallback greeting");
        verify(testServiceFallback).greetingWithName("John");
    }

    private void setupStub(int responseCode) {
        server.enqueue(new MockResponse()
            .setResponseCode(responseCode)
            .setHeader("Content-Type", "text/plain")
            .setBody("Hello, world!"));
    }
}
