package com.kumouri.kmodigipresbe.model.contact;

import lombok.Builder;

@Builder(toBuilder = true)
public record PostalAddress(
        String street,
        String city,
        String region,
        String postalCode,
        String country,
        String label
) {
}
