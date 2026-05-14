package com.kumouri.kmodigipresbe.model.contact;

import lombok.Builder;

@Builder(toBuilder = true)
public record PhoneNumber(String number, String label) {
}
