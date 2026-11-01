package io.mosip.registration.api.liveness;

/** Outcome plus a short reason for audit. Immutable. */
public final class LivenessResult {
    private final LivenessOutcome outcome;
    private final String reason;

    public LivenessResult(LivenessOutcome outcome, String reason) {
        this.outcome = outcome;
        this.reason = reason;
    }

    public LivenessOutcome getOutcome() {
        return outcome;
    }

    public String getReason() {
        return reason;
    }

    public boolean isLive() {
        return outcome == LivenessOutcome.LIVE;
    }
}
