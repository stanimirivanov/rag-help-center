package org.raghc.embedding.article

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.raghc.embedding.article.adapter.inbound.kafka.ArticleIntegrationEventListener
import org.raghc.embedding.article.adapter.inbound.kafka.KafkaRetryConfiguration
import org.raghc.embedding.article.application.ArticleIndexingService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import java.time.Duration
import java.util.UUID

private const val ARTICLE_TOPIC = "help-center.article-events.v1"
private const val DLT_TOPIC = "help-center.article-events.v1.DLT"

@SpringJUnitConfig(
    classes = [
        KafkaRetryConfiguration::class,
        ArticleIntegrationEventListener::class,
        KafkaRetryIntegrationTest.TestConfiguration::class,
    ],
)
@ImportAutoConfiguration(KafkaAutoConfiguration::class, JacksonAutoConfiguration::class)
@EmbeddedKafka(
    topics = [ARTICLE_TOPIC, DLT_TOPIC],
    bootstrapServersProperty = "spring.kafka.bootstrap-servers",
)
@TestPropertySource(
    properties = [
        "spring.application.name=embedding-worker-kafka-test",
        "spring.kafka.consumer.auto-offset-reset=earliest",
    ],
)
class KafkaRetryIntegrationTest(
    @Autowired private val kafkaTemplate: KafkaTemplate<String, String>,
    @Autowired private val indexingService: ArticleIndexingService,
    @Autowired private val embeddedKafka: EmbeddedKafkaBroker,
) {
    @Test
    fun `transient failures retry while malformed input goes directly to the DLT`() {
        dltConsumer().use { consumer ->
            kafkaTemplate.send(ARTICLE_TOPIC, "transient", validEvent()).get()
            val transientDlt = receiveDlt(consumer, "transient")
            assertThat(transientDlt.value()).contains("ArticlePublished")
            awaitHandleInvocations(4)

            kafkaTemplate.send(ARTICLE_TOPIC, "malformed", "not-json").get()
            val malformedDlt = receiveDlt(consumer, "malformed")
            assertThat(malformedDlt.value()).isEqualTo("not-json")
            assertThat(handleInvocations()).hasSize(4)
        }
    }

    private fun awaitHandleInvocations(expected: Int) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline && handleInvocations().size < expected) {
            Thread.sleep(50)
        }
        assertThat(handleInvocations()).hasSize(expected)
    }

    private fun handleInvocations() = mockingDetails(indexingService).invocations.filter { it.method.name == "handle" }

    private fun dltConsumer() =
        DefaultKafkaConsumerFactory(
            KafkaTestUtils
                .consumerProps(
                    embeddedKafka.brokersAsString,
                    "dlt-assertion-${UUID.randomUUID()}",
                    "false",
                ).apply {
                    this[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
                },
            StringDeserializer(),
            StringDeserializer(),
        ).createConsumer().also { embeddedKafka.consumeFromAnEmbeddedTopic(it, DLT_TOPIC) }

    private fun receiveDlt(
        consumer: Consumer<String, String>,
        key: String,
    ): ConsumerRecord<String, String> {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(250)).firstOrNull { it.key() == key }?.let {
                return it
            }
        }
        throw AssertionError("No DLT record received for key $key")
    }

    private fun validEvent() =
        """
        {
          "eventId":"${UUID.randomUUID()}",
          "eventType":"ArticlePublished",
          "schemaVersion":1,
          "traceId":"test-trace",
          "tenantId":"${UUID.randomUUID()}",
          "aggregateId":"${UUID.randomUUID()}",
          "streamVersion":1,
          "occurredAt":"2026-08-20T12:00:00Z",
          "data":{"revision":1,"title":"Reset password","body":"Use settings.","locale":"en",
                  "collectionId":"${UUID.randomUUID()}"}
        }
        """.trimIndent()

    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    class TestConfiguration {
        @Bean
        fun indexingService(): ArticleIndexingService =
            mock(ArticleIndexingService::class.java) { invocation ->
                if (invocation.method.name == "handle") {
                    error("model unavailable")
                }
                org.mockito.Answers.RETURNS_DEFAULTS
                    .answer(invocation)
            }
    }
}
