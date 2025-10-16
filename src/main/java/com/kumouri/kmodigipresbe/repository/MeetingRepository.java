package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.meeting.Meeting;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.UUID;

public interface MeetingRepository extends MongoRepository<Meeting, UUID> {
}
