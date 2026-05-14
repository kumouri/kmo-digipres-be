package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationDTO;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.service.ActivityCrudService;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import com.kumouri.kmodigipresbe.util.RequestMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/communication")
public class CommunicationController {

    private final EmailService emailService;
    private final RequestMapper requestMapper;
    private final ContactRepository contacts;
    private final ActivityCrudService activities;

    @PostMapping("/singleEmail")
    public Mono<Boolean> sendEmail(@RequestBody SingleEmailCommunicationDTO request) {
        SingleEmailCommunicationRequest parsed = requestMapper.toSingleEmailCommunicationRequest(request);
        return emailService.sendSingleEmail(parsed)
                .flatMap(sent -> {
                    if (!Boolean.TRUE.equals(sent)) return Mono.just(false);
                    return logToTimeline(parsed).thenReturn(true);
                });
    }

    private Mono<Activity> logToTimeline(SingleEmailCommunicationRequest req) {
        return TenantContextHolder.required().flatMap(ctx ->
                contacts.findByTenantAndEmailAddress(ctx.tenantId(), req.to().asString())
                        .next()
                        .flatMap(matched -> {
                            Map<String, Object> payload = new HashMap<>();
                            payload.put("to", req.to().asString());
                            payload.put("from", req.from().asString());
                            Activity a = Activity.builder()
                                    .type(ActivityType.EMAIL)
                                    .direction(ActivityDirection.OUTBOUND)
                                    .subjectType(SubjectType.CONTACT)
                                    .subjectId(matched.getId())
                                    .summary(req.subject())
                                    .body(req.body())
                                    .occurredAt(Instant.now())
                                    .payload(payload)
                                    .build();
                            return activities.log(a);
                        }));
    }
}
