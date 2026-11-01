package io.mosip.registration.test.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assume.assumeNotNull;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.mosip.registration.api.liveness.LivenessFacade;
import io.mosip.registration.api.liveness.LivenessProgress;
import io.mosip.registration.api.liveness.LivenessSessionHandle;
import io.mosip.registration.service.liveness.SajeevLivenessProvider;

public class SajeevLivenessProviderTest {

	private static LivenessFacade facadeWith(SajeevLivenessProvider p) {
		LivenessFacade f = new LivenessFacade();
		ReflectionTestUtils.setField(f, "sessionProviders", Collections.singletonList(p));
		return f;
	}

	@Test
	public void test_engine_that_cannot_load_models_fails_closed() {
		String before = System.getProperty("mosip.registration.liveness.model.dir");
		System.setProperty("mosip.registration.liveness.model.dir", "/definitely/not/here");
		try {
			SajeevLivenessProvider p = new SajeevLivenessProvider();
			LivenessSessionHandle h = facadeWith(p).openSession("RESIDENT");
			assertEquals(LivenessProgress.Phase.UNAVAILABLE, h.progress().getPhase());
			assertFalse(h.checkCapture(new byte[] { 1 }, 3000).isBound());
			p.shutdown();
		} finally {
			if (before == null) {
				System.clearProperty("mosip.registration.liveness.model.dir");
			} else {
				System.setProperty("mosip.registration.liveness.model.dir", before);
			}
		}
	}

	/** Needs the SAJEEV models (-Dmosip.registration.liveness.model.dir=...) and a face photo (-Dsajeev.face.jpg=...). */
	@Test
	public void test_a_photo_of_a_face_is_never_live_through_the_real_engine() throws Exception {
		String models = System.getProperty("mosip.registration.liveness.model.dir");
		String photo = System.getProperty("sajeev.face.jpg");
		assumeNotNull(models, photo);
		byte[] jpg = Files.readAllBytes(Paths.get(photo));
		SajeevLivenessProvider p = new SajeevLivenessProvider();
		try {
			LivenessSessionHandle h = facadeWith(p).openSession("RESIDENT");
			long end = System.currentTimeMillis() + 40_000;
			while (!h.progress().isFinished() && System.currentTimeMillis() < end) {
				h.onFrame(jpg);
				Thread.sleep(100);
			}
			assertNotEquals("a still photo became LIVE", LivenessProgress.Phase.LIVE, h.progress().getPhase());
			assertFalse(h.checkCapture(jpg, 3000).isBound());
			h.close();
		} finally {
			p.shutdown();
		}
	}
}
