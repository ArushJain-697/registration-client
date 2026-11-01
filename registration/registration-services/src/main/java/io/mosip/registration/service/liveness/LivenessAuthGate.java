package io.mosip.registration.service.liveness;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.registration.api.liveness.LivenessProgress;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.exception.RegBaseCheckedException;

/**
 * The liveness gate for face capture that has no capture screen of its own: face login, packet authentication by
 * operator or supervisor, EOD approval. It runs, in this order: the device preview stream goes into a liveness
 * session until the session is decided, the stream is closed, the device captures, and the captured face must be
 * bound to the face seen live. Any other outcome (no engine, attack, timeout, stream error or end, a device that
 * returns nothing, an unbound face) gives {@code null}: fail closed.
 *
 * <p>The whole check is bounded by the session timeout, however the stream behaves. Call it from a background
 * thread, never from the FX thread. It holds no JavaFX code: the text for the person goes to a {@link LivenessPrompt}.</p>
 */
@Component
public class LivenessAuthGate {

	private static final Logger LOGGER = LoggerFactory.getLogger(LivenessAuthGate.class);

	/** Reads the next JPEG frame from the preview stream; throws at the end of the stream. */
	public interface FrameReader {
		byte[] next(InputStream stream) throws IOException;
	}

	public interface StreamOpener {
		InputStream open() throws RegBaseCheckedException, IOException;
	}

	public interface Capturer {
		List<BiometricsDto> capture() throws RegBaseCheckedException, IOException;
	}

	@Autowired
	private LivenessCaptureCoordinator coordinator;

	@Autowired(required = false)
	private LivenessPrompt prompt;

	private FrameReader reader = new MjpegFrameReader();
	private LongSupplier timeoutMillis = LivenessClientConfig::sessionTimeoutMillis;
	private BooleanSupplier enabled = LivenessClientConfig::enabled;

	public LivenessAuthGate() {
	}

	/** For tests and code outside Spring. */
	public LivenessAuthGate(LivenessCaptureCoordinator coordinator, FrameReader reader, LivenessPrompt prompt,
			LongSupplier timeoutMillis, BooleanSupplier enabled) {
		this.coordinator = coordinator;
		this.reader = reader;
		this.prompt = prompt;
		this.timeoutMillis = timeoutMillis;
		this.enabled = enabled;
	}

	/**
	 * @param workflow OPERATOR or SUPERVISOR
	 * @return the biometrics the device captured, or {@code null} when liveness or binding refused (nothing may be
	 *         authenticated or stored then). With liveness switched off by configuration the device capture is
	 *         returned as is, after an audit event and a WARN.
	 */
	public List<BiometricsDto> captureLive(String workflow, StreamOpener opener, Capturer capturer)
			throws RegBaseCheckedException, IOException {
		if (!enabled.getAsBoolean()) {
			coordinator.recordDisabled(workflow, "mosip.registration.liveness.enabled=false");
			return capturer.capture();
		}
		if (coordinator.retriesExhausted(workflow)) {
			LOGGER.warn("Face capture refused: no liveness attempts left for {}", workflow);
			return null;
		}
		if (!livenessPassed(workflow, opener)) {
			return null;
		}
		try {
			List<BiometricsDto> captured = capturer.capture(); // the preview stream is already closed
			LivenessCaptureCoordinator.Gate g = coordinator.decideStoreFor(captured,
					LivenessClientConfig.captureTimeoutMillis());
			if (g != LivenessCaptureCoordinator.Gate.STORE) {
				LOGGER.warn("Face capture refused: captured face not bound to the live face ({})", g);
				return null;
			}
			return captured;
		} finally {
			coordinator.end();
		}
	}

	/** Streams the camera into a session until it is decided or the deadline passes. */
	boolean livenessPassed(String workflow, StreamOpener opener) {
		final long timeout = timeoutMillis.getAsLong();
		final AtomicBoolean stop = new AtomicBoolean();
		ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "LIVENESS_AUTH_STREAM");
			t.setDaemon(true);
			return t;
		});
		InputStream stream = null;
		boolean passed = false;
		try {
			stream = opener.open();
			if (stream == null) {
				return false;
			}
			final InputStream in = stream;
			coordinator.begin(workflow);
			Future<Boolean> run = worker.submit(() -> loop(in, stop, timeout));
			try {
				passed = run.get(timeout, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				LOGGER.warn("Liveness stream check passed its deadline of {} ms", timeout);
			} catch (ExecutionException e) {
				LOGGER.warn("Liveness stream check failed: {}", String.valueOf(e.getCause()));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return passed;
		} catch (Exception e) {
			LOGGER.error("Liveness stream failed", e);
			return false;
		} finally {
			stop.set(true);
			closeQuietly(stream); // also unblocks a read that is waiting on the device
			worker.shutdownNow();
			try {
				worker.awaitTermination(500, TimeUnit.MILLISECONDS); // so that no late prompt text follows the close
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (!passed) {
				coordinator.end();
			}
			if (prompt != null) {
				try {
					prompt.close();
				} catch (RuntimeException e) {
					LOGGER.warn("Closing the liveness prompt failed", e);
				}
			}
		}
	}

	private boolean loop(InputStream in, AtomicBoolean stop, long timeout) throws IOException {
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
		if (in.read() == -1) { // the first byte only proves that the device serves a stream
			return false;
		}
		String lang = language();
		while (!stop.get() && System.nanoTime() < deadline) {
			coordinator.onFrame(reader.next(in)); // EOF or a bad part throws: the check ends
			LivenessProgress p = coordinator.progress();
			if (prompt != null) {
				String key = p.getChallenge() != null ? p.getChallenge() : p.getGuidanceKey();
				prompt.show(LivenessMessages.text(key, lang));
			}
			LivenessCaptureCoordinator.Gate g = coordinator.pollBeforeCapture();
			if (g == LivenessCaptureCoordinator.Gate.START_CAPTURE) {
				return true;
			}
			if (g != LivenessCaptureCoordinator.Gate.WAIT) {
				return false;
			}
		}
		return false;
	}

	private static String language() {
		try {
			String lang = io.mosip.registration.context.ApplicationContext.applicationLanguage();
			return lang == null ? "en" : lang.startsWith("fr") ? "fr" : lang.startsWith("ar") ? "ar" : "en";
		} catch (RuntimeException e) {
			return "en";
		}
	}

	private static void closeQuietly(InputStream s) {
		if (s != null) {
			try {
				s.close();
			} catch (IOException ignored) {
				// closing only
			}
		}
	}
}
