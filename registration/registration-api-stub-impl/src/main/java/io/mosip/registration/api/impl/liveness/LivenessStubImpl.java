package io.mosip.registration.api.impl.liveness;

import java.util.List;

import org.springframework.stereotype.Component;

import io.mosip.registration.api.liveness.LivenessResult;
import io.mosip.registration.api.liveness.LivenessOutcome;
import io.mosip.registration.api.liveness.LivenessService;

/** Stub liveness service: always UNAVAILABLE. It must never report LIVE (fail closed). */
@Component
public class LivenessStubImpl implements LivenessService {

    @Override
    public String getServiceName() {
        return "liveness-stub";
    }

    @Override
    public LivenessResult evaluate(String workflow, List<byte[]> frameJpegs) {
        return new LivenessResult(LivenessOutcome.UNAVAILABLE, "stub_no_engine");
    }
}
