package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.util.RequestMapper;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationDTO;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
@RestController
@RequestMapping("/communication")
public class CommunicationController {
    private final EmailService emailService;
    private final RequestMapper requestMapper;

    @PostMapping("/singleEmail")
    public Mono<Boolean> sendEmail(@RequestBody SingleEmailCommunicationDTO request) {
        SingleEmailCommunicationRequest singleEmailCommunicationRequest = requestMapper.toSingleEmailCommunicationRequest(request);
        return emailService.sendSingleEmail(singleEmailCommunicationRequest);
    }
}
