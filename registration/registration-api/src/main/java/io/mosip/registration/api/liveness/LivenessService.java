package io.mosip.registration.api.liveness;

import java.util.List;

/**
 * SPI implemented by the liveness engine (or a stub). Registered as a Spring bean and picked up by
 * {@link LivenessFacade}, the same way DocScannerService is picked up.
 */
public interface LivenessService {

    /** @return a name for logs and audit */
    String getServiceName();

    /**
     * @param workflow RESIDENT, OPERATOR or SUPERVISOR
     * @param frameJpegs recent frames, JPEG bytes, oldest first
     * @return the outcome for this window
     */
    LivenessResult evaluate(String workflow, List<byte[]> frameJpegs);
}
