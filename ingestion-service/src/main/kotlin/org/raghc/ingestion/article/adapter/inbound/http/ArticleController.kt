package org.raghc.ingestion.article.adapter.inbound.http

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.raghc.ingestion.article.application.ArticleCommandResult
import org.raghc.ingestion.article.application.ArticleCommandService
import org.raghc.ingestion.article.application.CreateArticleCommand
import org.raghc.ingestion.article.application.ReviseArticleCommand
import org.raghc.ingestion.article.domain.ArticleContent
import org.raghc.ingestion.article.domain.ArticleId
import org.raghc.ingestion.article.domain.ArticleLocale
import org.raghc.ingestion.article.domain.TenantId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/articles")
class ArticleController(
    private val commandService: ArticleCommandService,
) {
    @PostMapping
    fun create(
        @RequestHeader("X-Tenant-Id") tenantId: UUID,
        @Valid @RequestBody request: CreateArticleRequest,
    ): ResponseEntity<ArticleCommandResponse> {
        val result =
            commandService.create(
                CreateArticleCommand(TenantId(tenantId), request.title, request.body, request.locale),
            )
        return ResponseEntity
            .created(URI.create("/api/v1/articles/${result.articleId.value}"))
            .eTag(result.etag())
            .body(result.toResponse())
    }

    @PutMapping("/{articleId}/content")
    fun revise(
        @RequestHeader("X-Tenant-Id") tenantId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @PathVariable articleId: UUID,
        @Valid @RequestBody request: ReviseArticleRequest,
    ): ResponseEntity<ArticleCommandResponse> {
        val result =
            commandService.revise(
                ReviseArticleCommand(
                    TenantId(tenantId),
                    ArticleId(articleId),
                    parseVersion(ifMatch),
                    request.title,
                    request.body,
                ),
            )
        return ResponseEntity.ok().eTag(result.etag()).body(result.toResponse())
    }

    private fun parseVersion(ifMatch: String): Long {
        val match = ETAG.matchEntire(ifMatch.trim()) ?: throw InvalidVersionPreconditionException(ifMatch)
        return match.groupValues[1].toLong().takeIf { it >= 1 }
            ?: throw InvalidVersionPreconditionException(ifMatch)
    }

    private fun ArticleCommandResult.etag(): String = "\"$streamVersion\""

    private fun ArticleCommandResult.toResponse() = ArticleCommandResponse(articleId.value, streamVersion)

    private companion object {
        val ETAG = Regex("^\"([0-9]+)\"$")
    }
}

data class CreateArticleRequest(
    @field:NotBlank @field:Size(max = ArticleContent.MAX_TITLE_LENGTH) val title: String,
    @field:NotBlank @field:Size(max = ArticleContent.MAX_BODY_LENGTH) val body: String,
    @field:Pattern(regexp = ArticleLocale.LANGUAGE_TAG_PATTERN) val locale: String,
)

data class ReviseArticleRequest(
    @field:NotBlank @field:Size(max = ArticleContent.MAX_TITLE_LENGTH) val title: String,
    @field:NotBlank @field:Size(max = ArticleContent.MAX_BODY_LENGTH) val body: String,
)

data class ArticleCommandResponse(
    val articleId: UUID,
    val streamVersion: Long,
)

class InvalidVersionPreconditionException(
    value: String,
) : RuntimeException("If-Match must contain a quoted positive stream version; received $value")
