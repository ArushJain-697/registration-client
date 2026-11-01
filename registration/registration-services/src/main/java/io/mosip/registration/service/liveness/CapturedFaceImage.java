package io.mosip.registration.service.liveness;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * Turns what the device returned (an ISO 19794-5 face record with a JPEG 2000 image inside, or a plain image) into a
 * PNG that the liveness engine can decode. The client already ships the JPEG 2000 reader (jai-imageio-jpeg2000).
 */
public final class CapturedFaceImage {

	private static final byte[] JP2_SIGNATURE = { 0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, (byte) 0x87, 0x0A };
	private static final byte[] J2K_CODESTREAM = { (byte) 0xFF, 0x4F, (byte) 0xFF, 0x51 };

	private CapturedFaceImage() {
	}

	/**
	 * @param isoOrImage the bytes of the captured face (ISO 19794-5 record, bare JPEG 2000, JPEG or PNG)
	 * @return PNG bytes, or null when nothing could be decoded
	 */
	public static byte[] toPng(byte[] isoOrImage) {
		if (isoOrImage == null || isoOrImage.length == 0) {
			return null;
		}
		int at = indexOf(isoOrImage, JP2_SIGNATURE);
		if (at < 0) {
			at = indexOf(isoOrImage, J2K_CODESTREAM);
		}
		byte[] image = at > 0 ? java.util.Arrays.copyOfRange(isoOrImage, at, isoOrImage.length) : isoOrImage;
		try {
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(image));
			if (img == null) {
				return null;
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(img, "png", out);
			return out.toByteArray();
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static int indexOf(byte[] data, byte[] pattern) {
		outer: for (int i = 0; i <= data.length - pattern.length; i++) {
			for (int j = 0; j < pattern.length; j++) {
				if (data[i + j] != pattern[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}
}
