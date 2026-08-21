package org.raghc.retrieval.knowledge.adapter.inbound.http

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class RetrievalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(exception: MethodArgumentNotValidException): ProblemDetail =
        problem("Invalid search request").apply {
            setProperty(
                "violations",
                exception.bindingResult.fieldErrors
                    .map { mapOf("field" to it.field, "message" to it.defaultMessage.orEmpty()) }
                    .sortedBy { it.getValue("field") },
            )
        }

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidQuery(exception: IllegalArgumentException): ProblemDetail =
        problem(
            title = "Invalid search request",
            detail = exception.message.orEmpty(),
        )

    private fun problem(
        title: String,
        detail: String = "The request contains invalid values",
    ) = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail).apply { this.title = title }
}
