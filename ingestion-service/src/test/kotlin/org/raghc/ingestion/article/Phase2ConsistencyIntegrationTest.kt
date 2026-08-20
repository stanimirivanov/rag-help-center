package org.raghc.ingestion.article

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.raghc.ingestion.article.adapter.out.messaging.ArticleOutboxPublisher
import org.raghc.ingestion.article.application.ArticleCommandService
import org.raghc.ingestion.article.application.ChangeArticleStatusCommand
import org.raghc.ingestion.article.application.CreateArticleCommand
import org.raghc.ingestion.article.domain.TenantId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class Phase2ConsistencyIntegrationTest(
    @Autowired private val jdbcClient: JdbcClient,
    @Autowired private val commandService: ArticleCommandService,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    @Test
    fun `rolls back event projection outbox and idempotency when the outbox write fails`() {
        clearOutbox()
        val tenantId = TenantId(UUID.randomUUID())
        val created = commandService.create(CreateArticleCommand(tenantId, "Rollback", "Initial content", "en", null))
        val commandKey = "rollback-${UUID.randomUUID()}"
        insertOutboxRow(tenantId.value, created.articleId.value, 2)

        assertThatThrownBy {
            commandService.publish(ChangeArticleStatusCommand(tenantId, created.articleId, 1, commandKey))
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        val eventCount = countByArticle("article_events", created.articleId.value)
        val projection =
            jdbcClient
                .sql(
                    "select lifecycle_status, stream_version from article_projection where article_id = :articleId",
                ).param("articleId", created.articleId.value)
                .query { rs, _ -> rs.getString("lifecycle_status") to rs.getLong("stream_version") }
                .single()
        val idempotencyCount =
            jdbcClient
                .sql("select count(*) from command_idempotency where idempotency_key = :key")
                .param("key", commandKey)
                .query(Long::class.java)
                .single()

        assertThat(eventCount).isEqualTo(1)
        assertThat(projection).isEqualTo("DRAFT" to 1L)
        assertThat(idempotencyCount).isZero()
    }

    @Test
    fun `two publisher instances cannot publish the same pending row concurrently`() {
        clearOutbox()
        val tenantId = UUID.randomUUID()
        val articleId = UUID.randomUUID()
        insertOutboxRow(tenantId, articleId, 1)
        val kafkaTemplate = kafkaTemplateMock()
        val sendStarted = CountDownLatch(1)
        val sendCompletion = CompletableFuture<SendResult<String, String>>()
        `when`(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer {
            sendStarted.countDown()
            sendCompletion
        }
        val firstPublisher = ArticleOutboxPublisher(jdbcClient, kafkaTemplate)
        val secondPublisher = ArticleOutboxPublisher(jdbcClient, kafkaTemplate)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = CompletableFuture.runAsync({ publishInTransaction(firstPublisher) }, executor)
            assertThat(sendStarted.await(5, TimeUnit.SECONDS)).isTrue()
            val second = CompletableFuture.runAsync({ publishInTransaction(secondPublisher) }, executor)
            second.get(5, TimeUnit.SECONDS)

            sendCompletion.complete(sendResultMock())
            first.get(5, TimeUnit.SECONDS)

            verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString())
            assertThat(countPendingOutbox(articleId)).isZero()
        } finally {
            sendCompletion.cancel(true)
            executor.shutdownNow()
        }
    }

    private fun publishInTransaction(publisher: ArticleOutboxPublisher) {
        transactionTemplate.executeWithoutResult { publisher.publishBatch() }
    }

    private fun clearOutbox() {
        jdbcClient.sql("delete from article_outbox").update()
    }

    private fun insertOutboxRow(
        tenantId: UUID,
        articleId: UUID,
        streamVersion: Long,
    ) {
        jdbcClient
            .sql(
                """
                insert into article_outbox
                    (outbox_id, tenant_id, aggregate_id, stream_version, event_type,
                     schema_version, trace_id, payload, occurred_at)
                values (:id, :tenantId, :articleId, :streamVersion, 'TestEvent', 1, 'test-trace', '{}'::jsonb,
                        clock_timestamp())
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("tenantId", tenantId)
            .param("articleId", articleId)
            .param("streamVersion", streamVersion)
            .update()
    }

    private fun countByArticle(
        table: String,
        articleId: UUID,
    ): Long =
        jdbcClient
            .sql("select count(*) from $table where aggregate_id = :articleId")
            .param("articleId", articleId)
            .query(Long::class.java)
            .single()

    private fun countPendingOutbox(articleId: UUID): Long =
        jdbcClient
            .sql(
                "select count(*) from article_outbox where aggregate_id = :articleId and published_at is null",
            ).param("articleId", articleId)
            .query(Long::class.java)
            .single()

    @Suppress("UNCHECKED_CAST")
    private fun kafkaTemplateMock(): KafkaTemplate<String, String> {
        val result = mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun sendResultMock(): SendResult<String, String> {
        val result = mock(SendResult::class.java) as SendResult<String, String>
        return result
    }

    companion object {
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
