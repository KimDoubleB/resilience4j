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

import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.lang.reflect.Proxy;
import java.util.Objects;

/**
 * Main class for combining Spring HttpExchange interfaces with Resilience4j.
 *
 * <p>Usage with RestClient (synchronous):
 * <pre>{@code
 * RestClient restClient = RestClient.builder()
 *     .baseUrl("http://localhost:8080")
 *     .build();
 *
 * HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
 *     .withCircuitBreaker(circuitBreaker)
 *     .withRetry(retry)
 *     .withFallback(fallbackInstance)
 *     .build();
 *
 * MyService service = Resilience4jHttpExchange.builder(decorators)
 *     .restClient(restClient)
 *     .build(MyService.class);
 * }</pre>
 *
 * <p>Usage with WebClient (reactive):
 * <pre>{@code
 * WebClient webClient = WebClient.builder()
 *     .baseUrl("http://localhost:8080")
 *     .build();
 *
 * HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
 *     .withCircuitBreaker(circuitBreaker)
 *     .build();
 *
 * MyReactiveService service = Resilience4jHttpExchange.builder(decorators)
 *     .webClient(webClient)
 *     .build(MyReactiveService.class);
 * }</pre>
 *
 * @see HttpExchangeDecorators
 * @see HttpExchangeDecorator
 */
public final class Resilience4jHttpExchange {

    private Resilience4jHttpExchange() {
        // Utility class
    }

    /**
     * Creates a new builder with the specified decorator.
     *
     * @param decorator the decorator to apply to HttpExchange method invocations
     * @return a new Builder instance
     */
    public static Builder builder(HttpExchangeDecorator decorator) {
        return new Builder(decorator);
    }

    /**
     * Builder for creating resilient HttpExchange client proxies.
     */
    public static final class Builder {

        private final HttpExchangeDecorator decorator;
        private RestClient restClient;
        private WebClient webClient;
        private HttpServiceProxyFactory customFactory;

        Builder(HttpExchangeDecorator decorator) {
            this.decorator = Objects.requireNonNull(decorator, "decorator must not be null");
        }

        /**
         * Configure with a RestClient for synchronous HTTP calls.
         *
         * @param restClient the RestClient to use
         * @return this builder
         */
        public Builder restClient(RestClient restClient) {
            this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
            return this;
        }

        /**
         * Configure with a WebClient for reactive HTTP calls.
         *
         * @param webClient the WebClient to use
         * @return this builder
         */
        public Builder webClient(WebClient webClient) {
            this.webClient = Objects.requireNonNull(webClient, "webClient must not be null");
            return this;
        }

        /**
         * Configure with a custom HttpServiceProxyFactory.
         * Use this for advanced configurations.
         *
         * @param factory the custom factory to use
         * @return this builder
         */
        public Builder factory(HttpServiceProxyFactory factory) {
            this.customFactory = Objects.requireNonNull(factory, "factory must not be null");
            return this;
        }

        /**
         * Build a resilient HttpExchange client proxy.
         *
         * @param serviceType the HttpExchange interface class
         * @param <T>         the type of the service interface
         * @return a decorated proxy implementing the service interface
         */
        public <T> T build(Class<T> serviceType) {
            return build(serviceType, serviceType.getSimpleName());
        }

        /**
         * Build a resilient HttpExchange client proxy with a custom name.
         *
         * @param serviceType the HttpExchange interface class
         * @param name        the name for this client (used in metrics/logging)
         * @param <T>         the type of the service interface
         * @return a decorated proxy implementing the service interface
         */
        @SuppressWarnings("unchecked")
        public <T> T build(Class<T> serviceType, String name) {
            Objects.requireNonNull(serviceType, "serviceType must not be null");
            Objects.requireNonNull(name, "name must not be null");

            if (!serviceType.isInterface()) {
                throw new IllegalArgumentException("serviceType must be an interface");
            }

            // Create the underlying Spring proxy
            HttpServiceProxyFactory factory = createFactory();
            T springProxy = factory.createClient(serviceType);

            // Create target metadata
            HttpExchangeTarget<T> target = HttpExchangeTarget.of(serviceType, name);

            // Wrap with our decorator invocation handler
            return (T) Proxy.newProxyInstance(
                serviceType.getClassLoader(),
                new Class<?>[]{serviceType},
                new DecoratorInvocationHandler(target, springProxy, decorator)
            );
        }

        private HttpServiceProxyFactory createFactory() {
            if (customFactory != null) {
                return customFactory;
            }
            if (restClient != null) {
                return HttpServiceProxyFactory
                    .builderFor(RestClientAdapter.create(restClient))
                    .build();
            }
            if (webClient != null) {
                return HttpServiceProxyFactory
                    .builderFor(WebClientAdapter.create(webClient))
                    .build();
            }
            throw new IllegalStateException(
                "Either restClient, webClient, or custom factory must be configured");
        }
    }
}
