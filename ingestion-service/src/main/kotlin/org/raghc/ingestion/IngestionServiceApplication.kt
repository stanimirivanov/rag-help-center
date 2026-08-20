package org.raghc.ingestion

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class IngestionServiceApplication

fun main() {
    runApplication<IngestionServiceApplication>()
}
