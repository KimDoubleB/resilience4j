# Resilience4j HTTP Exchange

This module provides integration between [Resilience4j](https://resilience4j.readme.io/) and Spring's [HTTP Interface](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html#rest-http-interface) (`@HttpExchange`).

## Overview

Spring 6+ introduced HTTP Interface clients that allow you to define HTTP services as Java interfaces with `@HttpExchange` annotations. This module wraps those interfaces with Resilience4j patterns (CircuitBreaker, Retry, RateLimiter, Bulkhead, TimeLimiter) using Java Dynamic Proxy.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         User Code                                    │
│   service.getUser(1L)                                                │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│              Java Dynamic Proxy (Resilience4j Wrapper)               │
│                    DecoratorInvocationHandler                        │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │              Decorator Chain (applied in order)              │    │
│  │   Retry → CircuitBreaker → RateLimiter → Bulkhead → Fallback │    │
│  └─────────────────────────────────────────────────────────────┘    │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│              Spring HttpServiceProxyFactory Proxy                    │
│                  (Handles actual HTTP requests)                      │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    RestClient / WebClient                            │
│                      (HTTP communication)                            │
└─────────────────────────────────────────────────────────────────────┘
```

## Usage

### 1. Define an HttpExchange Interface

```java
@HttpExchange("/api")
public interface UserService {

    @GetExchange("/users/{id}")
    User getUser(@PathVariable Long id);

    @PostExchange("/users")
    User createUser(@RequestBody User user);

    @GetExchange("/users")
    List<User> getAllUsers();
}
```

### 2. Configure as Spring Bean

```java
@Configuration
public class HttpExchangeConfig {

    @Bean
    public RestClient restClient() {
        return RestClient.builder()
            .baseUrl("http://localhost:8080")
            .build();
    }

    @Bean
    public CircuitBreaker circuitBreaker() {
        return CircuitBreaker.of("userService", CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .build());
    }

    @Bean
    public Retry retry() {
        return Retry.of("userService", RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(500))
            .build());
    }

    @Bean
    public UserService userService(RestClient restClient,
                                   CircuitBreaker circuitBreaker,
                                   Retry retry) {
        // 1. Build decorator chain
        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withRetry(retry)
            .withCircuitBreaker(circuitBreaker)
            .withFallback(new UserServiceFallback())
            .build();

        // 2. Create resilient proxy
        return Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(UserService.class);
    }
}
```

### 3. Use with WebClient (Reactive)

```java
@Bean
public UserService reactiveUserService(WebClient webClient,
                                        CircuitBreaker circuitBreaker) {
    HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
        .withCircuitBreaker(circuitBreaker)
        .build();

    return Resilience4jHttpExchange.builder(decorators)
        .webClient(webClient)
        .build(UserService.class);
}
```

## How It Works

### Phase 1: Proxy Creation (Bean Initialization)

When `Resilience4jHttpExchange.builder(...).build(UserService.class)` is called:

1. **Spring Proxy Creation**: `HttpServiceProxyFactory` creates a proxy that handles HTTP communication
2. **Decorator Pre-application**: `DecoratorInvocationHandler` pre-decorates all interface methods with the configured resilience patterns
3. **Final Proxy Wrapping**: Java Dynamic Proxy wraps the Spring proxy with the `DecoratorInvocationHandler`

```java
// Resilience4jHttpExchange.java:150-171
public <T> T build(Class<T> serviceType, String name) {
    // Create Spring's HttpServiceProxyFactory
    HttpServiceProxyFactory factory = createFactory();

    // Spring creates proxy implementing UserService
    T springProxy = factory.createClient(serviceType);

    // Create target metadata
    HttpExchangeTarget<T> target = HttpExchangeTarget.of(serviceType, name);

    // Wrap with Resilience4j proxy (Java Dynamic Proxy)
    return (T) Proxy.newProxyInstance(
        serviceType.getClassLoader(),
        new Class<?>[]{serviceType},
        new DecoratorInvocationHandler(target, springProxy, decorator)
    );
}
```

### Phase 2: Method Invocation (`service.getUser(1L)`)

**Step 1**: `DecoratorInvocationHandler.invoke()` intercepts the call

```java
// DecoratorInvocationHandler.java:94-127
public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    // Handle equals/hashCode/toString separately
    switch (method.getName()) {
        case "equals": return equals(args[0]);
        case "hashCode": return hashCode();
        case "toString": return toString();
    }

    // Default methods are invoked directly
    if (method.isDefault()) {
        return InvocationHandler.invokeDefault(proxy, method, args);
    }

    // Execute pre-decorated function
    CheckedFunction<Object[], Object> decorated = decoratedDispatch.get(method);
    return decorated.apply(args);  // ← Decorator chain executes here
}
```

**Step 2**: Decorator chain execution (Onion structure)

```
User call: service.getUser(1L)
     │
     ▼
┌────────────────────────────────────────┐
│  Retry (outermost - declared first)    │
│  - Executes retry logic on failure     │
│  ┌────────────────────────────────────┐│
│  │  CircuitBreaker                    ││
│  │  - Checks circuit state            ││
│  │  - Blocks if OPEN                  ││
│  │  ┌────────────────────────────────┐││
│  │  │  Fallback (innermost)          │││
│  │  │  - Returns fallback on error   │││
│  │  │  ┌────────────────────────────┐│││
│  │  │  │  Spring Proxy call         ││││
│  │  │  │  → RestClient HTTP request ││││
│  │  │  └────────────────────────────┘│││
│  │  └────────────────────────────────┘││
│  └────────────────────────────────────┘│
└────────────────────────────────────────┘
```

## Decorator Order Matters

The order in which decorators are declared determines the execution order:

```java
HttpExchangeDecorators.builder()
    .withRetry(retry)                    // Applied 1st (outermost)
    .withCircuitBreaker(circuitBreaker)  // Applied 2nd
    .withFallback(fallback)              // Applied 3rd (innermost)
    .build();
```

### Scenario 1: Fallback AFTER CircuitBreaker (Recommended)

```java
.withCircuitBreaker(circuitBreaker)
.withFallback(fallback)
```

- HTTP request fails → Fallback returns alternative value
- CircuitBreaker is OPEN → Fallback returns alternative value

### Scenario 2: Fallback BEFORE CircuitBreaker

```java
.withFallback(fallback)
.withCircuitBreaker(circuitBreaker)
```

- HTTP request fails → Fallback returns alternative value
- CircuitBreaker is OPEN → `CallNotPermittedException` propagates (Fallback NOT called!)

## Available Decorators

| Decorator | Method | Description |
|-----------|--------|-------------|
| Retry | `withRetry(Retry)` | Retries failed calls |
| CircuitBreaker | `withCircuitBreaker(CircuitBreaker)` | Prevents calls when failure rate is high |
| RateLimiter | `withRateLimiter(RateLimiter)` | Limits call frequency |
| Bulkhead | `withBulkhead(Bulkhead)` | Limits concurrent calls |
| TimeLimiter | `withTimeLimiter(TimeLimiter, executor)` | Adds timeout to calls |
| Fallback | `withFallback(fallback)` | Returns fallback value on failure |
| FallbackFactory | `withFallbackFactory(Function<Exception, ?>)` | Creates fallback with exception info |

### Fallback with Exception Filter

```java
// Only trigger fallback for specific exception types
.withFallback(fallback, HttpServerErrorException.class)

// Or use a custom predicate
.withFallback(fallback, ex -> ex instanceof TimeoutException)
```

## Complete Example

```java
@Configuration
public class ResilientUserServiceConfig {

    @Bean
    public UserService userService(RestClient restClient) {
        // Configure resilience patterns
        CircuitBreaker cb = CircuitBreaker.of("user-service", CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build());

        Retry retry = Retry.of("user-service", RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(500))
            .retryExceptions(IOException.class, TimeoutException.class)
            .build());

        RateLimiter rl = RateLimiter.of("user-service", RateLimiterConfig.custom()
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .limitForPeriod(100)
            .timeoutDuration(Duration.ofMillis(500))
            .build());

        // Build decorator chain
        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withRetry(retry)
            .withCircuitBreaker(cb)
            .withRateLimiter(rl)
            .withFallbackFactory(ex -> new UserServiceFallback(ex))
            .build();

        // Create resilient client
        return Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(UserService.class, "user-service");
    }
}

// Fallback implementation
public class UserServiceFallback implements UserService {
    private final Exception cause;

    public UserServiceFallback(Exception cause) {
        this.cause = cause;
    }

    @Override
    public User getUser(Long id) {
        log.warn("Fallback for getUser({}): {}", id, cause.getMessage());
        return User.unknown(id);
    }

    @Override
    public User createUser(User user) {
        throw new ServiceUnavailableException("Cannot create user", cause);
    }

    @Override
    public List<User> getAllUsers() {
        return Collections.emptyList();
    }
}
```

## Module Structure

| File | Description |
|------|-------------|
| `Resilience4jHttpExchange.java` | Entry point, Builder pattern for proxy creation |
| `HttpExchangeDecorators.java` | Decorator chain builder (Retry, CB, RateLimiter, etc.) |
| `HttpExchangeDecorator.java` | Functional interface for decoration contract |
| `DecoratorInvocationHandler.java` | Java Dynamic Proxy InvocationHandler |
| `HttpExchangeTarget.java` | Target interface metadata container |
| `FallbackDecorator.java` | Fallback with exception filtering |
| `FallbackHandler.java` | Fallback invocation contract |
| `DefaultFallbackHandler.java` | Static fallback instance handler |
| `FallbackFactory.java` | Dynamic fallback factory handler |

## Requirements

- Java 17+
- Spring Framework 6+
- Resilience4j 2.x

## Dependencies

```gradle
dependencies {
    implementation 'io.github.resilience4j:resilience4j-spring6-http-exchange:${version}'

    // Required Spring dependencies
    implementation 'org.springframework:spring-web:6.x'

    // For WebClient support (optional)
    implementation 'org.springframework:spring-webflux:6.x'
}
```
