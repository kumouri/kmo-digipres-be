package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.request.SingleSmsCommunicationDTO;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/communication")
@RequiredArgsConstructor
public class SmsCommunicationController {

    private final TwilioSmsService sms;

    @PostMapping("/singleSms")
    public Mono<Boolean> sendSms(@Valid @RequestBody SingleSmsCommunicationDTO body) {
        SmsCommunicationRequest req = SmsCommunicationRequest.builder()
                .to(new PhoneContact(body.getTo()))
                .body(body.getBody())
                .build();
        return sms.sendSms(req);
    }
}
