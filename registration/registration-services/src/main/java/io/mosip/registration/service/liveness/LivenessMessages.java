package io.mosip.registration.service.liveness;

import java.io.IOException;

import io.sajeev.guidance.GuidanceKeys;

/**
 * The words the person sees during a liveness check, in English, French or Arabic, taken from the engine's own bundles
 * (the PDF texts are exact in English; French and Arabic are not yet reviewed by a native speaker).
 */
public final class LivenessMessages {

	private static GuidanceKeys keys;

	private LivenessMessages() {
	}

	/**
	 * @param key a guidance key such as {@code challenge.blink} or {@code error.face_not_detected}
	 * @param lang "en", "fr" or "ar"; anything else gives English
	 * @return the text; the key itself if the bundle cannot be read
	 */
	public static synchronized String text(String key, String lang) {
		if (key == null) {
			return "";
		}
		try {
			if (keys == null) {
				try {
					keys = GuidanceKeys.fromConfig(LivenessClientConfig.guidanceOverrides());
				} catch (IllegalArgumentException bad) {
					keys = new GuidanceKeys(); // a faulty override file never blanks the screen: the PDF wording is used
				}
			}
			String t = keys.text(key, lang == null ? "en" : lang);
			return t == null ? key : t;
		} catch (IOException e) {
			return key;
		}
	}

	/** @param lang a language code @return true for right-to-left languages */
	public static boolean isRightToLeft(String lang) {
		return GuidanceKeys.isRtl(lang == null ? "en" : lang);
	}
}
