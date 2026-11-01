package io.mosip.registration.api.liveness;

/** Result of a liveness check. Fail closed: only LIVE means the face may be accepted. */
public enum LivenessOutcome {
    /** The face passed liveness and PAD. */
    LIVE,
    /** A presentation attack or a failed check. Capture is refused. */
    NOT_LIVE,
    /** No engine is available or it failed. Never treated as LIVE. */
    UNAVAILABLE
}
