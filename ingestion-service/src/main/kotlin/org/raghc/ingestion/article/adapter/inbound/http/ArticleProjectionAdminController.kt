package org.raghc.ingestion.article.adapter.inbound.http

import org.raghc.ingestion.article.application.ArticleProjectionWriter
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/v1/projections/articles")
class ArticleProjectionAdminController(
    private val projectionWriter: ArticleProjectionWriter,
) {
    @PostMapping("/replay")
    @Transactional
    fun replay(): ResponseEntity<Void> {
        projectionWriter.rebuild()
        return ResponseEntity.noContent().build()
    }
}
