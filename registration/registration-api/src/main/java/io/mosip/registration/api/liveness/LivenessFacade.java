package io.mosip.registration.api.liveness;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Entry point for the registration client. Delegates to the first registered {@link LivenessService}.
 * Fails closed: no service, or a service that throws, gives UNAVAILABLE, never LIVE.
 */
@Component
public class LivenessFacade {

    private static final Logger LOGGER = LoggerFactory.getLogger(LivenessFacade.class);

    @Autowired(required = false)
    private List<LivenessService> livenessServices;

    @Autowired(required = false)
    private List<LivenessSessionProvider> sessionProviders;

    public LivenessResult evaluate(String workflow, List<byte[]> frameJpegs) {
        if (livenessServices == null || livenessServices.isEmpty()) {
            LOGGER.warn("No liveness service registered: failing closed");
            return new LivenessResult(LivenessOutcome.UNAVAILABLE, "no_liveness_service");
        }
        LivenessService service = livenessServices.get(0);
        try {
            LivenessResult r = service.evaluate(workflow, frameJpegs);
            return r == null ? new LivenessResult(LivenessOutcome.UNAVAILABLE, "null_result") : r;
        } catch (RuntimeException e) {
            LOGGER.error("Liveness service " + service.getServiceName() + " failed", e);
            return new LivenessResult(LivenessOutcome.UNAVAILABLE, "service_error");
        }
    }

    /**
     * Open a liveness session. Fails closed: no provider, or a provider that throws, gives a session that is
     * UNAVAILABLE for its whole life and never binds a capture.
     *
     * @param workflow RESIDENT, OPERATOR or SUPERVISOR
     * @return a session handle; never null
     */
    public LivenessSessionHandle openSession(String workflow) {
        if (sessionProviders == null || sessionProviders.isEmpty()) {
            LOGGER.warn("No liveness session provider registered: failing closed");
            return new UnavailableSession("no_liveness_service");
        }
        LivenessSessionProvider provider = sessionProviders.get(0);
        try {
            LivenessSessionHandle h = provider.open(workflow);
            return h == null ? new UnavailableSession("null_session") : h;
        } catch (RuntimeException e) {
            LOGGER.error("Liveness provider " + provider.getServiceName() + " failed to open a session", e);
            return new UnavailableSession("service_error");
        }
    }

    /** A session that never reaches LIVE. */
    private static final class UnavailableSession implements LivenessSessionHandle {
        private final String reason;

        UnavailableSession(String reason) {
            this.reason = reason;
        }

        @Override
        public void onFrame(byte[] jpeg) {
            // nothing to do: this session can never become live
        }

        @Override
        public LivenessProgress progress() {
            return new LivenessProgress(LivenessProgress.Phase.UNAVAILABLE, null, "liveness.error.engine", reason);
        }

        @Override
        public LivenessCaptureCheck checkCapture(byte[] capturedImage, long captureTimeoutMillis) {
            return new LivenessCaptureCheck(false, reason);
        }

        @Override
        public void close() {
            // nothing held
        }
    }
}
