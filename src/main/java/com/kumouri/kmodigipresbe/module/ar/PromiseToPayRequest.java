package com.kumouri.kmodigipresbe.module.ar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * "Get Paid" AR-4 — request body for {@code POST /ar/promises} (staff records a
 * promise-to-pay). Validation:
 * <ul>
 *   <li>{@code invoiceId} — required; the invoice must exist in the tenant (4601 / 404);</li>
 *   <li>{@code promisedDate} — required; must be today-or-future (4602 / 400);</li>
 *   <li>{@code promisedAmount} — optional; when supplied must be > 0 (4602 / 400);</li>
 *   <li>{@code note} — optional free text.</li>
 * </ul>
 */
public record PromiseToPayRequest(
        UUID invoiceId,
        LocalDate promisedDate,
        BigDecimal promisedAmount,
        String note
) {}
