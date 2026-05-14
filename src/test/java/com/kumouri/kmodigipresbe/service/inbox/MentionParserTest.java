package com.kumouri.kmodigipresbe.service.inbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MentionParserTest {

    @Test
    void plainHandleExtracted() {
        assertThat(MentionParser.extract("Hello @alice, please review"))
                .containsExactly("alice");
    }

    @Test
    void dottedAndDashedAndUnderscoredHandlesExtracted() {
        assertThat(MentionParser.extract("@alice.smith @bob-jones @carol_42 done"))
                .containsExactly("alice.smith", "bob-jones", "carol_42");
    }

    @Test
    void emailAddressIsNotAMention() {
        // alice@example.com must not produce a mention "example".
        assertThat(MentionParser.extract("Send to alice@example.com soon"))
                .isEmpty();
    }

    @Test
    void duplicatesDeduped_orderPreserved() {
        assertThat(MentionParser.extract("@dave first, then @alice, then @dave again"))
                .containsExactly("dave", "alice");
    }

    @Test
    void emptyOrNullText_returnsEmpty() {
        assertThat(MentionParser.extract(null)).isEmpty();
        assertThat(MentionParser.extract("   ")).isEmpty();
        assertThat(MentionParser.extract("no mentions here")).isEmpty();
    }

    @Test
    void standaloneAtSignNotMatched() {
        assertThat(MentionParser.extract("@ alone is not a mention"))
                .isEmpty();
    }

    @Test
    void handleMustStartWithLetter() {
        // Avoid catching "@123" which probably isn't a handle.
        assertThat(MentionParser.extract("@123 numeric @abc letters"))
                .containsExactly("abc");
    }
}
