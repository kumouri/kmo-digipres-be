package com.kumouri.kmodigipresbe.model.communication;

/**
 * Per-message engagement outcomes Postmark reports back via webhook. See
 * {@code EmailEngagement#event}.
 */
public enum EmailEngagementEvent {
    OPEN,
    CLICK,
    BOUNCE,
    SPAM,
    DELIVERED
}
