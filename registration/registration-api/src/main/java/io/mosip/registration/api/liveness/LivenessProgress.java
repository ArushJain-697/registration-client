package io.mosip.registration.api.liveness;

/**
 * Where a liveness session stands, for the screen to show. Immutable.
 * Only {@link Phase#LIVE} allows the face to be captured; every other phase refuses it (fail closed).
 */
public final class LivenessProgress {

	/** The phase of the session. */
	public enum Phase {
		/** Frames are being checked; the screen shows the guidance or the challenge. */
		RUNNING,
		/** The person is live. The capture may be taken and must then be bound. */
		LIVE,
		/** A presentation attack or a failed challenge. The capture is refused. */
		NOT_LIVE,
		/** The engine is missing or failed, or the face could not be judged. Never treated as live. */
		UNAVAILABLE
	}

	private final Phase phase;
	private final String challenge;
	private final String guidanceKey;
	private final String reason;

	/**
	 * @param phase the phase
	 * @param challenge the challenge to show now (for example BLINK), or null
	 * @param guidanceKey a message key for the person (for example liveness.guidance.no_face), or null
	 * @param reason a short machine-readable reason, for audit
	 */
	public LivenessProgress(Phase phase, String challenge, String guidanceKey, String reason) {
		this.phase = phase;
		this.challenge = challenge;
		this.guidanceKey = guidanceKey;
		this.reason = reason;
	}

	public Phase getPhase() {
		return phase;
	}

	public String getChallenge() {
		return challenge;
	}

	public String getGuidanceKey() {
		return guidanceKey;
	}

	public String getReason() {
		return reason;
	}

	public boolean isLive() {
		return phase == Phase.LIVE;
	}

	public boolean isFinished() {
		return phase != Phase.RUNNING;
	}
}
