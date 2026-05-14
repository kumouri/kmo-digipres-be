package com.kumouri.kmodigipresbe.model.contact;

import lombok.Builder;

/**
 * Phone-channel binding for outbound SMS. Sibling of {@link EmailContact} —
 * a value type, not a {@code @Document}.
 */
@Builder(toBuilder = true)
public record PhoneContact(String e164) {
}
