package com.kumouri.kmodigipresbe.config;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

@Configuration
public class AngusConfig {
    @Bean
    public Session angusSession() {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", "smtp.protonmail.ch");
        props.put("mail.smtp.port", "587");
        props.put("mail.smtp.auth.mechanisms", "LOGIN");
        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication("info@kmodigitalpresence.com", "D2K2CTALX4RLN4RJ");
            }
        });
    }
}
