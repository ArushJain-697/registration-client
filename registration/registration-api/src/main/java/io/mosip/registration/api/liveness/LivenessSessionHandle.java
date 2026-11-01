package io.mosip.registration.api.liveness;

/**
 * One liveness check for one person at one step. Single use. {@link #onFrame} never blocks the caller.
 */
public interface LivenessSessionHandle extends AutoCloseable {

	/** @param jpeg the next frame from the device stream */
	void onFrame(byte[] jpeg);

	/** @return the current progress; never null */
	LivenessProgress progress();

	/**
	 * Check the captured face against what was seen live.
	 * @param capturedImage the captured face as an image the engine can decode (JPEG or PNG)
	 * @param captureTimeoutMillis the device's capture timeout
	 * @return the check; bound is false if the session was not live
	 */
	LivenessCaptureCheck checkCapture(byte[] capturedImage, long captureTimeoutMillis);

	/** Stop and release everything. Safe to call more than once. */
	@Override
	void close();
}
