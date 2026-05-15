package com.kumouri.kmodigipresbe.service.servicehub;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.servicehub.SlaPolicy;
import com.kumouri.kmodigipresbe.model.servicehub.Ticket;
import com.kumouri.kmodigipresbe.model.servicehub.TicketComment;
import com.kumouri.kmodigipresbe.model.servicehub.TicketPriority;
import com.kumouri.kmodigipresbe.model.servicehub.TicketStatus;
import com.kumouri.kmodigipresbe.repository.SlaPolicyRepository;
import com.kumouri.kmodigipresbe.repository.TicketCommentRepository;
import com.kumouri.kmodigipresbe.repository.TicketRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ticket lifecycle: NEW → OPEN → PENDING → RESOLVED → CLOSED.
 * Forward-only except PENDING → OPEN (re-open after waiting on customer).
 * Backwards to OPEN is allowed; re-opening RESOLVED or CLOSED is not.
 *
 * <p>On CREATE, the SLA policy (if set) is resolved and {@code slaResponseDue}
 * / {@code slaResolutionDue} are computed from {@code priority}. The
 * {@link SlaBreachScheduler} scans every minute and marks overdue tickets.
 */
@Service
@RequiredArgsConstructor
public class TicketService {

    /** Transitions that are NOT permitted. Anything not listed here is allowed. */
    private static final Set<String> ILLEGAL_TRANSITIONS = Set.of(
            "RESOLVED->OPEN",
            "RESOLVED->PENDING",
            "CLOSED->NEW",
            "CLOSED->OPEN",
            "CLOSED->PENDING",
            "CLOSED->RESOLVED"
    );

    private final TicketRepository tickets;
    private final TicketCommentRepository comments;
    private final SlaPolicyRepository slaPolicies;
    private final DomainEventPublisher events;

    public Flux<Ticket> findAll() {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> tickets.findAllByTenantId(ctx.tenantId()));
    }

    public Mono<Ticket> findById(UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> tickets.findByTenantIdAndId(ctx.tenantId(), id))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException("Ticket not found", 2901, 404)));
    }

    public Mono<Ticket> create(Ticket body) {
        return TenantContextHolder.required().flatMap(ctx -> {
            body.setId(null);
            body.setStatus(TicketStatus.NEW);
            body.setTenantId(ctx.tenantId());

            Mono<Ticket> withSla;
            if (body.getSlaPolicyId() != null) {
                withSla = slaPolicies.findByTenantIdAndId(ctx.tenantId(), body.getSlaPolicyId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException("SLA policy not found", 2902, 404)))
                        .map(policy -> applySlaDueDates(body, policy));
            } else {
                withSla = Mono.just(body);
            }

            return withSla.flatMap(tickets::save)
                    .flatMap(saved -> {
                        DomainEvent event = new DomainEvent(
                                DomainEventType.TICKET_CREATED, saved.getTenantId(), saved.getId(),
                                Map.of("priority", saved.getPriority().name(),
                                        "subject", saved.getSubject() == null ? "" : saved.getSubject()),
                                Instant.now());
                        events.publish(event);
                        return Mono.just(saved);
                    });
        });
    }

    public Mono<Ticket> update(UUID id, Ticket patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getSubject() != null) existing.setSubject(patch.getSubject());
            if (patch.getBody() != null) existing.setBody(patch.getBody());
            if (patch.getPriority() != null) existing.setPriority(patch.getPriority());
            if (patch.getAssignedUserId() != null) existing.setAssignedUserId(patch.getAssignedUserId());
            return tickets.save(existing)
                    .flatMap(saved -> {
                        events.publish(new DomainEvent(
                                DomainEventType.TICKET_UPDATED, saved.getTenantId(), saved.getId(),
                                Map.of("priority", saved.getPriority().name()), Instant.now()));
                        return Mono.just(saved);
                    });
        });
    }

    public Mono<Ticket> transition(UUID id, TicketStatus newStatus) {
        return findById(id).flatMap(existing -> {
            String transitionKey = existing.getStatus().name() + "->" + newStatus.name();
            if (ILLEGAL_TRANSITIONS.contains(transitionKey)) {
                return Mono.error(new DigiPresBeException(
                        "Transition " + transitionKey + " is not permitted", 2900, 409));
            }
            TicketStatus prev = existing.getStatus();
            existing.setStatus(newStatus);
            if (newStatus == TicketStatus.RESOLVED && existing.getResolvedAt() == null) {
                existing.setResolvedAt(Instant.now());
            }
            return tickets.save(existing)
                    .flatMap(saved -> {
                        events.publish(new DomainEvent(
                                DomainEventType.TICKET_STATUS_CHANGED, saved.getTenantId(), saved.getId(),
                                Map.of("from", prev.name(), "to", newStatus.name()), Instant.now()));
                        return Mono.just(saved);
                    });
        });
    }

    public Mono<Void> delete(UUID id) {
        return findById(id).flatMap(t -> tickets.deleteById(t.getId()));
    }

    public Flux<TicketComment> listComments(UUID ticketId) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> comments.findAllByTenantIdAndTicketIdOrderByCreatedAtAsc(
                        ctx.tenantId(), ticketId));
    }

    public Mono<TicketComment> addComment(UUID ticketId, TicketComment body) {
        return TenantContextHolder.required().flatMap(ctx ->
                findById(ticketId).flatMap(ticket -> {
                    body.setId(null);
                    body.setTenantId(ctx.tenantId());
                    body.setTicketId(ticketId);
                    if (ctx.userId() != null) body.setAuthorUserId(ctx.userId());
                    return comments.save(body);
                }));
    }

    private static Ticket applySlaDueDates(Ticket ticket, SlaPolicy policy) {
        String priorityKey = ticket.getPriority() == null
                ? TicketPriority.MEDIUM.name()
                : ticket.getPriority().name();
        Instant now = Instant.now();
        Integer responseMinutes = policy.getResponseTargetMinutes().get(priorityKey);
        Integer resolutionMinutes = policy.getResolutionTargetMinutes().get(priorityKey);
        if (responseMinutes != null) {
            ticket.setSlaResponseDue(now.plusSeconds(responseMinutes * 60L));
        }
        if (resolutionMinutes != null) {
            ticket.setSlaResolutionDue(now.plusSeconds(resolutionMinutes * 60L));
        }
        return ticket;
    }
}
