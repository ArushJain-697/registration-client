package io.mosip.registration.api.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

public class LivenessFacadeTest {

    @Test
    public void test_facade_delegates_to_registered_service() {
        LivenessFacade f = new LivenessFacade();
        LivenessService svc = new LivenessService() {
            public String getServiceName() { return "fake"; }
            public LivenessResult evaluate(String w, List<byte[]> frames) {
                return new LivenessResult(LivenessOutcome.LIVE, "fake_live");
            }
        };
        ReflectionTestUtils.setField(f, "livenessServices", Collections.singletonList(svc));
        assertEquals(LivenessOutcome.LIVE, f.evaluate("RESIDENT", Collections.emptyList()).getOutcome());
    }

    @Test
    public void test_no_service_fails_closed() {
        LivenessFacade f = new LivenessFacade();
        LivenessResult r = f.evaluate("RESIDENT", Collections.emptyList());
        assertEquals(LivenessOutcome.UNAVAILABLE, r.getOutcome());
        assertFalse(r.isLive());
    }
}
