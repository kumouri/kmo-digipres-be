package com.kumouri.kmodigipresbe.integration.twilio;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Real Estate Concierge (RE-1) — the shared inbound-SMS webhook ({@code TwilioInboundSmsController}) is
 * available when <em>either</em> the ChairFill module (CF-3, the gap-fill YES/STOP path) <em>or</em> the
 * Real Estate Concierge module (RE-1, the grounded concierge for a pure-realestate deployment) is enabled.
 *
 * <p>A property-based {@link AnyNestedCondition} (not {@code @ConditionalOnBean}) is used deliberately: the
 * {@code InboundSmsService} bean is contributed by an {@code @AutoConfiguration} which Spring processes
 * <em>after</em> component-scanning, so a {@code @ConditionalOnBean} on the component-scanned controller
 * would race the bean's registration and could spuriously drop the controller. Gating on the module
 * flags is order-independent and keeps the controller absent from the OpenAPI spec when no module is
 * on (the {@code NoShowRiskController} precedent). When ChairFill alone is on, this evaluates identically
 * to the previous {@code @ConditionalOnProperty(kmosf.modules.chairfill.enabled)} — byte-equivalent.
 *
 * <p>E2 adds a third nested condition: the {@code responder} module ({@link #ResponderEnabled}) also
 * contributes an {@code InboundSmsService} (a {@code @ConditionalOnMissingBean} fallback in
 * {@code ResponderAutoConfiguration} for a responder-only deployment), so the shared inbound-SMS webhook
 * must register when responder alone is on. {@code responder} defaults present
 * ({@code kmosf.modules.responder.enabled=true} in {@code application.properties}), so this nested
 * {@code @ConditionalOnProperty} (no {@code matchIfMissing}) matches by default; it does NOT change the
 * controller registration when chairfill/realestate are on (an {@code AnyNestedCondition} OR).
 */
public class InboundSmsModuleEnabledCondition extends AnyNestedCondition {

    public InboundSmsModuleEnabledCondition() {
        super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
    static class ChairFillEnabled {
    }

    @ConditionalOnProperty(prefix = "kmosf.modules.realestate", name = "enabled")
    static class RealEstateEnabled {
    }

    @ConditionalOnProperty(prefix = "kmosf.modules.responder", name = "enabled")
    static class ResponderEnabled {
    }
}
