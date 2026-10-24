package io.mosip.registration.api.liveness;

/** Whether the captured face is the face that was seen live. Only {@code bound == true} may be stored. Immutable. */
public final class LivenessCaptureCheck {

	private final boolean bound;
	private final String reason;

	/**
	 * @param bound true only when every binding check passed
	 * @param reason which checks failed, for audit (no biometric data)
	 */
	public LivenessCaptureCheck(boolean bound, String reason) {
		this.bound = bound;
		this.reason = reason;
	}

	public boolean isBound() {
		return bound;
	}

	public String getReason() {
		return reason;
	}
}
