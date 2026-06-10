package com.kumouri.kmodigipresbe.module.realestate.marketing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security fix BE-12 — {@link FairHousingLint#firstRiskTerm} channel-agnostic risk check used by
 * the SMS concierge as the deterministic auto-send backstop. Pure (no Docker).
 */
class FairHousingLintTest {

    @Test
    void flagsSteeringLanguage() {
        assertThat(FairHousingLint.firstRiskTerm(
                "This is a safe neighborhood, perfect for families.")).isNotNull();
        assertThat(FairHousingLint.firstRiskTerm("Great for kids!")).isEqualTo("great for kids");
        assertThat(FairHousingLint.firstRiskTerm("A quiet christian community.")).isNotNull();
    }

    @Test
    void cleanTextReturnsNull() {
        assertThat(FairHousingLint.firstRiskTerm(
                "The roof was replaced in 2019 with architectural shingles.")).isNull();
        assertThat(FairHousingLint.firstRiskTerm("The home has 3 bedrooms and 2 baths.")).isNull();
    }

    @Test
    void blankOrNullReturnsNull() {
        assertThat(FairHousingLint.firstRiskTerm(null)).isNull();
        assertThat(FairHousingLint.firstRiskTerm("")).isNull();
        assertThat(FairHousingLint.firstRiskTerm("   ")).isNull();
    }

    @Test
    void wordBoundaryAvoidsFalsePositive() {
        // "singles" is a banned token, but must not match inside "shingles".
        assertThat(FairHousingLint.firstRiskTerm("architectural shingles on the roof")).isNull();
    }
}
