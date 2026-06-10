package com.kumouri.kmodigipresbe.module.dispatch.controller.dto;

import com.kumouri.kmodigipresbe.module.dispatch.service.DispatchPlanService;

/**
 * T14 (Home "DispatchIQ") — the {@code POST /dispatch/apply} response: how many work orders were
 * (re)assigned vs already at their target tech (skipped). A re-apply of the same decisions returns
 * {@code applied=0} (every work order is already at its target — the idempotency proof). After applying,
 * the FE re-fetches the EXISTING dispatch board ({@code GET /home-services/dispatch}) to see the committed
 * assignments — T14 does not re-render the board itself.
 *
 * @param applied how many work orders were (re)assigned this call
 * @param skipped how many were already at their target tech (no-op — the idempotency signal)
 */
public record ApplyResponse(int applied, int skipped) {

    public static ApplyResponse from(DispatchPlanService.ApplyResult r) {
        return new ApplyResponse(r.applied(), r.skipped());
    }
}
