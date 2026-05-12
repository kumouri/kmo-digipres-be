package com.kumouri.kmodigipresbe.config;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

@Configuration
public class AngusConfig {

    @Value("${kmosf.mail.smtp.host}")
    private String host;

    @Value("${kmosf.mail.smtp.port}")
    private String port;

    @Value("${kmosf.mail.smtp.username}")
    private String username;

    @Value("${kmosf.mail.smtp.password}")
    private String password;

    @Bean
    public Session angusSession() {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", port);
        props.put("mail.smtp.auth.mechanisms", "LOGIN");
        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });
    }
}
