package com.kumouri.kmodigipresbe.integration.twilio.voice.extract;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the per-tenant {@link VoicemailExtractionStrategy} from the wire value of
 * {@code IntegrationConnection(twilio).config.voicemailVertical} (HS-1 — Home Services front desk).
 * Spring injects every {@link VoicemailExtractionStrategy} {@code @Component}; this indexes them by
 * {@link VoicemailExtractionStrategy#verticalKey()}.
 *
 * <p>{@link #forVertical} <strong>defaults to {@code mole-pest}</strong> for an absent, blank, or
 * unrecognized config value — so NMM (whose Twilio row carries no {@code voicemailVertical} key)
 * resolves to the mole strategy and stays byte-equivalent, and a mis-set value degrades safely to
 * mole rather than erroring (plan §6 vertical-selection-drift mitigation). An explicitly-set value
 * that is not a registered strategy is logged at {@code warn} the first time it is seen.
 */
@Slf4j
@Component
public class VoicemailExtractionStrategyResolver {

    /** The default vertical when {@code voicemailVertical} is absent/blank/unknown. */
    public static final String DEFAULT_VERTICAL = MolePestExtractionStrategy.VERTICAL_KEY;

    private final Map<String, VoicemailExtractionStrategy> byVertical;

    public VoicemailExtractionStrategyResolver(List<VoicemailExtractionStrategy> strategies) {
        Map<String, VoicemailExtractionStrategy> map = new HashMap<>();
        for (VoicemailExtractionStrategy s : strategies) {
            VoicemailExtractionStrategy prior = map.put(s.verticalKey(), s);
            if (prior != null) {
                throw new IllegalStateException(
                        "Duplicate VoicemailExtractionStrategy for vertical " + s.verticalKey());
            }
        }
        this.byVertical = Map.copyOf(map);
        if (!byVertical.containsKey(DEFAULT_VERTICAL)) {
            throw new IllegalStateException(
                    "No default VoicemailExtractionStrategy registered for vertical "
                            + DEFAULT_VERTICAL);
        }
        log.info("VoicemailExtractionStrategyResolver: registered verticals {}", byVertical.keySet());
    }

    /**
     * Returns the strategy for the given {@code voicemailVertical} config value, defaulting to the
     * mole-pest strategy for an absent/blank/unknown value.
     */
    public VoicemailExtractionStrategy forVertical(String configValue) {
        if (configValue == null || configValue.isBlank()) {
            return byVertical.get(DEFAULT_VERTICAL);
        }
        VoicemailExtractionStrategy s = byVertical.get(configValue.trim());
        if (s == null) {
            log.warn("VoicemailExtractionStrategyResolver: unknown voicemailVertical '{}' — "
                    + "defaulting to '{}'", configValue, DEFAULT_VERTICAL);
            return byVertical.get(DEFAULT_VERTICAL);
        }
        return s;
    }
}
