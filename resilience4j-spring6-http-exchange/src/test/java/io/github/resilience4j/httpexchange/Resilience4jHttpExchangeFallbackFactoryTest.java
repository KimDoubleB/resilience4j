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

import io.github.resilience4j.httpexchange.test.TestServiceFallbackThrowingException;
import io.github.resilience4j.httpexchange.test.TestServiceFallbackWithException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.*;

/**
 * Unit tests on fallback factories.
 */
class Resilience4jHttpExchangeFallbackFactoryTest {

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

    private TestService buildTestService(Function<Exception, ?> fallbackSupplier) {
        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withFallbackFactory(fallbackSupplier)
            .build();
        return Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);
    }

    @Test
    void should_successfully_get_a_response() {
        setupStub(200);
        TestService testService = buildTestService(e -> mock(TestService.class));

        String result = testService.greeting();

        assertThat(result).isEqualTo("Hello, world!");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void should_lazily_fail_on_invalid_fallback() {
        setupStub(500);
        TestService testService = buildTestService(e -> "my fallback");

        Throwable throwable = catchThrowable(testService::greeting);

        assertThat(throwable).isNotNull()
            .hasMessageContaining(
                "Cannot use the fallback [class java.lang.String] for [interface io.github.resilience4j.httpexchange.TestService]");
    }

    @Test
    void should_go_to_fallback_and_consume_exception() {
        setupStub(500);
        TestService testService = buildTestService(TestServiceFallbackWithException::new);

        String result = testService.greeting();

        assertThat(result)
            .startsWith("Message from exception:");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void should_go_to_fallback_and_rethrow_an_exception_thrown_in_fallback() {
        setupStub(500);
        TestService testService = buildTestService(e -> new TestServiceFallbackThrowingException());

        Throwable result = catchThrowable(testService::greeting);

        assertThat(result).isNotNull()
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Exception in greeting fallback");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void should_go_to_fallback_and_consume_exception_with_exception_filter() {
        setupStub(500);
        TestService uselessFallback = spy(TestService.class);
        when(uselessFallback.greeting()).thenReturn("I should not be called");

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withFallbackFactory(TestServiceFallbackWithException::new, HttpServerErrorException.class)
            .withFallbackFactory(e -> uselessFallback)
            .build();
        TestService testService = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        String result = testService.greeting();

        assertThat(result)
            .startsWith("Message from exception:");
        verify(uselessFallback, times(0)).greeting();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void should_go_to_second_fallback_and_consume_exception_with_exception_filter() {
        setupStub(500);
        TestService uselessFallback = spy(TestService.class);
        when(uselessFallback.greeting()).thenReturn("I should not be called");

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withFallbackFactory(e -> uselessFallback, MyException.class)
            .withFallbackFactory(TestServiceFallbackWithException::new)
            .build();
        TestService testService = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        String result = testService.greeting();

        assertThat(result)
            .startsWith("Message from exception:");
        verify(uselessFallback, times(0)).greeting();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void should_go_to_fallback_and_consume_exception_with_predicate() {
        setupStub(500);
        TestService uselessFallback = spy(TestService.class);
        when(uselessFallback.greeting()).thenReturn("I should not be called");

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withFallbackFactory(TestServiceFallbackWithException::new,
                HttpServerErrorException.class::isInstance)
            .withFallbackFactory(e -> uselessFallback)
            .build();
        TestService testService = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        String result = testService.greeting();

        assertThat(result)
            .startsWith("Message from exception:");
        verify(uselessFallback, times(0)).greeting();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void should_go_to_second_fallback_and_consume_exception_with_predicate() {
        setupStub(500);
        TestService uselessFallback = spy(TestService.class);
        when(uselessFallback.greeting()).thenReturn("I should not be called");

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withFallbackFactory(e -> uselessFallback, MyException.class::isInstance)
            .withFallbackFactory(TestServiceFallbackWithException::new)
            .build();
        TestService testService = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        String result = testService.greeting();

        assertThat(result)
            .startsWith("Message from exception:");
        verify(uselessFallback, times(0)).greeting();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void should_pass_exception_to_factory() {
        setupStub(500);

        TestService testService = buildTestService(e -> {
            assertThat(e).isInstanceOf(HttpServerErrorException.class);
            return new TestServiceFallbackWithException(e);
        });

        String result = testService.greeting();

        assertThat(result).startsWith("Message from exception:");
    }

    private void setupStub(int responseCode) {
        server.enqueue(new MockResponse()
            .setResponseCode(responseCode)
            .setHeader("Content-Type", "text/plain")
            .setBody("Hello, world!"));
    }

    private static class MyException extends Exception {
    }
}
