package org.raghc.embedding

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class EmbeddingWorkerApplication

fun main() {
    runApplication<EmbeddingWorkerApplication>()
}
