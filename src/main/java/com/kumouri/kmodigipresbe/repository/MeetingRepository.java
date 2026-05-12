package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.meeting.Meeting;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

import java.util.UUID;

public interface MeetingRepository extends ReactiveMongoRepository<Meeting, UUID> {
}
