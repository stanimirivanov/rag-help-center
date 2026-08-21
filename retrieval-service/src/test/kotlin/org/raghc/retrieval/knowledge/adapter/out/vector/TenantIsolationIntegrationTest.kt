package org.raghc.retrieval.knowledge.adapter.out.vector

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.raghc.retrieval.knowledge.adapter.out.persistence.PostgresFullTextKnowledgeSearchIndex
import org.raghc.retrieval.knowledge.application.SearchKnowledgeQuery
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.AbstractEmbeddingModel
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TenantIsolationIntegrationTest.EmbeddingConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
class TenantIsolationIntegrationTest(
    @Autowired private val vectorStore: VectorStore,
    @Autowired private val searchIndex: SpringAiKnowledgeSearchIndex,
    @Autowired private val fullTextIndex: PostgresFullTextKnowledgeSearchIndex,
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `publishes the versioned retrieval OpenAPI contract`() {
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/internal/v1/search'].post").exists())
            .andExpect(jsonPath("$.paths['/internal/v1/search'].post.parameters[0].name").value("X-Tenant-Id"))
            .andExpect(jsonPath("$.components.schemas.KnowledgeSearchRequest").exists())
            .andExpect(jsonPath("$.components.schemas.KnowledgeSearchResponse").exists())
    }

    @Test
    fun `returns problem details for an invalid search request`() {
        mockMvc
            .perform(
                post("/internal/v1/search")
                    .header("X-Tenant-Id", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"query":" ","topK":0,"minimumScore":2.0}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Invalid search request"))
            .andExpect(jsonPath("$.violations.length()").value(3))
    }

    @Test
    fun `database search never crosses tenant or locale boundaries`() {
        val requestedTenant = UUID.randomUUID()
        val otherTenant = UUID.randomUUID()
        val expectedArticle = UUID.randomUUID()
        vectorStore.add(
            listOf(
                document(requestedTenant, expectedArticle, "en", "Expected English result"),
                document(requestedTenant, UUID.randomUUID(), "de", "Same tenant, wrong locale"),
                document(otherTenant, UUID.randomUUID(), "en", "Wrong tenant, same locale"),
            ),
        )

        val results =
            searchIndex.search(
                SearchKnowledgeQuery(
                    requestedTenant,
                    query = "password reset",
                    locale = "en",
                    topK = 10,
                    minimumScore = 0.0,
                ),
            )

        assertThat(results).hasSize(1)
        assertThat(results.single().articleId).isEqualTo(expectedArticle)
        assertThat(results.single().content).isEqualTo("Expected English result")

        val lexicalResults =
            fullTextIndex.search(
                SearchKnowledgeQuery(
                    requestedTenant,
                    query = "Expected English",
                    locale = "en",
                    minimumScore = 0.0,
                ),
            )
        assertThat(lexicalResults).hasSize(1)
        assertThat(lexicalResults.single().articleId).isEqualTo(expectedArticle)

        val allRequestedTenantLocales =
            fullTextIndex.search(
                SearchKnowledgeQuery(
                    requestedTenant,
                    query = "tenant",
                    minimumScore = 0.0,
                ),
            )
        assertThat(allRequestedTenantLocales).hasSize(1)
        assertThat(allRequestedTenantLocales.single().locale).isEqualTo("de")
    }

    private fun document(
        tenantId: UUID,
        articleId: UUID,
        locale: String,
        content: String,
    ) = Document(
        UUID.randomUUID().toString(),
        content,
        mapOf(
            "tenantId" to tenantId.toString(),
            "articleId" to articleId.toString(),
            "revision" to 1L,
            "chunkIndex" to 0,
            "locale" to locale,
        ),
    )

    @TestConfiguration(proxyBeanMethods = false)
    class EmbeddingConfiguration {
        @Bean
        fun embeddingModel() = ConstantEmbeddingModel()
    }

    class ConstantEmbeddingModel : AbstractEmbeddingModel() {
        override fun call(request: EmbeddingRequest) =
            EmbeddingResponse(request.instructions.mapIndexed { index, _ -> Embedding(VECTOR.copyOf(), index) })

        override fun embed(document: Document): FloatArray = VECTOR.copyOf()

        override fun dimensions(): Int = VECTOR.size

        private companion object {
            val VECTOR = FloatArray(8) { 1.0f }
        }
    }

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
