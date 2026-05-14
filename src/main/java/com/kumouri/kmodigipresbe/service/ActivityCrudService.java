package com.kumouri.kmodigipresbe.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.service.inbox.MentionResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ActivityCrudService {

    private final ActivityRepository activities;
    private final MentionResolver mentions;

    public Flux<Activity> findAll() {
        return activities.findAll();
    }

    public Mono<Activity> findById(UUID id) {
        return activities.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Activity not found", 1300, 404)));
    }

    public Mono<Activity> create(Activity activity) {
        activity.setId(null);
        if (activity.getOccurredAt() == null) {
            activity.setOccurredAt(Instant.now());
        }
        return activities.save(activity)
                .flatMap(saved -> mentions.processActivity(saved).thenReturn(saved));
    }

    public Mono<Activity> log(Activity activity) {
        return create(activity);
    }

    public Mono<Activity> update(UUID id, Activity patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getType() != null) existing.setType(patch.getType());
            if (patch.getDirection() != null) existing.setDirection(patch.getDirection());
            if (patch.getSubjectType() != null) existing.setSubjectType(patch.getSubjectType());
            if (patch.getSubjectId() != null) existing.setSubjectId(patch.getSubjectId());
            if (patch.getSummary() != null) existing.setSummary(patch.getSummary());
            if (patch.getBody() != null) existing.setBody(patch.getBody());
            if (patch.getOccurredAt() != null) existing.setOccurredAt(patch.getOccurredAt());
            if (patch.getDueAt() != null) existing.setDueAt(patch.getDueAt());
            if (patch.getCompletedAt() != null) existing.setCompletedAt(patch.getCompletedAt());
            if (patch.getOwnerId() != null) existing.setOwnerId(patch.getOwnerId());
            if (patch.getPayload() != null) existing.setPayload(patch.getPayload());
            if (patch.getCustomFields() != null) existing.setCustomFields(patch.getCustomFields());
            return activities.save(existing)
                    .flatMap(saved -> mentions.processActivity(saved).thenReturn(saved));
        });
    }

    public Mono<Void> delete(UUID id) {
        return activities.deleteById(id);
    }
}
