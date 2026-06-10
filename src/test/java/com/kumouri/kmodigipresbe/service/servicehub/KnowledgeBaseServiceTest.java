package com.kumouri.kmodigipresbe.service.servicehub;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security fix BE-17 — {@link KnowledgeBaseService#sanitizeTextQuery} strips MongoDB $text operator
 * syntax (leading {@code -}/{@code +} term operators, {@code "} exact-phrase quotes). Pure (no Docker).
 */
class KnowledgeBaseServiceTest {

    @Test
    void stripsLeadingMinusExclusionOperator() {
        // "-secret" would EXCLUDE docs containing "secret"; sanitized to a plain term.
        assertThat(KnowledgeBaseService.sanitizeTextQuery("-secret")).isEqualTo("secret");
        assertThat(KnowledgeBaseService.sanitizeTextQuery("billing -refund"))
                .isEqualTo("billing refund");
    }

    @Test
    void stripsQuotesPhraseOperator() {
        assertThat(KnowledgeBaseService.sanitizeTextQuery("\"exact phrase\""))
                .isEqualTo("exact phrase");
    }

    @Test
    void stripsLeadingPlusOperator() {
        assertThat(KnowledgeBaseService.sanitizeTextQuery("+required term"))
                .isEqualTo("required term");
    }

    @Test
    void plainQueryUnchanged() {
        assertThat(KnowledgeBaseService.sanitizeTextQuery("how to reset password"))
                .isEqualTo("how to reset password");
    }

    @Test
    void blankOrNullBecomesEmpty() {
        assertThat(KnowledgeBaseService.sanitizeTextQuery(null)).isEmpty();
        assertThat(KnowledgeBaseService.sanitizeTextQuery("")).isEmpty();
        assertThat(KnowledgeBaseService.sanitizeTextQuery("   ")).isEmpty();
    }

    @Test
    void onlyOperatorsCollapseToEmpty() {
        assertThat(KnowledgeBaseService.sanitizeTextQuery("- -- \"\"")).isEmpty();
    }

    @Test
    void collapsesInternalWhitespace() {
        assertThat(KnowledgeBaseService.sanitizeTextQuery("  multiple   spaces  here "))
                .isEqualTo("multiple spaces here");
    }
}
