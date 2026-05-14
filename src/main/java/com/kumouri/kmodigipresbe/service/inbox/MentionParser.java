package com.kumouri.kmodigipresbe.service.inbox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts {@code @username} mentions from free text (e.g. {@code Activity.notes}
 * or {@code Activity.body}). Pure, stateless, no I/O — resolving handles to
 * {@code User.id} happens in the caller against a {@link com.kumouri.kmodigipresbe.repository.UserRepository}.
 *
 * <h2>Recognised shapes</h2>
 * <ul>
 *   <li>{@code @alice} → "alice"</li>
 *   <li>{@code @alice.smith} → "alice.smith"</li>
 *   <li>{@code @alice-smith} → "alice-smith"</li>
 *   <li>{@code @alice_42} → "alice_42"</li>
 * </ul>
 *
 * <h2>Explicitly NOT matched</h2>
 * <ul>
 *   <li>Email addresses — the {@code @} must be at a word boundary, so
 *       {@code alice@example.com} does not produce a mention "example".</li>
 *   <li>Standalone {@code @} not followed by a handle character.</li>
 * </ul>
 *
 * <p>Returned list preserves insertion order with duplicates dropped.
 */
public final class MentionParser {

    private static final Pattern PATTERN = Pattern.compile("(?<![A-Za-z0-9._-])@([A-Za-z][A-Za-z0-9._-]{0,63})");

    private MentionParser() {
    }

    public static List<String> extract(String text) {
        if (text == null || text.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = PATTERN.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return new ArrayList<>(out);
    }
}
