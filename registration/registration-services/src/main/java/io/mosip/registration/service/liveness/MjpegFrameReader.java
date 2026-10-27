package io.mosip.registration.service.liveness;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.mosip.registration.service.liveness.LivenessAuthGate.FrameReader;

/**
 * Reads the next JPEG from a Mosip device (MDS) preview stream (multipart, each part with a Content-Length header).
 * Unlike a plain read loop it ends with an {@link EOFException} when the stream ends and refuses absurd sizes, so
 * an unplugged camera or a hostile device cannot make the caller spin or allocate without limit.
 */
public class MjpegFrameReader implements FrameReader {

	private static final String CONTENT_LENGTH = "Content-Length:";
	/** Largest header block and largest frame accepted. */
	static final int MAX_HEADER = 4096;
	static final int MAX_FRAME = 16 * 1024 * 1024;

	@Override
	public byte[] next(InputStream in) throws IOException {
		ByteArrayOutputStream header = new ByteArrayOutputStream(128);
		int length = -1;
		int b;
		while (length < 0) {
			b = in.read();
			if (b < 0) {
				throw new EOFException("stream ended in the part header");
			}
			if (header.size() >= MAX_HEADER) {
				throw new IOException("part header too long");
			}
			header.write(b);
			String h = new String(header.toByteArray(), StandardCharsets.ISO_8859_1);
			int at = h.indexOf(CONTENT_LENGTH);
			if (at >= 0 && (b == 10 || b == 13) && h.length() > at + CONTENT_LENGTH.length()) {
				try {
					length = Integer.parseInt(h.substring(at + CONTENT_LENGTH.length()).trim());
				} catch (NumberFormatException e) {
					throw new IOException("bad Content-Length", e);
				}
			}
		}
		if (length <= 0 || length > MAX_FRAME) {
			throw new IOException("unacceptable frame length " + length);
		}
		// skip to the JPEG start (0xFF); the end of the stream is an error, never a loop
		while ((b = in.read()) != 255) {
			if (b < 0) {
				throw new EOFException("stream ended before the image");
			}
		}
		byte[] img = new byte[length + 1];
		img[0] = (byte) 255;
		int off = 1;
		while (off < img.length) {
			int n = in.read(img, off, img.length - off);
			if (n < 0) {
				throw new EOFException("stream ended inside the image");
			}
			off += n;
		}
		return img;
	}
}
