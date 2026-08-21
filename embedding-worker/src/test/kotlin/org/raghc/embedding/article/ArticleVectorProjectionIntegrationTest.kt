package org.raghc.embedding.article

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.raghc.embedding.article.application.ArticleIndexingService
import org.raghc.embedding.article.application.ArticleIntegrationEvent
import org.raghc.embedding.article.application.ArticleRevisionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ArticleVectorProjectionIntegrationTest(
    @Autowired private val indexingService: ArticleIndexingService,
    @Autowired private val jdbcClient: JdbcClient,
) {
    private val tenantId = UUID.randomUUID()
    private val articleId = UUID.randomUUID()
    private val collectionId = UUID.randomUUID()

    @Test
    fun `redelivery replacement and withdrawal preserve one active revision`() {
        val first = event("ArticlePublished", 1)
        indexingService.handle(first)
        indexingService.handle(first)

        assertThat(count("embedding_inbox")).isEqualTo(1)
        assertThat(count("embedding_status_outbox")).isEqualTo(1)
        assertThat(indexedRevisions()).containsExactly(1L)
        assertThat(indexedCollections()).containsExactly(collectionId)

        indexingService.handle(event("ArticleRestored", 2))

        assertThat(indexedRevisions()).containsExactly(2L)

        indexingService.handle(event("ArticleWithdrawn", 2, includeContent = false))

        assertThat(indexedRevisions()).isEmpty()
        assertThat(count("embedding_status_outbox")).isEqualTo(3)
    }

    private fun event(
        type: String,
        revision: Long,
        includeContent: Boolean = true,
    ) = ArticleIntegrationEvent(
        UUID.randomUUID(),
        type,
        1,
        "test-trace",
        tenantId,
        articleId,
        revision,
        Instant.parse("2026-08-20T12:00:00Z"),
        ArticleRevisionData(
            revision,
            "Reset a password".takeIf { includeContent },
            "Open settings and follow the reset instructions.".takeIf { includeContent },
            "en".takeIf { includeContent },
            collectionId.takeIf { includeContent },
        ),
    )

    private fun count(table: String): Long =
        jdbcClient
            .sql("select count(*) from $table")
            .query(Long::class.java)
            .single()

    private fun indexedRevisions(): List<Long> =
        jdbcClient
            .sql(
                """
                select distinct (metadata->>'revision')::bigint as revision from vector_store
                where metadata->>'tenantId' = :tenantId and metadata->>'articleId' = :articleId
                order by (metadata->>'revision')::bigint
                """.trimIndent(),
            ).param("tenantId", tenantId.toString())
            .param("articleId", articleId.toString())
            .query { resultSet, _ -> resultSet.getLong("revision") }
            .list()

    private fun indexedCollections(): List<UUID> =
        jdbcClient
            .sql(
                """
                select distinct (metadata->>'collectionId')::uuid as collection_id from vector_store
                where metadata->>'tenantId' = :tenantId and metadata->>'articleId' = :articleId
                """.trimIndent(),
            ).param("tenantId", tenantId.toString())
            .param("articleId", articleId.toString())
            .query { resultSet, _ -> resultSet.getObject("collection_id", UUID::class.java) }
            .list()

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"))

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
