package org.raghc.qa.retrieval.adapter.outbound.http

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.raghc.qa.retrieval.application.RetrievalQuery
import org.springframework.web.client.ResourceAccessException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

class HttpRetrieveKnowledgeAdapterTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `sends the retrieval contract and maps its response`() {
        val tenantId = UUID.randomUUID()
        val collectionId = UUID.randomUUID()
        val chunkId = UUID.randomUUID()
        val articleId = UUID.randomUUID()
        var receivedTenant: String? = null
        var receivedBody: String? = null
        startServer { exchange ->
            receivedTenant = exchange.requestHeaders.getFirst("X-Tenant-Id")
            receivedBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            val response =
                """
                {
                  "chunks": [{
                    "chunkId": "$chunkId", "articleId": "$articleId", "revision": 3,
                    "chunkIndex": 1, "locale": "en-US", "content": "Reset your password.", "score": 0.91
                  }]
                }
                """.trimIndent()
            val responseBytes = response.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }

        val adapter = createAdapter(Duration.ofSeconds(2))
        val chunks =
            adapter.retrieve(
                RetrievalQuery(
                    tenantId,
                    "How do I reset it?",
                    "en-US",
                    5,
                    0.7,
                    collectionId,
                ),
            )

        assertThat(receivedTenant).isEqualTo(tenantId.toString())
        assertThat(receivedBody).contains(
            "\"query\":\"How do I reset it?\"",
            "\"collectionId\":\"$collectionId\"",
            "\"locale\":\"en-US\"",
            "\"topK\":5",
            "\"minimumScore\":0.7",
        )
        assertThat(chunks).hasSize(1)
        val chunk = chunks.single()
        assertThat(chunk.chunkId).isEqualTo(chunkId)
        assertThat(chunk.articleId).isEqualTo(articleId)
        assertThat(chunk.revision).isEqualTo(3)
        assertThat(chunk.chunkIndex).isEqualTo(1)
        assertThat(chunk.locale).isEqualTo("en-US")
        assertThat(chunk.content).isEqualTo("Reset your password.")
        assertThat(chunk.score).isEqualTo(0.91)
    }

    @Test
    fun `enforces the configured read timeout`() {
        startServer { exchange ->
            Thread.sleep(300)
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }

        val adapter = createAdapter(Duration.ofMillis(50))

        assertThatThrownBy { adapter.retrieve(RetrievalQuery(UUID.randomUUID(), "question")) }
            .isInstanceOf(ResourceAccessException::class.java)
    }

    private fun createAdapter(readTimeout: Duration): HttpRetrieveKnowledgeAdapter {
        val properties =
            RetrievalClientProperties(
                URI("http://localhost:${server!!.address.port}"),
                Duration.ofSeconds(1),
                readTimeout,
            )
        return HttpRetrieveKnowledgeAdapter(RetrievalClientConfiguration().retrievalHttpService(properties))
    }

    private fun startServer(handler: com.sun.net.httpserver.HttpHandler) {
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server!!.createContext("/internal/v1/search", handler)
        server!!.start()
    }
}
