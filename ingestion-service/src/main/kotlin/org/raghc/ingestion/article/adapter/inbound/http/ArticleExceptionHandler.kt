package org.raghc.ingestion.article.adapter.inbound.http

import org.raghc.ingestion.article.application.ArticleNotFoundException
import org.raghc.ingestion.article.application.ConcurrentArticleModificationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ArticleExceptionHandler {
    @ExceptionHandler(ArticleNotFoundException::class)
    fun notFound(exception: ArticleNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.message.orEmpty()).apply {
            title = "Article not found"
        }

    @ExceptionHandler(ConcurrentArticleModificationException::class)
    fun conflict(exception: ConcurrentArticleModificationException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.message.orEmpty()).apply {
            title = "Article version conflict"
            setProperty("expectedVersion", exception.expectedVersion)
            setProperty("actualVersion", exception.actualVersion)
        }

    @ExceptionHandler(InvalidVersionPreconditionException::class, IllegalArgumentException::class)
    fun badRequest(exception: RuntimeException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.message.orEmpty()).apply {
            title = "Invalid article command"
        }
}
