package com.kumouri.kmodigipresbe.util;

import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationDTO;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RequestMapper {
    SingleEmailCommunicationDTO toSingleEmailCommunicationDTO(SingleEmailCommunicationRequest request);

    SingleEmailCommunicationRequest toSingleEmailCommunicationRequest(SingleEmailCommunicationDTO dto);

    default EmailContact toEmailContact(String email) {
        return new EmailContact(email);
    }
}
