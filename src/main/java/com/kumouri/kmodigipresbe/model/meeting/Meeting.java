package com.kumouri.kmodigipresbe.model.meeting;

import com.kumouri.kmodigipresbe.model.contact.Contact;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Set;

@Document
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Meeting {
    private String name;
    private String description;
    private String location;
    private LocalDateTime start;
    private LocalDateTime end;
    private boolean allDay;

    private Contact organizer;
    private Set<Contact> attendees;
}
