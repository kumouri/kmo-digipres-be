package com.kumouri.kmodigipresbe.service;

import com.kumouri.kmodigipresbe.model.request.EmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmailService implements ContactService<EmailCommunicationRequest> {
    private final Session emailSession;

    public boolean sendSingleEmail(SingleEmailCommunicationRequest request) {
        Message message = new MimeMessage(emailSession);
        try {
            message.setRecipient(Message.RecipientType.TO, request.to().email());
            message.setFrom(request.from().email());
            message.setSubject(request.subject());

            MimeBodyPart messageBodyPart = new MimeBodyPart();
            messageBodyPart.setContent(request.body(), "text/html; charset=utf-8");

            Multipart multipart = new MimeMultipart();
            multipart.addBodyPart(messageBodyPart);

            message.setContent(multipart);

            Transport.send(message);

            return true;
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean initiateContact(EmailCommunicationRequest request) {
        if (request instanceof SingleEmailCommunicationRequest) {
            return sendSingleEmail((SingleEmailCommunicationRequest) request);
        }
        return false;
    }
}
