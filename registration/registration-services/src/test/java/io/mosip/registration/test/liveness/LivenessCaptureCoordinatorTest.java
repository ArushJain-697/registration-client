package io.mosip.registration.test.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.mosip.registration.api.liveness.LivenessCaptureCheck;
import io.mosip.registration.api.liveness.LivenessFacade;
import io.mosip.registration.api.liveness.LivenessProgress;
import io.mosip.registration.api.liveness.LivenessProgress.Phase;
import io.mosip.registration.api.liveness.LivenessSessionHandle;
import io.mosip.registration.api.liveness.LivenessSessionProvider;
import io.mosip.registration.service.liveness.LivenessCaptureCoordinator;
import io.mosip.registration.service.liveness.LivenessCaptureCoordinator.Gate;

public class LivenessCaptureCoordinatorTest {

	private static final byte[] NOT_AN_IMAGE = { 1, 2, 3 };

	/** A session whose phase and binding answer are set by the test. */
	private static final class Fake implements LivenessSessionHandle {
		Phase phase;
		boolean bound;
		int frames;
		boolean closed;

		Fake(Phase phase, boolean bound) {
			this.phase = phase;
			this.bound = bound;
		}

		public void onFrame(byte[] jpeg) {
			frames++;
		}

		public LivenessProgress progress() {
			return new LivenessProgress(phase, null, null, "fake");
		}

		public LivenessCaptureCheck checkCapture(byte[] image, long timeout) {
			return new LivenessCaptureCheck(bound, bound ? "" : "embedding");
		}

		public void close() {
			closed = true;
		}
	}

	private static LivenessCaptureCoordinator with(Fake fake) {
		LivenessFacade f = new LivenessFacade();
		LivenessSessionProvider p = new LivenessSessionProvider() {
			public String getServiceName() {
				return "fake";
			}

			public LivenessSessionHandle open(String w) {
				return fake;
			}
		};
		ReflectionTestUtils.setField(f, "sessionProviders", Collections.singletonList(p));
		LivenessCaptureCoordinator c = new LivenessCaptureCoordinator(f);
		c.begin("RESIDENT");
		return c;
	}

	/** A real PNG so that the capture decodes; the Fake decides the binding. */
	private static byte[] png() throws Exception {
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(img, "png", o);
		return o.toByteArray();
	}

	@Test
	public void test_capture_never_stored_without_binding() throws Exception {
		assertEquals(Gate.REFUSE_UNBOUND, with(new Fake(Phase.LIVE, false)).decideStore(png(), 3000));
		assertEquals(Gate.STORE, with(new Fake(Phase.LIVE, true)).decideStore(png(), 3000));
		// a capture that cannot even be decoded is never bound
		assertEquals(Gate.REFUSE_UNBOUND, with(new Fake(Phase.LIVE, true)).decideStore(NOT_AN_IMAGE, 3000));
	}

	@Test
	public void test_non_live_face_never_reaches_registration_dto() throws Exception {
		for (Phase p : new Phase[] { Phase.RUNNING, Phase.NOT_LIVE, Phase.UNAVAILABLE }) {
			// even if the binding check would say yes, a session that is not LIVE never stores
			Gate g = with(new Fake(p, true)).decideStore(png(), 3000);
			assertNotEquals("phase " + p + " was allowed to store", Gate.STORE, g);
		}
		// no session at all (no facade, or begin() never called)
		assertNotEquals(Gate.STORE, new LivenessCaptureCoordinator().decideStore(png(), 3000));
		LivenessCaptureCoordinator c = new LivenessCaptureCoordinator(new LivenessFacade());
		c.begin("RESIDENT"); // no provider: the facade gives an UNAVAILABLE session
		assertEquals(Gate.REFUSE_UNAVAILABLE, c.decideStore(png(), 3000));
		assertEquals(Gate.REFUSE_UNAVAILABLE, c.pollBeforeCapture());
	}

	@Test
	public void test_retry_limit_enforced() {
		LivenessCaptureCoordinator c = with(new Fake(Phase.NOT_LIVE, false));
		c.setMaxRetries(2);
		assertFalse(c.retriesExhausted());
		assertEquals(Gate.REFUSE_NOT_LIVE, c.pollBeforeCapture());
		assertFalse(c.retriesExhausted());
		assertEquals(Gate.REFUSE_NOT_LIVE, c.pollBeforeCapture());
		assertTrue(c.retriesExhausted());
		assertEquals(2, c.failedAttempts());
		c.resetAttempts();
		assertFalse(c.retriesExhausted());
	}

	@Test
	public void test_unavailable_engine_does_not_use_up_a_retry() {
		LivenessCaptureCoordinator c = with(new Fake(Phase.UNAVAILABLE, false));
		assertEquals(Gate.REFUSE_UNAVAILABLE, c.pollBeforeCapture());
		assertEquals(0, c.failedAttempts());
	}

	@Test
	public void test_capture_starts_only_when_live() {
		Fake f = new Fake(Phase.RUNNING, false);
		LivenessCaptureCoordinator c = with(f);
		assertEquals(Gate.WAIT, c.pollBeforeCapture());
		f.phase = Phase.LIVE;
		assertEquals(Gate.START_CAPTURE, c.pollBeforeCapture());
	}

	@Test
	public void test_a_session_with_no_verdict_times_out_closed() throws Exception {
		Fake f = new Fake(Phase.RUNNING, false);
		LivenessCaptureCoordinator c = with(f);
		c.setSessionTimeoutMillis(10);
		Thread.sleep(40);
		assertEquals(Gate.REFUSE_UNAVAILABLE, c.pollBeforeCapture());
		assertTrue("the timed-out session was not closed", f.closed);
	}

	@Test
	public void test_frames_reach_the_session_and_end_releases_it() {
		Fake f = new Fake(Phase.RUNNING, false);
		LivenessCaptureCoordinator c = with(f);
		c.onFrame(NOT_AN_IMAGE);
		c.onFrame(NOT_AN_IMAGE);
		assertEquals(2, f.frames);
		c.end();
		c.end(); // twice is fine
		assertTrue(f.closed);
		c.onFrame(NOT_AN_IMAGE); // after end: ignored
		assertEquals(2, f.frames);
	}

	/** The coordinator does not know online from offline: the same refusal holds for both, for operator and supervisor. */
	private void blockedWhenLivenessFails(String workflow) throws Exception {
		final String[] opened = new String[1];
		LivenessFacade f = new LivenessFacade();
		LivenessSessionProvider p = new LivenessSessionProvider() {
			public String getServiceName() {
				return "fake";
			}

			public LivenessSessionHandle open(String w) {
				opened[0] = w;
				return new Fake(Phase.NOT_LIVE, true);
			}
		};
		ReflectionTestUtils.setField(f, "sessionProviders", Collections.singletonList(p));
		LivenessCaptureCoordinator c = new LivenessCaptureCoordinator(f);
		c.begin(workflow);
		assertEquals(workflow, opened[0]);
		assertEquals(Gate.REFUSE_NOT_LIVE, c.pollBeforeCapture());
		assertNotEquals(Gate.STORE, c.decideStore(png(), 3000));
	}

	@Test
	public void test_operator_login_blocked_when_liveness_fails_offline() throws Exception {
		blockedWhenLivenessFails("OPERATOR");
	}

	@Test
	public void test_operator_login_blocked_when_liveness_fails_online() throws Exception {
		blockedWhenLivenessFails("OPERATOR");
	}

	@Test
	public void test_supervisor_approval_blocked_when_liveness_fails() throws Exception {
		blockedWhenLivenessFails("SUPERVISOR");
	}

	@Test
	public void test_failures_of_one_workflow_do_not_lock_another() {
		Fake f = new Fake(Phase.NOT_LIVE, false);
		LivenessCaptureCoordinator c = with(f);
		c.begin("OPERATOR");
		c.setMaxRetries(1);
		assertEquals(Gate.REFUSE_NOT_LIVE, c.pollBeforeCapture());
		assertTrue(c.retriesExhausted("OPERATOR") || c.retriesExhausted());
		assertFalse("an operator's failures locked resident capture", c.retriesExhausted("RESIDENT"));
		c.resetAttempts("OPERATOR");
		assertEquals(0, c.failedAttempts());
	}

	@Test
	public void test_one_live_verdict_stores_one_capture() throws Exception {
		Fake f = new Fake(Phase.LIVE, true);
		LivenessCaptureCoordinator c = with(f);
		assertEquals(Gate.STORE, c.decideStore(png(), 3000));
		assertTrue(f.closed);
		assertEquals("a second capture was stored on the same live verdict", Gate.REFUSE_UNAVAILABLE, c.decideStore(png(), 3000));
	}

	@Test
	public void test_failures_survive_a_new_popup() {
		// a new popup is a new begin() on the same workflow: the failed attempts must still count
		Fake f = new Fake(Phase.NOT_LIVE, false);
		LivenessCaptureCoordinator c = with(f);
		assertEquals(Gate.REFUSE_NOT_LIVE, c.pollBeforeCapture());
		assertEquals(Gate.REFUSE_NOT_LIVE, c.pollBeforeCapture());
		c.begin("RESIDENT"); // popup closed and reopened
		assertEquals(Gate.REFUSE_NOT_LIVE, c.pollBeforeCapture());
		assertTrue("three failures over three popups did not exhaust the retries", c.retriesExhausted("RESIDENT"));
		c.begin("RESIDENT");
		assertTrue("reopening the popup reset the counter", c.retriesExhausted("RESIDENT"));
	}

	@Test
	public void test_a_store_resets_only_its_own_workflow_counter() throws Exception {
		Fake bad = new Fake(Phase.NOT_LIVE, false);
		LivenessCaptureCoordinator c = with(bad);
		c.pollBeforeCapture();
		c.begin("OPERATOR");
		c.pollBeforeCapture();
		assertEquals(1, c.failedAttempts());
		bad.phase = Phase.LIVE;
		bad.bound = true;
		assertEquals(Gate.STORE, c.decideStore(png(), 3000));
		assertEquals("a store did not reset its workflow", 0, c.failedAttempts());
		c.begin("RESIDENT");
		assertEquals("a store of OPERATOR reset RESIDENT", 1, c.failedAttempts());
	}

	@Test
	public void test_missing_capture_list_is_refused_and_counted() {
		Fake f = new Fake(Phase.LIVE, true);
		LivenessCaptureCoordinator c = with(f);
		assertEquals(Gate.REFUSE_UNBOUND, c.decideStoreFor(null, 3000));
		assertEquals(Gate.REFUSE_UNBOUND, c.decideStoreFor(java.util.Collections.emptyList(), 3000));
		assertEquals(2, c.failedAttempts());
		c.end();
		assertEquals(Gate.REFUSE_UNAVAILABLE, c.decideStoreFor(null, 3000));
	}

	@Test
	public void test_switched_off_liveness_writes_an_audit_event_and_a_warn() {
		io.mosip.registration.audit.AuditManagerService audit = org.mockito.Mockito.mock(io.mosip.registration.audit.AuditManagerService.class);
		LivenessCaptureCoordinator c = new LivenessCaptureCoordinator();
		c.setAuditManager(audit);
		c.recordDisabled("RESIDENT", "enabled=false");
		org.mockito.Mockito.verify(audit, org.mockito.Mockito.times(1)).audit(
				org.mockito.Matchers.eq(io.mosip.registration.constants.AuditEvent.REG_LIVENESS_DISABLED),
				org.mockito.Matchers.any(io.mosip.registration.constants.Components.class), org.mockito.Matchers.eq("RESIDENT"),
				org.mockito.Matchers.anyString());
	}

	@Test
	public void test_audit_ids_in_the_liveness_block_are_unique() {
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (io.mosip.registration.constants.AuditEvent e : io.mosip.registration.constants.AuditEvent.values()) {
			if (e.getId().startsWith("REG-LIV-")) {
				assertTrue("duplicate id " + e.getId(), seen.add(e.getId()));
			}
		}
		assertTrue(seen.contains("REG-LIV-007"));
	}
}
