package org.raghc.ingestion.article

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
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
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ArticleApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
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
