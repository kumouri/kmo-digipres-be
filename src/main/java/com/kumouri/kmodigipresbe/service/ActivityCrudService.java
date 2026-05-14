package com.kumouri.kmodigipresbe.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
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
        return activities.save(activity);
    }

    public Mono<Activity> log(Activity activity) {
        return create(activity);
    }
}
