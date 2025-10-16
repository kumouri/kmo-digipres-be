package com.kumouri.kmodigipresbe.model.request;

import com.kumouri.kmodigipresbe.model.contact.Contact;

public interface CommunicationRequest {
    Contact to();
    Contact from();
    String subject();
    String body();
}
