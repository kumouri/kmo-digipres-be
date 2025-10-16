package com.kumouri.kmodigipresbe.model.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class SingleEmailCommunicationDTO implements CommunicationDTO {
    private String to;
    private String from;
    private String subject;
    private String body;
}
