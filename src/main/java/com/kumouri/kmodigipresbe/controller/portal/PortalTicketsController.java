package com.kumouri.kmodigipresbe.controller.portal;

import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.request.PortalTicketCreateRequest;
import com.kumouri.kmodigipresbe.model.response.PortalTicketSummary;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.service.ActivityCrudService;
import com.kumouri.kmodigipresbe.service.portal.PortalLinkedContactResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Portal-submitted support tickets. Tickets are persisted as {@link Activity} rows
 * with {@code type=TICKET, subjectType=CONTACT, subjectId=caller.contactId,
 * direction=INBOUND}. Creation routes through {@link ActivityCrudService#create},
 * which sets {@code occurredAt=now} and runs the standard {@code MentionResolver}
 * scan — a portal ticket that {@code @-mentions} a staff handle will fan out to the
 * shared inbox like any other activity.
 *
 * <p>If a future plan needs priority / status / SLA on tickets, model a dedicated
 * {@code Ticket} entity then — don't bolt fields onto Activity.
 */
@RestController
@RequestMapping("/portal/me")
@RequiredArgsConstructor
public class PortalTicketsController {

    private final PortalLinkedContactResolver linkedContact;
    private final ActivityCrudService activityCrud;
    private final ActivityRepository activities;

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PortalTicketSummary> createTicket(@Valid @RequestBody PortalTicketCreateRequest req) {
        return linkedContact.resolve()
                .flatMap(contact -> {
                    Activity activity = Activity.builder()
                            .type(ActivityType.TICKET)
                            .direction(ActivityDirection.INBOUND)
                            .subjectType(SubjectType.CONTACT)
                            .subjectId(contact.getId())
                            .summary(req.summary())
                            .body(req.body())
                            .build();
                    return activityCrud.create(activity);
                })
                .map(PortalTicketSummary::from);
    }

    @GetMapping("/tickets")
    public Flux<PortalTicketSummary> listTickets() {
        return linkedContact.resolve()
                .flatMapMany(contact -> activities
                        .findAllByTenantIdAndTypeAndSubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                                contact.getTenantId(),
                                ActivityType.TICKET,
                                SubjectType.CONTACT,
                                contact.getId()))
                .map(PortalTicketSummary::from);
    }
}
