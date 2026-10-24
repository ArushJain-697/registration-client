package io.mosip.registration.service.liveness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.constants.Components;
import io.mosip.registration.api.liveness.LivenessCaptureCheck;
import io.mosip.registration.api.liveness.LivenessFacade;
import io.mosip.registration.api.liveness.LivenessProgress;
import io.mosip.registration.api.liveness.LivenessSessionHandle;

/**
 * Decides, for the face capture screen, when the capture may start and whether a captured face may be stored.
 * Plain logic with no screen code, so it can be tested without JavaFX.
 *
 * <p>Rules (SAJEEV invariants): a face is stored only if the session reached LIVE and the captured face is bound to the
 * face seen live (no orphan capture); anything else, including a missing or failed engine, refuses (fail closed);
 * failed attempts are counted and stop at the retry limit.</p>
 */
@Component
public class LivenessCaptureCoordinator {

	private static final Logger LOGGER = LoggerFactory.getLogger(LivenessCaptureCoordinator.class);

	/** What the screen should do next. */
	public enum Gate {
		/** Frames are still being checked: keep the stream running and show the guidance. */
		WAIT,
		/** The person is live: start the capture. */
		START_CAPTURE,
		/** The captured face is live and bound: it may be stored. */
		STORE,
		/** Presentation attack, failed challenge or no live proof. The capture is refused and counts as a failed attempt. */
		REFUSE_NOT_LIVE,
		/** The captured face is not the face seen live. Refused, counts as a failed attempt. */
		REFUSE_UNBOUND,
		/** The engine is missing or could not judge. Refused; does not use up a retry. */
		REFUSE_UNAVAILABLE
	}

	@Autowired(required = false)
	private LivenessFacade livenessFacade;

	@Autowired(required = false)
	private AuditManagerService auditFactory;

	private LivenessSessionHandle session;
	private String workflow;
	private long startedMillis;
	private long sessionTimeoutMillis = 45_000;
	private int failedAttempts;
	private int maxRetries = 3;

	public LivenessCaptureCoordinator() {
	}

	/** For tests and for code outside Spring. */
	public LivenessCaptureCoordinator(LivenessFacade facade) {
		this.livenessFacade = facade;
	}

	/** For tests: the audit sink. */
	public void setAuditManager(AuditManagerService audit) {
		this.auditFactory = audit;
	}

	private void audit(AuditEvent event) {
		try {
			if (auditFactory != null) {
				auditFactory.audit(event, Components.REG_BIOMETRICS, workflow == null ? "LIVENESS" : workflow, "workflow");
			}
		} catch (RuntimeException e) {
			LOGGER.error("Writing the liveness audit event failed", e); // an audit failure must never change the decision
		}
	}

	public synchronized void setMaxRetries(int maxRetries) {
		this.maxRetries = Math.max(1, maxRetries);
	}

	public synchronized void setSessionTimeoutMillis(long millis) {
		this.sessionTimeoutMillis = millis;
	}

	/**
	 * Start a liveness session for a new capture attempt. The failed-attempt count is kept across attempts for the
	 * same registration; call {@link #resetAttempts()} when the person or the step changes.
	 *
	 * @param workflow RESIDENT, OPERATOR or SUPERVISOR
	 */
	public synchronized void begin(String workflow) {
		end();
		this.workflow = workflow;
		this.maxRetries = LivenessClientConfig.maxRetries();
		this.sessionTimeoutMillis = LivenessClientConfig.sessionTimeoutMillis();
		this.startedMillis = System.currentTimeMillis();
		this.session = livenessFacade == null ? null : livenessFacade.openSession(workflow);
		audit(AuditEvent.REG_LIVENESS_START);
		if (session == null || session.progress().getPhase() == LivenessProgress.Phase.UNAVAILABLE) {
			audit(AuditEvent.REG_LIVENESS_ENGINE_UNAVAILABLE);
		}
	}

	/** @param jpeg a frame from the device stream; never blocks */
	public void onFrame(byte[] jpeg) {
		LivenessSessionHandle s = session;
		if (s != null) {
			s.onFrame(jpeg);
		}
	}

	/** @return the progress for the screen; UNAVAILABLE when no session is open */
	public synchronized LivenessProgress progress() {
		if (session == null) {
			return new LivenessProgress(LivenessProgress.Phase.UNAVAILABLE, null, "liveness.error.engine", "not_started");
		}
		return session.progress();
	}

	/**
	 * Called while the stream is shown, before the capture starts.
	 * @return WAIT, START_CAPTURE, REFUSE_NOT_LIVE or REFUSE_UNAVAILABLE
	 */
	public synchronized Gate pollBeforeCapture() {
		LivenessProgress p = progress();
		switch (p.getPhase()) {
		case LIVE:
			audit(AuditEvent.REG_LIVENESS_VERDICT);
			return Gate.START_CAPTURE;
		case RUNNING:
			if (System.currentTimeMillis() - startedMillis > sessionTimeoutMillis) {
				LOGGER.warn("Liveness session timed out without a verdict");
				end();
				return Gate.REFUSE_UNAVAILABLE;
			}
			return Gate.WAIT;
		case NOT_LIVE:
			failedAttempts++;
			audit(AuditEvent.REG_LIVENESS_VERDICT);
			return Gate.REFUSE_NOT_LIVE;
		default:
			return Gate.REFUSE_UNAVAILABLE;
		}
	}

	/**
	 * Called in the capture's success handler, BEFORE anything is stored or de-duplicated.
	 *
	 * @param capturedIsoOrImage the captured face as returned by the device
	 * @param captureTimeoutMillis the device capture timeout
	 * @return STORE only if the session is LIVE and the capture is bound to the live face
	 */
	public synchronized Gate decideStore(byte[] capturedIsoOrImage, long captureTimeoutMillis) {
		if (session == null) {
			return Gate.REFUSE_UNAVAILABLE;
		}
		LivenessProgress p = session.progress();
		if (!p.isLive()) {
			if (p.getPhase() == LivenessProgress.Phase.UNAVAILABLE) {
				return Gate.REFUSE_UNAVAILABLE;
			}
			failedAttempts++;
			return Gate.REFUSE_NOT_LIVE;
		}
		byte[] png = CapturedFaceImage.toPng(capturedIsoOrImage);
		LivenessCaptureCheck check = png == null ? new LivenessCaptureCheck(false, "undecodable_capture")
				: session.checkCapture(png, captureTimeoutMillis);
		audit(AuditEvent.REG_LIVENESS_BINDING);
		if (!check.isBound()) {
			failedAttempts++;
			LOGGER.warn("Captured face refused: {}", check.getReason());
			return Gate.REFUSE_UNBOUND;
		}
		failedAttempts = 0;
		return Gate.STORE;
	}

	/** @return true when the retry limit is reached: the screen must stop offering another attempt */
	public synchronized boolean retriesExhausted() {
		return failedAttempts >= maxRetries;
	}

	public synchronized int failedAttempts() {
		return failedAttempts;
	}

	public synchronized void resetAttempts() {
		failedAttempts = 0;
	}

	/** Close the session and release its frames. Safe to call more than once. */
	public synchronized void end() {
		if (session != null) {
			try {
				session.close();
			} catch (RuntimeException e) {
				LOGGER.error("Closing the liveness session failed", e);
			}
			session = null;
		}
	}
}
