package org.raghc.qa

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class QaServiceApplication

fun main(args: Array<String>) {
    runApplication<QaServiceApplication>(*args)
}
