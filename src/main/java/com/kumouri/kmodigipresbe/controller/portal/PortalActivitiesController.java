package com.kumouri.kmodigipresbe.controller.portal;

import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.response.PortalActivitySummary;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.service.portal.PortalLinkedContactResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Portal-facing activity timeline for the caller's Contact. Read-only. Uses the
 * existing {@code tenant_subject_idx} compound index to scan activities sorted by
 * {@code occurredAt DESC}; the response projection drops staff/internal fields
 * (payload, ownerId, subjectType/subjectId).
 */
@RestController
@RequestMapping("/portal/me")
@RequiredArgsConstructor
public class PortalActivitiesController {

    private final PortalLinkedContactResolver linkedContact;
    private final ActivityRepository activities;

    @GetMapping("/activities")
    public Flux<PortalActivitySummary> listActivities() {
        return linkedContact.resolve()
                .flatMapMany(contact -> activities
                        .findAllByTenantIdAndSubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                                contact.getTenantId(), SubjectType.CONTACT, contact.getId()))
                .map(PortalActivitySummary::from);
    }
}
