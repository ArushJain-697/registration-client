package io.mosip.registration.api.impl.liveness;

import static org.junit.Assert.assertFalse;

import java.util.Collections;

import org.junit.Test;

public class LivenessStubImplTest {

    @Test
    public void test_stub_never_reports_live() {
        LivenessStubImpl stub = new LivenessStubImpl();
        assertFalse(stub.evaluate("RESIDENT", Collections.emptyList()).isLive());
        assertFalse(stub.evaluate("OPERATOR", Collections.singletonList(new byte[] {1})).isLive());
    }
}
