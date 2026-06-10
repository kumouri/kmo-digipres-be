package com.kumouri.kmodigipresbe.service.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security fix BE-06 — per-account lockout counter unit test ({@link LoginAttemptTracker}).
 * Pure (no Mongo / Docker).
 */
class LoginAttemptTrackerTest {

    @Test
    void locksAfterMaxFailures() {
        LoginAttemptTracker tracker = new LoginAttemptTracker();
        String email = "victim@example.com";

        for (int i = 0; i < LoginAttemptTracker.MAX_FAILURES - 1; i++) {
            tracker.recordFailure(email);
            assertThat(tracker.isLocked(email))
                    .as("not locked before the threshold (failure %d)", i + 1)
                    .isFalse();
        }
        tracker.recordFailure(email); // the MAX_FAILURES-th
        assertThat(tracker.isLocked(email)).isTrue();
    }

    @Test
    void successClearsTheCounter() {
        LoginAttemptTracker tracker = new LoginAttemptTracker();
        String email = "user@example.com";
        for (int i = 0; i < LoginAttemptTracker.MAX_FAILURES; i++) {
            tracker.recordFailure(email);
        }
        assertThat(tracker.isLocked(email)).isTrue();

        tracker.recordSuccess(email);
        assertThat(tracker.isLocked(email)).isFalse();
    }

    @Test
    void distinctAccountsAreIndependent() {
        LoginAttemptTracker tracker = new LoginAttemptTracker();
        for (int i = 0; i < LoginAttemptTracker.MAX_FAILURES; i++) {
            tracker.recordFailure("a@example.com");
        }
        assertThat(tracker.isLocked("a@example.com")).isTrue();
        assertThat(tracker.isLocked("b@example.com")).isFalse();
    }

    @Test
    void unseenAccountIsNotLocked() {
        LoginAttemptTracker tracker = new LoginAttemptTracker();
        assertThat(tracker.isLocked("never-tried@example.com")).isFalse();
    }
}
