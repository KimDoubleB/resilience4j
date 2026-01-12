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

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Tests the integration of the {@link Resilience4jHttpExchange} with {@link Bulkhead}
 */
class Resilience4jHttpExchangeBulkheadTest {

    private MockWebServer server;
    private RestClient restClient;
    private TestService testService;
    private Bulkhead bulkhead;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        restClient = RestClient.builder()
            .baseUrl(server.url("/").toString())
            .build();

        bulkhead = spy(Bulkhead.of("bulkheadTest", BulkheadConfig.ofDefaults()));
        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withBulkhead(bulkhead)
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

        testService.greeting();

        assertThat(server.getRequestCount()).isEqualTo(1);
        verify(bulkhead).acquirePermission();
    }

    @Test
    void testSuccessfulCallWithDefaultMethod() {
        setupStub(200);

        testService.defaultGreeting();

        assertThat(server.getRequestCount()).isEqualTo(1);
        verify(bulkhead).acquirePermission();
    }

    @Test
    void testBulkheadFull() {
        setupStub(200);

        when(bulkhead.tryAcquirePermission()).thenReturn(false);

        assertThatThrownBy(() -> testService.greeting())
            .isInstanceOf(BulkheadFullException.class);

        assertThat(server.getRequestCount()).isEqualTo(0);
    }

    @Test
    void testFailedCall() {
        setupStub(500);

        assertThatThrownBy(() -> testService.greeting())
            .isInstanceOf(HttpServerErrorException.class);

        verify(bulkhead).acquirePermission();
    }

    @Test
    void testBulkheadReleasesPermissionAfterSuccess() {
        setupStub(200);

        Bulkhead realBulkhead = Bulkhead.of("test", BulkheadConfig.custom()
            .maxConcurrentCalls(1)
            .build());

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withBulkhead(realBulkhead)
            .build();
        TestService service = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        service.greeting();

        Bulkhead.Metrics metrics = realBulkhead.getMetrics();
        assertThat(metrics.getAvailableConcurrentCalls()).isEqualTo(1);
    }

    @Test
    void testBulkheadReleasesPermissionAfterFailure() {
        setupStub(500);

        Bulkhead realBulkhead = Bulkhead.of("test", BulkheadConfig.custom()
            .maxConcurrentCalls(1)
            .build());

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withBulkhead(realBulkhead)
            .build();
        TestService service = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        try {
            service.greeting();
        } catch (Exception ignored) {
        }

        Bulkhead.Metrics metrics = realBulkhead.getMetrics();
        assertThat(metrics.getAvailableConcurrentCalls()).isEqualTo(1);
    }

    @Test
    void testBulkheadConcurrentCalls() throws InterruptedException {
        Bulkhead realBulkhead = Bulkhead.of("test", BulkheadConfig.custom()
            .maxConcurrentCalls(2)
            .maxWaitDuration(Duration.ofMillis(100))
            .build());

        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withBulkhead(realBulkhead)
            .build();
        TestService service = Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(TestService.class);

        // Enqueue slow responses
        for (int i = 0; i < 5; i++) {
            server.enqueue(new MockResponse()
                .setBody("Response")
                .setHeader("Content-Type", "text/plain")
                .setBodyDelay(500, TimeUnit.MILLISECONDS));
        }

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch latch = new CountDownLatch(3);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < 3; i++) {
            executor.submit(() -> {
                try {
                    service.greeting();
                    successCount.incrementAndGet();
                } catch (BulkheadFullException e) {
                    rejectedCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // At least one call should be rejected due to bulkhead limit
        assertThat(rejectedCount.get()).isGreaterThanOrEqualTo(1);
    }

    private void setupStub(int responseCode) {
        server.enqueue(new MockResponse()
            .setResponseCode(responseCode)
            .setHeader("Content-Type", "text/plain")
            .setBody("hello world"));
    }
}
