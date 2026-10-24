package io.mosip.registration.service.liveness;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.mosip.registration.api.liveness.LivenessCaptureCheck;
import io.mosip.registration.api.liveness.LivenessProgress;
import io.mosip.registration.api.liveness.LivenessSessionHandle;
import io.mosip.registration.api.liveness.LivenessSessionProvider;
import io.sajeev.api.ChallengeType;
import io.sajeev.api.ErrorState;
import io.sajeev.api.Frame;
import io.sajeev.api.Verdict;
import io.sajeev.api.Workflow;
import io.sajeev.audit.AuditLog;
import io.sajeev.binding.Binding;
import io.sajeev.engine.EngineModels;
import io.sajeev.guidance.GuidanceKeys;
import io.sajeev.engine.EngineSession;
import io.sajeev.engine.EngineSettings;
import io.sajeev.engine.LivenessEngine;
import io.sajeev.metrics.LivenessMetrics;
import io.sajeev.policy.Config;

/**
 * Connects the SAJEEV engine to the client's liveness SPI. The models load once, on the first session, from the
 * directory named by the system property {@code mosip.registration.liveness.model.dir} (default {@code ./models}).
 * If the models cannot load, every session is UNAVAILABLE (fail closed).
 */
@Component
public class SajeevLivenessProvider implements LivenessSessionProvider {

	private static final Logger LOGGER = LoggerFactory.getLogger(SajeevLivenessProvider.class);

	private LivenessEngine engine;
	private boolean loadFailed;

	@Override
	public String getServiceName() {
		return "SAJEEV";
	}

	@Override
	public LivenessSessionHandle open(String workflow) {
		LivenessEngine e = engine();
		if (e == null) {
			throw new IllegalStateException("liveness engine not available");
		}
		return new Handle(e.start(Workflow.valueOf(workflow)));
	}

	private synchronized LivenessEngine engine() {
		if (engine == null && !loadFailed) {
			try {
				Path dir = Paths.get(System.getProperty("mosip.registration.liveness.model.dir", "models"));
				// Models load only after the signed manifest verifies. Turning that off is an explicit, logged decision for development.
				EngineModels models;
				if ("false".equalsIgnoreCase(System.getProperty("mosip.registration.liveness.model.verify"))
						&& "true".equalsIgnoreCase(System.getProperty("mosip.registration.liveness.dev"))) {
					LOGGER.warn("Model signature verification is switched OFF by mosip.registration.liveness.model.verify=false");
					models = new EngineModels(dir);
				} else {
					// a folder managed by ModelStore has a CURRENT file naming the verified version in use
					java.nio.file.Path inUse = java.nio.file.Files.isRegularFile(dir.resolve("CURRENT"))
							? new io.sajeev.model.ModelStore(dir, EngineModels.bundledTrustRoot()).currentDir() : dir;
					models = EngineModels.loadVerified(inUse, EngineModels.bundledTrustRoot(), 0);
				}
				engine = new LivenessEngine(models, loadConfig(), EngineSettings.defaults(),
						new AuditLog(System::currentTimeMillis), new LivenessMetrics());
			} catch (Exception | LinkageError ex) {
				LOGGER.error("The liveness engine could not start; every liveness check will be refused", ex);
				loadFailed = true;
			}
		}
		return engine;
	}

	/** The client's liveness settings; a bad value is logged and the defaults are used, so liveness is never switched off by a typo. */
	private static Config loadConfig() throws Config.ConfigException {
		try {
			return Config.load(LivenessClientConfig.engineProperties());
		} catch (Config.ConfigException | RuntimeException ex) {
			LOGGER.error("Invalid liveness configuration; using the defaults", ex);
			return Config.load(new Properties());
		}
	}

	@PreDestroy
	public synchronized void shutdown() {
		if (engine != null) {
			engine.close();
			engine = null;
		}
	}

	/** Adapts the engine session to the client's SPI types. */
	static final class Handle implements LivenessSessionHandle {
		private final EngineSession session;
		private final AtomicLong sequence = new AtomicLong();

		Handle(EngineSession session) {
			this.session = session;
		}

		@Override
		public void onFrame(byte[] jpeg) {
			session.onFrame(new Frame(jpeg, System.nanoTime(), sequence.getAndIncrement()));
		}

		@Override
		public LivenessProgress progress() {
			Verdict v = session.verdict();
			if (v == null) {
				ChallengeType c = session.currentChallenge();
				ErrorState g = session.liveGuidance();
				return new LivenessProgress(LivenessProgress.Phase.RUNNING, c == null ? null : challengeKey(c),
						g == null ? "passive.checking" : GuidanceKeys.keyFor(g), "running");
			}
			switch (v.tier()) {
			case LIVE_PASSIVE:
			case LIVE_ACTIVE:
				return new LivenessProgress(LivenessProgress.Phase.LIVE, null, null, v.reason());
			case ATTACK_SUSPECTED:
			case ACTIVE_REQUIRED:
			case LIMITED:
				return new LivenessProgress(LivenessProgress.Phase.NOT_LIVE, null,
						v.error().isPresent() ? GuidanceKeys.keyFor(v.error().get()) : "failure.liveness", v.reason());
			default:
				return new LivenessProgress(LivenessProgress.Phase.UNAVAILABLE, null,
						v.error().isPresent() ? GuidanceKeys.keyFor(v.error().get()) : "error.engine", v.reason());
			}
		}

		/** The guidance key of a challenge, as in the engine's message bundles (en, fr, ar). */
		static String challengeKey(ChallengeType c) {
			switch (c) {
			case TURN_HEAD_LEFT:
				return "challenge.turn_left";
			case TURN_HEAD_RIGHT:
				return "challenge.turn_right";
			default:
				return "challenge." + c.name().toLowerCase();
			}
		}

		@Override
		public LivenessCaptureCheck checkCapture(byte[] capturedImage, long captureTimeoutMillis) {
			Binding.Result r = session.checkCapture(capturedImage, System.nanoTime(), captureTimeoutMillis);
			return new LivenessCaptureCheck(r.verdict() == Binding.Verdict.BOUND, String.join(",", r.failedChecks()));
		}

		@Override
		public void close() {
			session.abort();
		}
	}
}
