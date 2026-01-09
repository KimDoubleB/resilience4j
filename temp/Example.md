```kotlin
@HttpExchange("/api/v1")
interface HomeClient {
    @GetExchange("/home")
    fun getHome(
        @RequestHeader("Accept-Language") language: String? = null,
        @RequestHeader("D-Comics-Client-Platform") platform: String? = null,
        @RequestHeader("D-Comics-Device-Id") deviceId: String? = null,
    ): HomeResponse
}

@Configuration
@ConditionalOnProperty(
    prefix = "infra.client.home",
    name = ["enabled"],
    havingValue = "true",
)
@EnableConfigurationProperties(HomeClientProperties::class)
@ImportHttpServices(
    group = HomeClientConfig.CLIENT_NAME,
    basePackages = ["com.disney.comics.infra.client.external.home"],
)
class HomeClientConfig
```
