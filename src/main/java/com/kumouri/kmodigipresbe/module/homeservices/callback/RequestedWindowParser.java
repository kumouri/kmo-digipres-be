package com.kumouri.kmodigipresbe.module.homeservices.callback;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T5 (Home Services "Instant Callback") — a <strong>pure, static, defensive</strong> best-effort parser of
 * the caller's free-text callback window (the words the caller used) into an approximate {@link Instant}.
 * Used only to set {@link CallbackRequest#getRequestedAt()} for display / secondary ordering — the raw
 * phrase is always kept in {@code requestedWindowText}, so an unparseable phrase loses nothing.
 *
 * <p><strong>Never throws, never invents:</strong> an unrecognized phrase returns {@code null}. AI is
 * triage, not truth — the dispatcher always sees the caller's exact words.
 *
 * <p>Handles the common cases only (no NLP dependency): "now"/"asap" → now; "in N min/hour(s)" →
 * now + N; a bare clock time ("2pm", "at 5:30") → today if still future, else tomorrow. Anything else
 * (e.g. "tomorrow morning") → {@code null} (the text is still shown).
 */
public final class RequestedWindowParser {

    private static final Pattern IN_MINUTES =
            Pattern.compile("\\bin\\s+(\\d{1,3})\\s*(?:min|mins|minute|minutes)\\b");
    private static final Pattern IN_HOURS =
            Pattern.compile("\\bin\\s+(\\d{1,2})\\s*(?:hr|hrs|hour|hours)\\b");
    // e.g. "2pm", "at 5", "5:30 pm", "at 11:15am"
    private static final Pattern CLOCK_TIME =
            Pattern.compile("\\b(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b");

    private RequestedWindowParser() {
    }

    /** Convenience overload using the system UTC clock. */
    public static Instant parse(String text) {
        return parse(text, Clock.systemUTC());
    }

    /**
     * Best-effort parse of {@code text} into an approximate callback instant relative to {@code clock}'s
     * "now". Returns {@code null} when the phrase is blank or not one of the recognized shapes.
     */
    public static Instant parse(String text, Clock clock) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim().toLowerCase();
        Instant now = clock.instant();

        if (t.contains("now") || t.contains("asap") || t.contains("right away") || t.contains("right now")) {
            return now;
        }

        Matcher mins = IN_MINUTES.matcher(t);
        if (mins.find()) {
            return now.plus(Duration.ofMinutes(clampLong(mins.group(1))));
        }
        Matcher hours = IN_HOURS.matcher(t);
        if (hours.find()) {
            return now.plus(Duration.ofHours(clampLong(hours.group(1))));
        }

        Matcher clk = CLOCK_TIME.matcher(t);
        if (clk.find()) {
            return clockToInstant(clk, clock);
        }
        return null;
    }

    private static Instant clockToInstant(Matcher clk, Clock clock) {
        int hour12 = Integer.parseInt(clk.group(1));
        int minute = clk.group(2) == null ? 0 : Integer.parseInt(clk.group(2));
        String ampm = clk.group(3);
        if (hour12 < 1 || hour12 > 12 || minute > 59) {
            return null;
        }
        int hour24 = hour12 % 12;
        if ("pm".equals(ampm)) {
            hour24 += 12;
        }
        ZoneId zone = clock.getZone();
        ZonedDateTime nowZdt = ZonedDateTime.ofInstant(clock.instant(), zone);
        ZonedDateTime candidate = nowZdt.with(LocalTime.of(hour24, minute, 0, 0));
        if (!candidate.isAfter(nowZdt)) {
            candidate = candidate.plusDays(1); // the time has already passed today → tomorrow
        }
        return candidate.toInstant();
    }

    private static long clampLong(String digits) {
        try {
            long v = Long.parseLong(digits);
            return Math.max(0L, Math.min(v, 1440L)); // cap at 24h of minutes / sane upper bound
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
