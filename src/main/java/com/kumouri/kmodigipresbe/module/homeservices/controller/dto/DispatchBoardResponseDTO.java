package com.kumouri.kmodigipresbe.module.homeservices.controller.dto;

import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response payload for {@code GET /home-services/dispatch}. Groups a day's
 * {@link WorkOrder}s by technician, then by hour slot (rounded down from
 * {@code scheduledStart}), with the work orders inside each slot pre-sorted by
 * the {@link com.kumouri.kmodigipresbe.module.homeservices.service.DispatchBoardService}
 * greedy nearest-neighbor routing pass.
 *
 * <p>{@code technicianUserId} may be {@code null} for work orders with no
 * assigned technician — those collapse into a single "unassigned" group so the
 * UI can render them alongside the staffed columns.
 */
public record DispatchBoardResponseDTO(
        LocalDate date,
        List<TechnicianDayDTO> technicians) {

    public record TechnicianDayDTO(
            UUID technicianUserId,
            List<SlotDTO> slots) {
    }

    public record SlotDTO(
            int startHour,
            List<WorkOrder> workOrders) {
    }
}
