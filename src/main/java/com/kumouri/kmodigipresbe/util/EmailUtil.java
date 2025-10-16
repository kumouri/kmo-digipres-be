package com.kumouri.kmodigipresbe.util;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.stereotype.Component;

@Component
public class EmailUtil {
    public static InternetAddress fromString(String email) {
        try {
            return new InternetAddress(email);
        } catch (AddressException e) {
            throw new DigiPresBeException(e, 0, 400);
        }
    }
}
