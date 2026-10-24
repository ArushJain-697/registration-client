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

    @Test
    public void test_no_session_provider_fails_closed() {
        LivenessFacade f = new LivenessFacade();
        LivenessSessionHandle h = f.openSession("RESIDENT");
        assertEquals(LivenessProgress.Phase.UNAVAILABLE, h.progress().getPhase());
        assertFalse(h.progress().isLive());
        assertFalse(h.checkCapture(new byte[] {1}, 3000).isBound());
    }

    @Test
    public void test_provider_that_throws_fails_closed() {
        LivenessFacade f = new LivenessFacade();
        LivenessSessionProvider bad = new LivenessSessionProvider() {
            public String getServiceName() { return "bad"; }
            public LivenessSessionHandle open(String w) { throw new IllegalStateException("boom"); }
        };
        ReflectionTestUtils.setField(f, "sessionProviders", Collections.singletonList(bad));
        assertEquals(LivenessProgress.Phase.UNAVAILABLE, f.openSession("OPERATOR").progress().getPhase());
    }
}
