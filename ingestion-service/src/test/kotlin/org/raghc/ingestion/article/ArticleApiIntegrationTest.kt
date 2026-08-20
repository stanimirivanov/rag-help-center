package org.raghc.ingestion.article

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.OffsetDateTime
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ArticleApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcClient: JdbcClient,
) {
    @Test
    fun `publishes the article command OpenAPI contract`() {
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/api/v1/articles'].post").exists())
            .andExpect(jsonPath("$.paths['/api/v1/articles/{articleId}/content'].put").exists())
    }

    @Test
    fun `creates revises and rejects a stale version`() {
        val tenantId = UUID.randomUUID()
        val creation =
            mockMvc
                .perform(
                    post("/api/v1/articles")
                        .header("X-Tenant-Id", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY),
                ).andExpect(status().isCreated)
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.streamVersion").value(1))
                .andReturn()

        val articleId = creation.response.getHeader("Location")!!.substringAfterLast('/')
        val eventTimes =
            jdbcClient
                .sql(
                    """
                    select occurred_at, recorded_at
                    from article_events
                    where aggregate_id = :articleId
                    """.trimIndent(),
                ).param("articleId", UUID.fromString(articleId))
                .query { resultSet, _ ->
                    resultSet.getObject("occurred_at", OffsetDateTime::class.java) to
                        resultSet.getObject("recorded_at", OffsetDateTime::class.java)
                }.single()
        assertThat(eventTimes.second).isAfterOrEqualTo(eventTimes.first)

        mockMvc
            .perform(
                put("/api/v1/articles/{articleId}/content", articleId)
                    .header("X-Tenant-Id", tenantId)
                    .header("If-Match", "\"1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(REVISE_BODY),
            ).andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.streamVersion").value(2))

        mockMvc
            .perform(
                put("/api/v1/articles/{articleId}/content", articleId)
                    .header("X-Tenant-Id", tenantId)
                    .header("If-Match", "\"1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(REVISE_BODY.replace("updated", "stale")),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.actualVersion").value(2))
    }

    @Test
    fun `does not reveal an article to another tenant`() {
        val creation =
            mockMvc
                .perform(
                    post("/api/v1/articles")
                        .header("X-Tenant-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY),
                ).andReturn()
        val articleId = creation.response.getHeader("Location")!!.substringAfterLast('/')

        mockMvc
            .perform(
                put("/api/v1/articles/{articleId}/content", articleId)
                    .header("X-Tenant-Id", UUID.randomUUID())
                    .header("If-Match", "\"1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(REVISE_BODY),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `publishes projects and replays an article idempotently`() {
        val tenantId = UUID.randomUUID()
        val key = "create-${UUID.randomUUID()}"
        val first = createArticle(tenantId, key)
        val duplicate = createArticle(tenantId, key)
        assertThat(duplicate.response.getHeader("Location")).isEqualTo(first.response.getHeader("Location"))
        val articleId = first.response.getHeader("Location")!!.substringAfterLast('/')

        mockMvc
            .perform(
                post("/api/v1/articles/{articleId}/publish", articleId)
                    .header("X-Tenant-Id", tenantId)
                    .header("If-Match", "\"1\"")
                    .header("Idempotency-Key", "publish-${UUID.randomUUID()}"),
            ).andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"2\""))

        mockMvc
            .perform(
                get("/api/v1/articles/{articleId}/status", articleId).header("X-Tenant-Id", tenantId),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.lifecycleStatus").value("PUBLISHED"))
            .andExpect(jsonPath("$.revision").value(1))
            .andExpect(jsonPath("$.indexingStatus").value("PENDING"))

        val envelope =
            jdbcClient
                .sql("select payload::text from article_outbox where aggregate_id = :articleId")
                .param("articleId", UUID.fromString(articleId))
                .query(String::class.java)
                .single()
        assertThat(envelope)
            .contains("ArticlePublished")
            .contains("schemaVersion")
            .contains("traceId")
            .contains("Reset a password")
            .contains("Open account settings.")
            .contains("\"revision\": 1")

        jdbcClient.sql("delete from article_projection").update()
        mockMvc
            .perform(post("/internal/v1/projections/articles/replay"))
            .andExpect(status().isNoContent)
        mockMvc
            .perform(
                get("/api/v1/articles/{articleId}", articleId).header("X-Tenant-Id", tenantId),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.lifecycleStatus").value("PUBLISHED"))
    }

    @Test
    fun `rejects an idempotency key reused for different content`() {
        val tenantId = UUID.randomUUID()
        val key = "same-${UUID.randomUUID()}"
        createArticle(tenantId, key)
        mockMvc
            .perform(
                post("/api/v1/articles")
                    .header("X-Tenant-Id", tenantId)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CREATE_BODY.replace("Open account settings.", "Different content.")),
            ).andExpect(status().isConflict)
    }

    private fun createArticle(
        tenantId: UUID,
        key: String,
    ) = mockMvc
        .perform(
            post("/api/v1/articles")
                .header("X-Tenant-Id", tenantId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE_BODY),
        ).andExpect(status().isCreated)
        .andReturn()

    companion object {
        private const val CREATE_BODY =
            """{"title":"Reset a password","body":"Open account settings.","locale":"en"}"""
        private const val REVISE_BODY =
            """{"title":"Reset a password","body":"Follow the updated instructions."}"""

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
