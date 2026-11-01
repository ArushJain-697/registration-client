package io.mosip.registration.test.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.mosip.registration.api.liveness.LivenessCaptureCheck;
import io.mosip.registration.api.liveness.LivenessFacade;
import io.mosip.registration.api.liveness.LivenessProgress;
import io.mosip.registration.api.liveness.LivenessProgress.Phase;
import io.mosip.registration.api.liveness.LivenessSessionHandle;
import io.mosip.registration.api.liveness.LivenessSessionProvider;
import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.service.liveness.LivenessAuthGate;
import io.mosip.registration.service.liveness.LivenessAuthGate.FrameReader;
import io.mosip.registration.service.liveness.LivenessCaptureCoordinator;
import io.mosip.registration.service.liveness.LivenessPrompt;

/** The shared gate for face login, operator and supervisor authentication. */
public class LivenessAuthGateTest {

	private static final class Fake implements LivenessSessionHandle {
		volatile Phase phase;
		final boolean bound;
		volatile boolean closed;
		final AtomicInteger frames = new AtomicInteger();

		Fake(Phase phase, boolean bound) {
			this.phase = phase;
			this.bound = bound;
		}

		public void onFrame(byte[] jpeg) {
			frames.incrementAndGet();
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

	private static final class TrackedStream extends ByteArrayInputStream {
		volatile boolean closed;

		TrackedStream() {
			super(new byte[] { 1, 2, 3, 4 });
		}

		@Override
		public void close() throws IOException {
			closed = true;
			super.close();
		}
	}

	private static final class Prompt implements LivenessPrompt {
		final List<String> shown = Collections.synchronizedList(new ArrayList<String>());
		volatile int closes;

		public void show(String text) {
			shown.add(text);
		}

		public void close() {
			closes++;
		}
	}

	private static LivenessCaptureCoordinator coordinator(Fake fake) {
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
		return new LivenessCaptureCoordinator(f);
	}

	private static byte[] png() throws Exception {
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(img, "png", o);
		return o.toByteArray();
	}

	private static List<BiometricsDto> device(byte[] iso) {
		BiometricsDto d = new BiometricsDto();
		d.setAttributeISO(iso);
		return Collections.singletonList(d);
	}

	private static final FrameReader FRAMES = s -> new byte[] { 1 };

	private static LivenessAuthGate gate(LivenessCaptureCoordinator c, FrameReader r, Prompt prompt, long timeoutMs, boolean enabled) {
		return new LivenessAuthGate(c, r, prompt, () -> timeoutMs, () -> enabled);
	}

	@Test
	public void test_face_login_style_capture_returns_the_face_only_when_live_and_bound() throws Exception {
		Fake f = new Fake(Phase.LIVE, true);
		Prompt prompt = new Prompt();
		TrackedStream stream = new TrackedStream();
		AtomicInteger captures = new AtomicInteger();
		List<BiometricsDto> got = gate(coordinator(f), FRAMES, prompt, 5000, true).captureLive("OPERATOR", () -> stream, () -> {
			captures.incrementAndGet();
			assertTrue("the preview stream must be closed before the device captures", stream.closed);
			try {
				return device(png());
			} catch (Exception e) {
				throw new IOException(e);
			}
		});
		assertNotNull(got);
		assertEquals(1, captures.get());
		assertTrue("the session was left open", f.closed);
		assertEquals(1, prompt.closes);
	}

	@Test
	public void test_no_device_capture_and_no_face_when_the_person_is_not_live() throws Exception {
		Fake f = new Fake(Phase.NOT_LIVE, true);
		AtomicInteger captures = new AtomicInteger();
		List<BiometricsDto> got = gate(coordinator(f), FRAMES, new Prompt(), 5000, true).captureLive("OPERATOR",
				TrackedStream::new, () -> {
					captures.incrementAndGet();
					return device(new byte[] { 1 });
				});
		assertNull(got);
		assertEquals("the device captured although liveness failed", 0, captures.get());
		assertTrue(f.closed);
	}

	@Test
	public void test_captured_face_that_is_not_the_live_face_is_refused() throws Exception {
		Fake f = new Fake(Phase.LIVE, false);
		List<BiometricsDto> got = gate(coordinator(f), FRAMES, new Prompt(), 5000, true).captureLive("SUPERVISOR",
				TrackedStream::new, () -> {
					try {
						return device(png());
					} catch (Exception e) {
						throw new IOException(e);
					}
				});
		assertNull(got);
		assertTrue(f.closed);
	}

	@Test
	public void test_a_device_that_returns_nothing_is_refused() throws Exception {
		Fake f = new Fake(Phase.LIVE, true);
		assertNull(gate(coordinator(f), FRAMES, null, 5000, true).captureLive("OPERATOR", TrackedStream::new,
				() -> Collections.<BiometricsDto>emptyList()));
		assertTrue(f.closed);
	}

	@Test(timeout = 10000)
	public void test_stream_that_ends_stops_the_check_and_releases_everything() throws Exception {
		Fake f = new Fake(Phase.RUNNING, true);
		Prompt prompt = new Prompt();
		TrackedStream stream = new TrackedStream();
		FrameReader eof = s -> {
			throw new EOFException("camera unplugged");
		};
		assertNull(gate(coordinator(f), eof, prompt, 5000, true).captureLive("OPERATOR", () -> stream, () -> {
			throw new AssertionError("must not capture");
		}));
		assertTrue("stream not closed", stream.closed);
		assertTrue("session not ended", f.closed);
		assertEquals("prompt not closed", 1, prompt.closes);
	}

	@Test(timeout = 10000)
	public void test_a_read_that_blocks_forever_ends_at_the_deadline() throws Exception {
		Fake f = new Fake(Phase.RUNNING, true);
		Prompt prompt = new Prompt();
		TrackedStream stream = new TrackedStream();
		FrameReader hang = s -> {
			try {
				Thread.sleep(60_000);
			} catch (InterruptedException e) {
				throw new IOException("interrupted", e);
			}
			return new byte[] { 1 };
		};
		long t0 = System.currentTimeMillis();
		assertNull(gate(coordinator(f), hang, prompt, 400, true).captureLive("OPERATOR", () -> stream, () -> {
			throw new AssertionError("must not capture");
		}));
		assertTrue("took too long: " + (System.currentTimeMillis() - t0), System.currentTimeMillis() - t0 < 5000);
		assertTrue(stream.closed);
		assertTrue(f.closed);
		assertEquals(1, prompt.closes);
	}

	@Test(timeout = 10000)
	public void test_frames_that_never_reach_a_verdict_end_at_the_deadline() throws Exception {
		Fake f = new Fake(Phase.RUNNING, true); // frames keep coming, the engine never decides
		FrameReader slow = s -> {
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				throw new IOException(e);
			}
			return new byte[] { 1 };
		};
		assertNull(gate(coordinator(f), slow, null, 300, true).captureLive("OPERATOR", TrackedStream::new, () -> {
			throw new AssertionError("must not capture");
		}));
		assertTrue(f.closed);
		assertTrue("frames were not fed", f.frames.get() > 0);
	}

	@Test
	public void test_no_stream_means_no_capture() throws Exception {
		Fake f = new Fake(Phase.LIVE, true);
		assertNull(gate(coordinator(f), FRAMES, null, 5000, true).captureLive("OPERATOR", () -> null, () -> {
			throw new AssertionError("must not capture");
		}));
	}

	@Test
	public void test_a_workflow_with_no_attempts_left_is_refused_before_the_camera_opens() throws Exception {
		Fake f = new Fake(Phase.NOT_LIVE, true);
		LivenessCaptureCoordinator c = coordinator(f);
		LivenessAuthGate g = gate(c, FRAMES, null, 5000, true);
		for (int i = 0; i < 3; i++) {
			assertNull(g.captureLive("OPERATOR", TrackedStream::new, () -> device(new byte[] { 1 })));
		}
		AtomicInteger opened = new AtomicInteger();
		assertNull(g.captureLive("OPERATOR", () -> {
			opened.incrementAndGet();
			return new TrackedStream();
		}, () -> device(new byte[] { 1 })));
		assertEquals("the camera was opened after the retries were used up", 0, opened.get());
		// another workflow is not locked, and a reset (new login / new registration) unlocks this one
		assertFalse(c.retriesExhausted("SUPERVISOR"));
		c.resetAttempts();
		assertFalse(c.retriesExhausted("OPERATOR"));
	}

	@Test
	public void test_switched_off_liveness_is_audited_once_per_capture_and_still_captures() throws Exception {
		AuditManagerService audit = org.mockito.Mockito.mock(AuditManagerService.class);
		LivenessCaptureCoordinator c = coordinator(new Fake(Phase.LIVE, true));
		c.setAuditManager(audit);
		AtomicInteger captures = new AtomicInteger();
		List<BiometricsDto> got = gate(c, FRAMES, null, 5000, false).captureLive("OPERATOR", () -> {
			throw new AssertionError("no stream when disabled");
		}, () -> {
			captures.incrementAndGet();
			return device(new byte[] { 1 });
		});
		assertNotNull(got);
		assertEquals(1, captures.get());
		org.mockito.Mockito.verify(audit, org.mockito.Mockito.times(1)).audit(
				org.mockito.Matchers.eq(AuditEvent.REG_LIVENESS_DISABLED), org.mockito.Matchers.any(io.mosip.registration.constants.Components.class),
				org.mockito.Matchers.anyString(), org.mockito.Matchers.anyString());
	}
}
