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
}
