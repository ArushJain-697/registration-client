package io.mosip.registration.test.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import io.mosip.registration.service.liveness.MjpegFrameReader;

public class MjpegFrameReaderTest {

	private static byte[] part(int declared, byte[] jpeg) throws IOException {
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		o.write(("--BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: " + declared + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
		o.write(jpeg);
		return o.toByteArray();
	}

	private static byte[] jpeg(int n) {
		byte[] b = new byte[n];
		b[0] = (byte) 0xFF;
		b[1] = (byte) 0xD8;
		return b;
	}

	@Test
	public void test_reads_one_frame_then_the_next() throws Exception {
		ByteArrayOutputStream two = new ByteArrayOutputStream();
		two.write(part(9, jpeg(10)));
		two.write(part(4, jpeg(5)));
		ByteArrayInputStream in = new ByteArrayInputStream(two.toByteArray());
		MjpegFrameReader r = new MjpegFrameReader();
		assertEquals(10, r.next(in).length);
		assertEquals(5, r.next(in).length);
	}

	@Test(timeout = 5000)
	public void test_end_of_stream_throws_instead_of_spinning() throws Exception {
		MjpegFrameReader r = new MjpegFrameReader();
		for (byte[] data : new byte[][] { {}, "--BOUNDARY\r\nContent-Len".getBytes(), part(100, new byte[0]),
				part(100, jpeg(10)) }) {
			try {
				r.next(new ByteArrayInputStream(data));
				fail("no exception at the end of the stream");
			} catch (EOFException expected) {
				// the loop ends
			}
		}
	}

	@Test
	public void test_absurd_frame_length_is_refused_without_allocating() throws Exception {
		try {
			new MjpegFrameReader().next(new ByteArrayInputStream(part(2_000_000_000, jpeg(10))));
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("length"));
		}
	}
}
