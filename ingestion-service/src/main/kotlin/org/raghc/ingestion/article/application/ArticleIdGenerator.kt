package org.raghc.ingestion.article.application

import org.raghc.ingestion.article.domain.ArticleId

fun interface ArticleIdGenerator {
    fun next(): ArticleId
}
