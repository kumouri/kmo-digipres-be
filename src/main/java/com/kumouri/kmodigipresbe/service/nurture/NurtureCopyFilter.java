package com.kumouri.kmodigipresbe.service.nurture;

import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureChannel;
import jakarta.annotation.Nullable;
import reactor.core.publisher.Mono;

/**
 * E1 — a strictly-additive, OPTIONAL post-compose copy filter (the keystone seam that lets a vertical
 * deployment vet/sanitize every outbound nurture message at the single {@link NurtureMessageComposer}
 * chokepoint, without touching the shared {@link NurtureRunner}).
 *
 * <h2>Why this is the right seam (T1 — RE Database Goldmine)</h2>
 * The RE "Database Goldmine" deploys the nurture engine to real estate, where <strong>every outbound
 * message must pass a Fair-Housing screen</strong> before it sends. But the RE deployment runs through the
 * <em>same shared</em> {@link NurtureRunner}, whose send path composes <em>and</em> dispatches in one
 * method ({@code ledgerFirstThenSend → composer.compose(...).flatMap(dispatch)}). A pure RE-side
 * post-filter outside the composer therefore cannot intercept the copy before it is sent. The composer is
 * the single chokepoint both the template path AND the best-effort AI-rewrite path funnel through, so a
 * filter applied here vets <strong>all</strong> outbound copy.
 *
 * <h2>VERTICAL-SCOPED (the GATE-2 fix)</h2>
 * Each filter declares the {@linkplain #vertical() vertical} it screens (e.g. {@code "realestate"} for
 * Fair-Housing, {@code "health"} for HIPAA). The composer holds a <strong>registry</strong> of filters
 * (additive {@link NurtureMessageComposer#registerCopyFilter}, never last-wins) and, when composing for an
 * enrollment, applies the filter whose {@link #appliesTo(String)} matches the <em>campaign's</em>
 * {@link NurtureCampaign#getVertical() vertical}. This is the keystone correctness fix: a process that
 * enables BOTH the realestate+nurture AND the frontdesk+nurture deployments now has each vertical's
 * messages screened by its own filter — the second consumer's registration no longer clobbers the first
 * (the prior single-field {@code setCopyFilter} bug, whose worst case was a health nurture message
 * shipping without the HIPAA screen). A fair-housing/HIPAA filter is therefore <strong>never skipped for
 * its own vertical and never applied to the wrong vertical</strong>.
 *
 * <h2>Strictly additive — E1 stays byte-equivalent</h2>
 * When no filter is registered (the default — E1 standalone, ChairFill/Home deployments) the composer's
 * output is unchanged, so the shipped E1 nurture ITs are byte-for-byte unaffected. A campaign whose
 * {@code vertical} is null/legacy (untagged) matches no filter and is likewise sent unfiltered — the safe
 * legacy behavior (we never guess a vertical and risk applying the <em>wrong</em> vertical's screen). A
 * vertical module (Real Estate / Health) contributes a filter bean + a side-effecting wiring bean that
 * registers it at init.
 *
 * <h2>Contract: never non-compliant, never dropped, never throws</h2>
 * An implementation MUST return a compliant body — the input itself when it is already clean, or a vetted
 * safe substitute when it is not (so a message always goes out; it is never silently dropped). It must be
 * best-effort: it never throws (the composer also guards it with {@code onErrorResume}, but the
 * implementation should not rely on that). It is pure w.r.t. the engine — it returns the body to send; it
 * does not itself send.
 */
public interface NurtureCopyFilter {

    /**
     * The vertical this filter screens — matched case-insensitively against a campaign's
     * {@link NurtureCampaign#getVertical()} by {@link #appliesTo(String)}. Stable, non-null (e.g.
     * {@code "realestate"} for the Fair-Housing screen, {@code "health"} for the HIPAA screen).
     */
    String vertical();

    /**
     * Whether this filter applies to a campaign tagged with {@code campaignVertical}. Case-insensitive
     * equality to {@link #vertical()}. A {@code null}/blank campaign vertical (legacy / untagged) returns
     * {@code false} — the safe invariant: a vertical-specific screen is never applied to a campaign that
     * has not opted into that vertical, and an untagged campaign therefore degrades to the unfiltered E1
     * behavior rather than risk the <em>wrong</em> vertical's substitution.
     *
     * @param campaignVertical the composing campaign's vertical tag (may be null)
     * @return true iff this filter should screen that campaign's copy
     */
    default boolean appliesTo(@Nullable String campaignVertical) {
        return campaignVertical != null
                && !campaignVertical.isBlank()
                && campaignVertical.trim().equalsIgnoreCase(vertical());
    }

    /**
     * Vet (and if necessary replace) one composed message body before it is sent.
     *
     * @param channel the channel the body will be sent on (SMS / EMAIL) — lets the filter pick a
     *                channel-appropriate safe substitute
     * @param body    the composed body (rendered template, possibly AI-rewritten)
     * @param contact the recipient (for rendering a personalized safe substitute)
     * @return the body to actually send (the input when already compliant, else a vetted safe substitute),
     *         wrapped with whether a substitution happened + a short reason; never errors
     */
    Mono<FilterResult> filter(NurtureChannel channel, String body, Contact contact);

    /**
     * The filter outcome.
     *
     * @param body     the body to send (compliant — the original or a safe substitute)
     * @param replaced true iff the original body was replaced by a safe substitute
     * @param reason   a short human reason when {@code replaced} (e.g. the flagged terms); null otherwise
     */
    record FilterResult(String body, boolean replaced, String reason) {

        /** The body passed the filter unchanged. */
        public static FilterResult passthrough(String body) {
            return new FilterResult(body, false, null);
        }

        /** The body was non-compliant and replaced with a vetted safe substitute. */
        public static FilterResult replaced(String safeBody, String reason) {
            return new FilterResult(safeBody, true, reason);
        }
    }
}
