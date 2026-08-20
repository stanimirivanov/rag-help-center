package org.raghc.ingestion.article.adapter.inbound.http

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.raghc.ingestion.article.application.ArticleCommandResult
import org.raghc.ingestion.article.application.ArticleCommandService
import org.raghc.ingestion.article.application.ArticleNotFoundException
import org.raghc.ingestion.article.application.ArticleQueryRepository
import org.raghc.ingestion.article.application.ArticleView
import org.raghc.ingestion.article.application.ChangeArticleStatusCommand
import org.raghc.ingestion.article.application.CreateArticleCommand
import org.raghc.ingestion.article.application.ReviseArticleCommand
import org.raghc.ingestion.article.domain.ArticleContent
import org.raghc.ingestion.article.domain.ArticleId
import org.raghc.ingestion.article.domain.ArticleLocale
import org.raghc.ingestion.article.domain.TenantId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
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
@Suppress("TooManyFunctions")
class ArticleController(
    private val commandService: ArticleCommandService,
    private val queryRepository: ArticleQueryRepository,
) {
    @PostMapping
    fun create(
        @RequestHeader("X-Tenant-Id") tenantId: UUID,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: CreateArticleRequest,
    ): ResponseEntity<ArticleCommandResponse> {
        val result =
            commandService.create(
                CreateArticleCommand(
                    TenantId(tenantId),
                    request.title,
                    request.body,
                    request.locale,
                    idempotencyKey,
                ),
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
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
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
                    idempotencyKey,
                ),
            )
        return ResponseEntity.ok().eTag(result.etag()).body(result.toResponse())
    }

    @PostMapping("/{articleId}/publish")
    fun publish(
        @RequestHeader("X-Tenant-Id") tenantId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @PathVariable articleId: UUID,
    ) = statusCommand(tenantId, articleId, ifMatch, key, commandService::publish)

    @PostMapping("/{articleId}/withdraw")
    fun withdraw(
        @RequestHeader("X-Tenant-Id") tenantId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @PathVariable articleId: UUID,
    ) = statusCommand(tenantId, articleId, ifMatch, key, commandService::withdraw)

    @PostMapping("/{articleId}/restore")
    fun restore(
        @RequestHeader("X-Tenant-Id") tenantId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @PathVariable articleId: UUID,
    ) = statusCommand(tenantId, articleId, ifMatch, key, commandService::restore)

    @GetMapping("/{articleId}")
    fun getArticle(
        @RequestHeader("X-Tenant-Id") tenantId: UUID,
        @PathVariable articleId: UUID,
    ): ArticleView =
        queryRepository.find(TenantId(tenantId), ArticleId(articleId))
            ?: throw ArticleNotFoundException(ArticleId(articleId))

    @GetMapping("/{articleId}/status")
    fun getStatus(
        @RequestHeader("X-Tenant-Id") tenantId: UUID,
        @PathVariable articleId: UUID,
    ): ArticleStatusResponse {
        val view = getArticle(tenantId, articleId)
        return ArticleStatusResponse(view.lifecycleStatus, view.revision, view.streamVersion, view.indexingStatus)
    }

    private fun statusCommand(
        tenantId: UUID,
        articleId: UUID,
        ifMatch: String,
        key: String?,
        operation: (ChangeArticleStatusCommand) -> ArticleCommandResult,
    ): ResponseEntity<ArticleCommandResponse> {
        val command =
            ChangeArticleStatusCommand(
                TenantId(tenantId),
                ArticleId(articleId),
                parseVersion(ifMatch),
                key,
            )
        val result = operation(command)
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

data class ArticleStatusResponse(
    val lifecycleStatus: String,
    val revision: Long,
    val streamVersion: Long,
    val indexingStatus: String,
)

class InvalidVersionPreconditionException(
    value: String,
) : RuntimeException("If-Match must contain a quoted positive stream version; received $value")
