package org.raghc.ingestion.article.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ArticleContentTest {
    @Test
    fun `reports all content violations together`() {
        val exception =
            assertThrows<InvalidArticleContentException> {
                ArticleContent.create(" ", "", ArticleLocale.of("en"))
            }

        assertThat(exception.violations).containsExactlyInAnyOrder(
            ArticleContentViolation.BLANK_TITLE,
            ArticleContentViolation.BLANK_BODY,
        )
    }

    @Test
    fun `rejects an invalid locale at the value object boundary`() {
        assertThatThrownBy { ArticleLocale.of("english") }
            .isInstanceOf(InvalidArticleLocaleException::class.java)
    }
}
