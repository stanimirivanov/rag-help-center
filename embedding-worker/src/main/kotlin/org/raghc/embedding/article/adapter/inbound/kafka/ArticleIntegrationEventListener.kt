package org.raghc.embedding.article.adapter.inbound.kafka

import org.raghc.embedding.article.application.ArticleIndexingService
import org.raghc.embedding.article.application.ArticleIntegrationEvent
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class ArticleIntegrationEventListener(
    private val objectMapper: ObjectMapper,
    private val indexingService: ArticleIndexingService,
) {
    @KafkaListener(
        topics = ["\${app.kafka.article-events-topic:help-center.article-events.v1}"],
        groupId = "\${spring.application.name}",
        autoStartup = "\${app.kafka.listener.enabled:true}",
    )
    fun onMessage(payload: String) {
        indexingService.handle(objectMapper.readValue(payload, ArticleIntegrationEvent::class.java))
    }
}
