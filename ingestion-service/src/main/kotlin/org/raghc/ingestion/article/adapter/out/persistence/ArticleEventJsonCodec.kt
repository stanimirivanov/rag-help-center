package org.raghc.ingestion.article.adapter.out.persistence

import org.raghc.ingestion.article.domain.ArticleContentRevised
import org.raghc.ingestion.article.domain.ArticleCreated
import org.raghc.ingestion.article.domain.ArticleEvent
import org.raghc.ingestion.article.domain.ArticlePublished
import org.raghc.ingestion.article.domain.ArticleRestored
import org.raghc.ingestion.article.domain.ArticleWithdrawn
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class ArticleEventJsonCodec(
    private val objectMapper: ObjectMapper,
) {
    fun encode(event: ArticleEvent): EncodedArticleEvent =
        when (event) {
            is ArticleCreated -> {
                EncodedArticleEvent("ArticleCreated", 2, objectMapper.writeValueAsString(event))
            }

            is ArticleContentRevised -> {
                EncodedArticleEvent("ArticleContentRevised", 1, objectMapper.writeValueAsString(event))
            }

            is ArticlePublished -> {
                EncodedArticleEvent("ArticlePublished", 1, objectMapper.writeValueAsString(event))
            }

            is ArticleWithdrawn -> {
                EncodedArticleEvent("ArticleWithdrawn", 1, objectMapper.writeValueAsString(event))
            }

            is ArticleRestored -> {
                EncodedArticleEvent("ArticleRestored", 1, objectMapper.writeValueAsString(event))
            }
        }

    fun decode(
        eventType: String,
        schemaVersion: Int,
        payload: String,
    ): ArticleEvent {
        val supported = schemaVersion == 1 || (eventType == "ArticleCreated" && schemaVersion == 2)
        require(supported) { "unsupported $eventType schema version $schemaVersion" }
        return when (eventType) {
            "ArticleCreated" -> objectMapper.readValue(payload, ArticleCreated::class.java)
            "ArticleContentRevised" -> objectMapper.readValue(payload, ArticleContentRevised::class.java)
            "ArticlePublished" -> objectMapper.readValue(payload, ArticlePublished::class.java)
            "ArticleWithdrawn" -> objectMapper.readValue(payload, ArticleWithdrawn::class.java)
            "ArticleRestored" -> objectMapper.readValue(payload, ArticleRestored::class.java)
            else -> error("unsupported article event type $eventType")
        }
    }
}

data class EncodedArticleEvent(
    val eventType: String,
    val schemaVersion: Int,
    val payload: String,
)
