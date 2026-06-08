package com.kumouri.kmodigipresbe.module.chairfill.model;

/**
 * No-show risk tier for an upcoming salon {@link com.kumouri.kmodigipresbe.module.salonspa.model.Booking}.
 * The ChairFill (CF-1) analogue of the lead-scorer's HOT/WARM/COLD — salon semantics
 * are LOW/MEDIUM/HIGH probability of the client not showing up.
 *
 * <p>Held as a string {@code riskTier} on {@link NoShowRisk} (mirroring {@code LeadScore.tier})
 * so the embedded value serializes the same way the lead-score embed does on Contact.
 */
public enum NoShowRiskTier {
    LOW,
    MEDIUM,
    HIGH
}
