package org.raghc.qa.retrieval.adapter.outbound.http

import org.raghc.qa.retrieval.application.RetrieveKnowledge
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.support.RestClientAdapter
import org.springframework.web.service.invoker.HttpServiceProxyFactory
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

private const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 2L
private const val DEFAULT_READ_TIMEOUT_SECONDS = 5L
private val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_SECONDS)
private val DEFAULT_READ_TIMEOUT: Duration = Duration.ofSeconds(DEFAULT_READ_TIMEOUT_SECONDS)

@ConfigurationProperties("app.retrieval")
data class RetrievalClientProperties(
    val baseUrl: URI,
    val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    val readTimeout: Duration = DEFAULT_READ_TIMEOUT,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetrievalClientProperties::class)
class RetrievalClientConfiguration {
    @Bean
    internal fun retrievalHttpService(properties: RetrievalClientProperties): RetrievalHttpService {
        val httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient)
        requestFactory.setReadTimeout(properties.readTimeout)
        val restClient =
            RestClient
                .builder()
                .baseUrl(properties.baseUrl.toString())
                .requestFactory(requestFactory)
                .build()

        return HttpServiceProxyFactory
            .builderFor(RestClientAdapter.create(restClient))
            .build()
            .createClient(RetrievalHttpService::class.java)
    }

    @Bean
    internal fun retrievalPort(client: RetrievalHttpService): RetrieveKnowledge = HttpRetrieveKnowledgeAdapter(client)
}
