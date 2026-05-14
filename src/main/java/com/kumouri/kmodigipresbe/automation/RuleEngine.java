package com.kumouri.kmodigipresbe.automation;

import com.kumouri.kmodigipresbe.automation.condition.ConditionEvaluator;
import com.kumouri.kmodigipresbe.automation.webhook.WebhookSubscriptionRepository;
import com.kumouri.kmodigipresbe.automation.webhook.WebhookDeliveryService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Set;

/**
 * Subscribes to {@link DomainEventPublisher} and dispatches each event to
 * (a) all matching workflow rules and (b) every explicit webhook subscription
 * that handles the event type.
 *
 * <p>Each event is processed on the {@code parallel} scheduler so a slow webhook
 * receiver does not block subsequent events. A subscriber failure does NOT
 * unsubscribe — {@code onErrorContinue} swallows + logs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleEngine {

    private final DomainEventPublisher publisher;
    private final WorkflowRuleRepository rules;
    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryService webhooks;
    private final RuleActionDispatcher dispatcher;

    @PostConstruct
    public void start() {
        publisher.stream()
                .publishOn(Schedulers.parallel())
                .flatMap(this::process)
                .onErrorContinue((err, evt) ->
                        log.error("RuleEngine dropped event {}: {}", evt, err.toString()))
                .subscribe();
        log.info("RuleEngine subscribed to DomainEventPublisher");
    }

    Mono<Void> process(DomainEvent event) {
        TenantContext anonOps = new TenantContext(event.tenantId(), null, Set.of("AUTOMATION"));
        Flux<Void> ruleRuns = rules
                .findAllByTenantIdAndTriggerAndActive(event.tenantId(), event.type(), true)
                .filter(rule -> ConditionEvaluator.matches(rule.getConditions(), event.payload()))
                .flatMap(rule -> runRule(rule, event));
        Flux<Void> webhookFanout = subscriptions
                .findAllByTenantIdAndActive(event.tenantId(), true)
                .filter(sub -> sub.handles(event.type()))
                .flatMap(sub -> webhooks.deliver(sub, event)
                        .onErrorResume(err -> {
                            log.warn("Webhook fan-out to {} failed: {}", sub.getUrl(), err.toString());
                            return Mono.empty();
                        }));
        return Flux.merge(ruleRuns, webhookFanout)
                .contextWrite(TenantContextHolder.write(anonOps))
                .then();
    }

    private Mono<Void> runRule(WorkflowRule rule, DomainEvent event) {
        return Flux.fromIterable(rule.getActions() == null ? java.util.List.of() : rule.getActions())
                .concatMap(action -> dispatcher.dispatch(action, event)
                        .onErrorResume(err -> {
                            log.warn("Rule '{}' action {} failed: {}",
                                    rule.getName(), action.getType(), err.toString());
                            rule.setLastErrorMessage(err.toString());
                            return Mono.empty();
                        }))
                .then(Mono.defer(() -> {
                    rule.setLastFiredAt(event.occurredAt());
                    rule.setFireCount(rule.getFireCount() + 1);
                    return rules.save(rule).then();
                }));
    }
}
