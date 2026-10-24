package io.mosip.registration.test.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.Properties;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.mosip.registration.api.liveness.LivenessCaptureCheck;
import io.mosip.registration.api.liveness.LivenessFacade;
import io.mosip.registration.api.liveness.LivenessProgress;
import io.mosip.registration.api.liveness.LivenessSessionHandle;
import io.mosip.registration.api.liveness.LivenessSessionProvider;
import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.constants.Components;
import io.mosip.registration.service.liveness.LivenessCaptureCoordinator;
import io.mosip.registration.service.liveness.LivenessClientConfig;

public class LivenessClientIntegrationTest {

	@Test
	public void test_config_defaults_apply_when_server_values_absent() {
		// the application map is not initialised in this test, which is the same as "server sent nothing"
		assertTrue(LivenessClientConfig.enabled());
		assertEquals(3, LivenessClientConfig.maxRetries());
		assertEquals(45_000L, LivenessClientConfig.sessionTimeoutMillis());
	}

	@Test
	public void test_messages_present_in_en_fr_ar() throws Exception {
		for (String lang : new String[] { "en", "fr", "ar" }) {
			Properties p = new Properties();
			try (InputStreamReader r = new InputStreamReader(
					new FileInputStream("../registration-client/src/main/resources/messages_" + lang + ".properties"), "UTF-8")) {
				p.load(r);
			}
			for (String key : new String[] { "LIVENESS_FAILURE", "LIVENESS_ENGINE_UNAVAILABLE", "LIVENESS_RETRIES_EXHAUSTED" }) {
				String v = p.getProperty(key);
				assertTrue(lang + " is missing " + key, v != null && v.trim().length() > 5);
			}
		}
	}

	@Test
	public void test_audit_events_written_through_audit_factory() {
		AuditManagerService audit = mock(AuditManagerService.class);
		LivenessFacade f = new LivenessFacade();
		LivenessSessionProvider p = new LivenessSessionProvider() {
			public String getServiceName() {
				return "fake";
			}

			public LivenessSessionHandle open(String w) {
				return new LivenessSessionHandle() {
					public void onFrame(byte[] j) {
					}

					public LivenessProgress progress() {
						return new LivenessProgress(LivenessProgress.Phase.LIVE, null, null, "x");
					}

					public LivenessCaptureCheck checkCapture(byte[] i, long t) {
						return new LivenessCaptureCheck(true, "");
					}

					public void close() {
					}
				};
			}
		};
		ReflectionTestUtils.setField(f, "sessionProviders", Collections.singletonList(p));
		LivenessCaptureCoordinator c = new LivenessCaptureCoordinator(f);
		c.setAuditManager(audit);
		c.begin("OPERATOR");
		c.pollBeforeCapture();
		verify(audit, atLeastOnce()).audit(eq(AuditEvent.REG_LIVENESS_START), eq(Components.REG_BIOMETRICS), anyString(), anyString());
		verify(audit, atLeastOnce()).audit(eq(AuditEvent.REG_LIVENESS_VERDICT), eq(Components.REG_BIOMETRICS), anyString(), anyString());
		assertEquals("REG-LIV-001", AuditEvent.REG_LIVENESS_START.getId());
	}
}
