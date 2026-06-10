package com.kumouri.kmodigipresbe.module.realestate.listingprep.controller;

import java.time.LocalDate;

/**
 * Real Estate Concierge (T10 — Listing Prep Studio) — the (all-optional) generate request body.
 *
 * @param startDate    the calendar's day-zero (nullable → the service defaults to the next Monday)
 * @param postsPerWeek the requested posts-per-week target (nullable → the configured default)
 */
public record ListingPrepGenerateRequest(LocalDate startDate, Integer postsPerWeek) {
}
