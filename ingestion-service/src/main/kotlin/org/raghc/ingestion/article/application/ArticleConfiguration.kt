package org.raghc.ingestion.article.application

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ArticleConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
