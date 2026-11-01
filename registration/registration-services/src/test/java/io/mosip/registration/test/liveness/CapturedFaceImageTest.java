package io.mosip.registration.test.liveness;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.junit.Test;

import io.mosip.registration.service.liveness.CapturedFaceImage;

public class CapturedFaceImageTest {

	private static BufferedImage sample() {
		BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 32; x++) {
				img.setRGB(x, y, (x * 8) << 16 | (y * 8) << 8 | 90);
			}
		}
		return img;
	}

	private static byte[] jp2(BufferedImage img) throws Exception {
		Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg2000");
		assertTrue("the JPEG 2000 plugin (jai-imageio-jpeg2000) is not on the classpath", it.hasNext());
		ImageWriter w = it.next();
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(o)) {
			w.setOutput(out);
			w.write(img);
		}
		w.dispose();
		return o.toByteArray();
	}

	@Test
	public void test_jpeg2000_inside_an_iso_record_is_decoded_to_png() throws Exception {
		byte[] image = jp2(sample());
		byte[] header = new byte[60]; // stands in for the ISO 19794-5 header and face information block
		byte[] iso = new byte[header.length + image.length];
		System.arraycopy(header, 0, iso, 0, header.length);
		System.arraycopy(image, 0, iso, header.length, image.length);
		byte[] png = CapturedFaceImage.toPng(iso);
		assertNotNull(png);
		BufferedImage back = ImageIO.read(new ByteArrayInputStream(png));
		assertEquals(32, back.getWidth());
		assertEquals(32, back.getHeight());
	}

	@Test
	public void test_bare_jpeg_and_png_pass_through() throws Exception {
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		ImageIO.write(sample(), "jpg", o);
		assertNotNull(CapturedFaceImage.toPng(o.toByteArray()));
		o.reset();
		ImageIO.write(sample(), "png", o);
		assertNotNull(CapturedFaceImage.toPng(o.toByteArray()));
	}

	@Test
	public void test_garbage_and_empty_give_null() {
		assertNull(CapturedFaceImage.toPng(new byte[] { 1, 2, 3, 4 }));
		assertNull(CapturedFaceImage.toPng(new byte[0]));
		assertNull(CapturedFaceImage.toPng(null));
	}
}
