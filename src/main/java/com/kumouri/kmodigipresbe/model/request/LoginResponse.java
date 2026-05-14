package com.kumouri.kmodigipresbe.model.request;

import java.util.Set;
import java.util.UUID;

public record LoginResponse(
        String token,
        UUID userId,
        UUID tenantId,
        String email,
        String displayName,
        Set<String> roles) {
}
