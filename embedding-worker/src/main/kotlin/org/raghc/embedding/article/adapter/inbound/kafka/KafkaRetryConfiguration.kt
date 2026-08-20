package org.raghc.embedding.article.adapter.inbound.kafka

import org.apache.kafka.common.TopicPartition
import org.raghc.embedding.article.application.UnsupportedArticleEventException
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.ExponentialBackOff
import tools.jackson.core.JacksonException

@Configuration
class KafkaRetryConfiguration {
    @Bean
    fun articleErrorHandler(
        kafkaTemplate: KafkaTemplate<String, String>,
        @Value("\${app.kafka.article-events-dlt:help-center.article-events.v1.DLT}") dltTopic: String,
    ): CommonErrorHandler {
        val recoverer =
            DeadLetterPublishingRecoverer(kafkaTemplate) { record, _ ->
                TopicPartition(dltTopic, record.partition())
            }
        val backOff =
            ExponentialBackOff(INITIAL_INTERVAL_MILLIS, MULTIPLIER).apply {
                maxInterval = MAX_INTERVAL_MILLIS
                maxAttempts = MAX_ATTEMPTS
            }
        return DefaultErrorHandler(recoverer, backOff).apply {
            addNotRetryableExceptions(
                JacksonException::class.java,
                UnsupportedArticleEventException::class.java,
                IllegalArgumentException::class.java,
            )
        }
    }

    private companion object {
        const val INITIAL_INTERVAL_MILLIS = 250L
        const val MAX_INTERVAL_MILLIS = 2_000L
        const val MULTIPLIER = 2.0
        const val MAX_ATTEMPTS = 3L
    }
}
