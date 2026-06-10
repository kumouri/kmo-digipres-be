package com.kumouri.kmodigipresbe.module.stylermatch.controller.dto;

import com.kumouri.kmodigipresbe.module.stylermatch.model.MatchRequest;
import com.kumouri.kmodigipresbe.module.stylermatch.service.StylerMatchService;

import java.time.Instant;
import java.util.UUID;

/**
 * T12 (Salon "StylerMatch") — the JSON request body for a match submission (public widget + staff desk).
 * A text/attribute match needs no photo, so this is a plain JSON body (NOT multipart) — the T8/T9 photo
 * intakes used {@code getMultipartData}; StylerMatch does not. Every field is optional; the service
 * rejects a fully-empty request with {@code 4481}.
 *
 * @param serviceMenuItemId      the requested service id (the hard-eligibility signal + what accept books)
 * @param styleCategory          the look the client wants (the primary specialty-fit signal)
 * @param length                 requested/own hair length
 * @param texture                requested/own hair texture
 * @param color                  requested/own color
 * @param preferredStaffMemberId an explicit preferred stylist (the largest preference bump)
 * @param contactId              an existing contact id (staff desk); the public path uses phone/email
 * @param slotStart              the start of the slot the client wants (the availability signal)
 * @param slotEnd                the end of the requested slot
 * @param name                   the client's name (for find-or-create)
 * @param phone                  the client's phone (E.164; the booking-link SMS target + find-or-create)
 * @param email                  the client's email (find-or-create)
 * @param notes                  a free-text note
 */
public record StylerMatchRequestBody(
        String serviceMenuItemId,
        String styleCategory,
        String length,
        String texture,
        String color,
        UUID preferredStaffMemberId,
        UUID contactId,
        Instant slotStart,
        Instant slotEnd,
        String name,
        String phone,
        String email,
        String notes) {

    /** The pure scoring inputs carried by this body. */
    public MatchRequest toMatchRequest() {
        return MatchRequest.builder()
                .serviceMenuItemId(serviceMenuItemId)
                .styleCategory(styleCategory)
                .length(length)
                .texture(texture)
                .color(color)
                .preferredStaffMemberId(preferredStaffMemberId)
                .contactId(contactId)
                .slotStart(slotStart)
                .slotEnd(slotEnd)
                .build();
    }

    /** The find-or-create contact inputs carried by this body. */
    public StylerMatchService.ManualContact toManualContact() {
        return new StylerMatchService.ManualContact(name, phone, email, notes);
    }
}
