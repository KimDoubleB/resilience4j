# Resilience4j HTTP Exchange

이 모듈은 [Resilience4j](https://resilience4j.readme.io/)와 Spring의 [HTTP Interface](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html#rest-http-interface) (`@HttpExchange`)를 통합합니다.

## 개요

Spring 6+에서는 `@HttpExchange` 어노테이션을 사용하여 HTTP 서비스를 Java 인터페이스로 정의할 수 있는 HTTP Interface 클라이언트를 도입했습니다. 이 모듈은 Java Dynamic Proxy를 사용하여 해당 인터페이스를 Resilience4j 패턴(CircuitBreaker, Retry, RateLimiter, Bulkhead, TimeLimiter)으로 래핑합니다.

## 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                         사용자 코드                                   │
│   service.getUser(1L)                                                │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│              Java Dynamic Proxy (Resilience4j 래퍼)                  │
│                    DecoratorInvocationHandler                        │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │              Decorator 체인 (순서대로 적용)                    │    │
│  │   Retry → CircuitBreaker → RateLimiter → Bulkhead → Fallback │    │
│  └─────────────────────────────────────────────────────────────┘    │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│              Spring HttpServiceProxyFactory Proxy                    │
│                  (실제 HTTP 요청 처리)                                │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    RestClient / WebClient                            │
│                      (HTTP 통신 수행)                                 │
└─────────────────────────────────────────────────────────────────────┘
```

## 사용법

### 1. HttpExchange 인터페이스 정의

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

### 2. Spring Bean으로 설정

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
        // 1. Decorator 체인 구성
        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withRetry(retry)
            .withCircuitBreaker(circuitBreaker)
            .withFallback(new UserServiceFallback())
            .build();

        // 2. Resilience4j 래핑된 프록시 생성
        return Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(UserService.class);
    }
}
```

### 3. WebClient 사용 (Reactive)

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

## 동작 원리

### Phase 1: 프록시 생성 (Bean 초기화 시점)

`Resilience4jHttpExchange.builder(...).build(UserService.class)` 호출 시:

1. **Spring 프록시 생성**: `HttpServiceProxyFactory`가 HTTP 통신을 처리하는 프록시 생성
2. **Decorator 사전 적용**: `DecoratorInvocationHandler`가 모든 인터페이스 메서드에 resilience 패턴을 미리 적용
3. **최종 프록시 래핑**: Java Dynamic Proxy가 Spring 프록시를 `DecoratorInvocationHandler`로 래핑

```java
// Resilience4jHttpExchange.java:150-171
public <T> T build(Class<T> serviceType, String name) {
    // Spring의 HttpServiceProxyFactory 생성
    HttpServiceProxyFactory factory = createFactory();

    // Spring이 UserService를 구현하는 프록시 생성
    T springProxy = factory.createClient(serviceType);

    // 타겟 메타데이터 생성
    HttpExchangeTarget<T> target = HttpExchangeTarget.of(serviceType, name);

    // Resilience4j 프록시로 래핑 (Java Dynamic Proxy)
    return (T) Proxy.newProxyInstance(
        serviceType.getClassLoader(),
        new Class<?>[]{serviceType},
        new DecoratorInvocationHandler(target, springProxy, decorator)
    );
}
```

### Phase 2: 메서드 호출 (`service.getUser(1L)`)

**Step 1**: `DecoratorInvocationHandler.invoke()`가 호출을 가로챔

```java
// DecoratorInvocationHandler.java:94-127
public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    // equals/hashCode/toString은 별도 처리
    switch (method.getName()) {
        case "equals": return equals(args[0]);
        case "hashCode": return hashCode();
        case "toString": return toString();
    }

    // default 메서드는 직접 실행
    if (method.isDefault()) {
        return InvocationHandler.invokeDefault(proxy, method, args);
    }

    // 미리 데코레이팅된 함수 실행
    CheckedFunction<Object[], Object> decorated = decoratedDispatch.get(method);
    return decorated.apply(args);  // ← 여기서 Decorator 체인 실행
}
```

**Step 2**: Decorator 체인 실행 (양파 구조)

```
사용자 호출: service.getUser(1L)
     │
     ▼
┌────────────────────────────────────────┐
│  Retry (가장 바깥쪽 - 먼저 선언됨)        │
│  - 실패 시 재시도 로직 실행               │
│  ┌────────────────────────────────────┐│
│  │  CircuitBreaker                    ││
│  │  - 서킷 상태 확인                    ││
│  │  - OPEN이면 차단                    ││
│  │  ┌────────────────────────────────┐││
│  │  │  Fallback (가장 안쪽)          │││
│  │  │  - 에러 발생 시 대체 값 반환     │││
│  │  │  ┌────────────────────────────┐│││
│  │  │  │  Spring Proxy 호출         ││││
│  │  │  │  → RestClient HTTP 요청    ││││
│  │  │  └────────────────────────────┘│││
│  │  └────────────────────────────────┘││
│  └────────────────────────────────────┘│
└────────────────────────────────────────┘
```

## Decorator 순서의 중요성

Decorator 선언 순서가 실행 순서를 결정합니다:

```java
HttpExchangeDecorators.builder()
    .withRetry(retry)                    // 1번째 적용 (가장 바깥쪽)
    .withCircuitBreaker(circuitBreaker)  // 2번째 적용
    .withFallback(fallback)              // 3번째 적용 (가장 안쪽)
    .build();
```

### 시나리오 1: Fallback이 CircuitBreaker 뒤에 있는 경우 (권장)

```java
.withCircuitBreaker(circuitBreaker)
.withFallback(fallback)
```

- HTTP 요청 실패 → Fallback이 대체 값 반환
- CircuitBreaker가 OPEN → Fallback이 대체 값 반환

### 시나리오 2: Fallback이 CircuitBreaker 앞에 있는 경우

```java
.withFallback(fallback)
.withCircuitBreaker(circuitBreaker)
```

- HTTP 요청 실패 → Fallback이 대체 값 반환
- CircuitBreaker가 OPEN → `CallNotPermittedException`이 전파됨 (Fallback 호출 안 됨!)

## 사용 가능한 Decorator

| Decorator | 메서드 | 설명 |
|-----------|--------|------|
| Retry | `withRetry(Retry)` | 실패한 호출 재시도 |
| CircuitBreaker | `withCircuitBreaker(CircuitBreaker)` | 실패율이 높을 때 호출 차단 |
| RateLimiter | `withRateLimiter(RateLimiter)` | 호출 빈도 제한 |
| Bulkhead | `withBulkhead(Bulkhead)` | 동시 호출 수 제한 |
| TimeLimiter | `withTimeLimiter(TimeLimiter, executor)` | 호출에 타임아웃 추가 |
| Fallback | `withFallback(fallback)` | 실패 시 대체 값 반환 |
| FallbackFactory | `withFallbackFactory(Function<Exception, ?>)` | 예외 정보로 Fallback 생성 |

### 예외 필터를 사용한 Fallback

```java
// 특정 예외 타입에만 Fallback 트리거
.withFallback(fallback, HttpServerErrorException.class)

// 또는 커스텀 predicate 사용
.withFallback(fallback, ex -> ex instanceof TimeoutException)
```

## 전체 예제

```java
@Configuration
public class ResilientUserServiceConfig {

    @Bean
    public UserService userService(RestClient restClient) {
        // Resilience 패턴 설정
        CircuitBreaker cb = CircuitBreaker.of("user-service", CircuitBreakerConfig.custom()
            .slidingWindowSize(10)                           // 슬라이딩 윈도우 크기
            .failureRateThreshold(50)                        // 실패율 임계값 50%
            .waitDurationInOpenState(Duration.ofSeconds(30)) // OPEN 상태 대기 시간
            .permittedNumberOfCallsInHalfOpenState(3)        // HALF_OPEN에서 허용 호출 수
            .build());

        Retry retry = Retry.of("user-service", RetryConfig.custom()
            .maxAttempts(3)                                  // 최대 3번 시도
            .waitDuration(Duration.ofMillis(500))            // 재시도 간격
            .retryExceptions(IOException.class, TimeoutException.class)
            .build());

        RateLimiter rl = RateLimiter.of("user-service", RateLimiterConfig.custom()
            .limitRefreshPeriod(Duration.ofSeconds(1))       // 1초마다 리프레시
            .limitForPeriod(100)                             // 주기당 100번 허용
            .timeoutDuration(Duration.ofMillis(500))         // 대기 타임아웃
            .build());

        // Decorator 체인 구성
        HttpExchangeDecorators decorators = HttpExchangeDecorators.builder()
            .withRetry(retry)
            .withCircuitBreaker(cb)
            .withRateLimiter(rl)
            .withFallbackFactory(ex -> new UserServiceFallback(ex))
            .build();

        // Resilient 클라이언트 생성
        return Resilience4jHttpExchange.builder(decorators)
            .restClient(restClient)
            .build(UserService.class, "user-service");
    }
}

// Fallback 구현
public class UserServiceFallback implements UserService {
    private final Exception cause;

    public UserServiceFallback(Exception cause) {
        this.cause = cause;
    }

    @Override
    public User getUser(Long id) {
        log.warn("getUser({}) Fallback 호출: {}", id, cause.getMessage());
        return User.unknown(id);
    }

    @Override
    public User createUser(User user) {
        throw new ServiceUnavailableException("사용자 생성 불가", cause);
    }

    @Override
    public List<User> getAllUsers() {
        return Collections.emptyList();
    }
}
```

## 실제 호출 흐름 예시

```java
// 1. 사용자가 호출
User user = userService.getUser(1L);

// 2. DecoratorInvocationHandler.invoke() 진입
//    → decoratedDispatch.get(getUser 메서드).apply(new Object[]{1L})

// 3. Retry.decorateCheckedFunction 실행
//    내부적으로 최대 3번 재시도 로직 수행

// 4. CircuitBreaker.decorateCheckedFunction 실행
//    - CLOSED 상태: 통과
//    - OPEN 상태: CallNotPermittedException 발생

// 5. Fallback 체크 (예외 발생 시)
//    - 예외 발생하면 fallback.getUser(1L) 호출
//    - 성공하면 그대로 통과

// 6. Spring HttpServiceProxyFactory Proxy 호출
//    - method.invoke(springProxy, args)
//    - @GetExchange("/users/{id}") 메타정보로 HTTP 요청 생성

// 7. RestClient가 실제 HTTP GET 요청 수행
//    GET http://localhost:8080/api/users/1

// 8. 응답 → User 객체로 변환 → 반환
```

## 모듈 구조

| 파일 | 설명 |
|------|------|
| `Resilience4jHttpExchange.java` | 진입점, Builder 패턴으로 프록시 생성 |
| `HttpExchangeDecorators.java` | Decorator 체인 빌더 (Retry, CB, RateLimiter 등) |
| `HttpExchangeDecorator.java` | Decoration 계약을 위한 함수형 인터페이스 |
| `DecoratorInvocationHandler.java` | Java Dynamic Proxy의 InvocationHandler |
| `HttpExchangeTarget.java` | 타겟 인터페이스 메타데이터 컨테이너 |
| `FallbackDecorator.java` | 예외 필터링이 있는 Fallback |
| `FallbackHandler.java` | Fallback 호출 계약 |
| `DefaultFallbackHandler.java` | 정적 Fallback 인스턴스 핸들러 |
| `FallbackFactory.java` | 동적 Fallback 팩토리 핸들러 |

## 요구사항

- Java 17+
- Spring Framework 6+
- Resilience4j 2.x

## 의존성

```gradle
dependencies {
    implementation 'io.github.resilience4j:resilience4j-spring6-http-exchange:${version}'

    // 필수 Spring 의존성
    implementation 'org.springframework:spring-web:6.x'

    // WebClient 지원용 (선택사항)
    implementation 'org.springframework:spring-webflux:6.x'
}
```
