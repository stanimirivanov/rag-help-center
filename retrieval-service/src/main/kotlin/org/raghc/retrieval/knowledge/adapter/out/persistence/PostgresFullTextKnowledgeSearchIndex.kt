package org.raghc.retrieval.knowledge.adapter.out.persistence

import org.raghc.retrieval.knowledge.application.KnowledgeChunk
import org.raghc.retrieval.knowledge.application.LexicalKnowledgeSearchIndex
import org.raghc.retrieval.knowledge.application.SearchKnowledgeQuery
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class PostgresFullTextKnowledgeSearchIndex(
    private val jdbcClient: JdbcClient,
) : LexicalKnowledgeSearchIndex {
    override fun search(query: SearchKnowledgeQuery): List<KnowledgeChunk> {
        val sql =
            """
            select id,
                   content,
                   (metadata->>'articleId')::uuid as article_id,
                   (metadata->>'revision')::bigint as revision,
                   (metadata->>'chunkIndex')::integer as chunk_index,
                   metadata->>'locale' as locale,
                   ts_rank_cd(to_tsvector('simple', content), websearch_to_tsquery('simple', :query)) as score
            from vector_store
            where metadata->>'tenantId' = :tenantId
              and (cast(:locale as text) is null or metadata->>'locale' = :locale)
              and to_tsvector('simple', content) @@ websearch_to_tsquery('simple', :query)
            order by score desc, id
            limit :topK
            """.trimIndent()
        return jdbcClient
            .sql(sql)
            .param("tenantId", query.tenantId.toString())
            .param("locale", query.locale)
            .param("query", query.query)
            .param("topK", query.topK)
            .query { row, _ ->
                KnowledgeChunk(
                    row.getObject("id", java.util.UUID::class.java),
                    row.getObject("article_id", java.util.UUID::class.java),
                    row.getLong("revision"),
                    row.getInt("chunk_index"),
                    row.getString("locale"),
                    row.getString("content"),
                    row.getDouble("score"),
                )
            }.list()
    }
}
