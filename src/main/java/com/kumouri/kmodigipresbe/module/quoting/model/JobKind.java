package com.kumouri.kmodigipresbe.module.quoting.model;

/**
 * T8 (Home Services "QuoteNow") — whether a {@link PriceBookLineItem} prices a REPAIR of an existing
 * unit or a REPLACE (new install). A price book typically carries both a repair line and a replace
 * line per equipment type; the repair-vs-replace reasoner compares the two.
 */
public enum JobKind {
    REPAIR,
    REPLACE
}
