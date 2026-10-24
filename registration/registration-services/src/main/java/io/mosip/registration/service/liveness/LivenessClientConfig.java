package io.mosip.registration.service.liveness;

import io.mosip.registration.context.ApplicationContext;

/**
 * Liveness settings from the client's configuration (global parameters synced from the server, or local), each with a
 * client-side default so that the client works when the server does not send them. A bad or missing value never
 * disables liveness: it falls back to the default.
 */
public final class LivenessClientConfig {

	public static final String PREFIX = "mosip.registration.liveness.";
	public static final String ENABLED = PREFIX + "enabled";
	public static final String MAX_RETRIES = PREFIX + "maxRetries";
	public static final String SESSION_TIMEOUT_MS = PREFIX + "sessionTimeoutMs";
	public static final String GUIDANCE_OVERRIDES = PREFIX + "guidance.overrides";

	public static final boolean DEFAULT_ENABLED = true;
	public static final int DEFAULT_MAX_RETRIES = 3;
	public static final long DEFAULT_SESSION_TIMEOUT_MS = 45_000L;

	private LivenessClientConfig() {
	}

	private static String raw(String key) {
		try {
			return ApplicationContext.getStringValueFromApplicationMap(key);
		} catch (RuntimeException e) {
			return null; // map not initialised
		}
	}

	/** @return the device capture timeout in milliseconds (the client's own setting), 30 s when it is not set */
	public static long captureTimeoutMillis() {
		try {
			int v = ApplicationContext.getIntValueFromApplicationMap(io.mosip.registration.constants.RegistrationConstants.CAPTURE_TIME_OUT);
			return v > 0 ? v : 30_000L;
		} catch (RuntimeException e) {
			return 30_000L;
		}
	}

	/**
	 * Every engine setting (mosip.registration.liveness.*, with the .resident/.operator/.supervisor overrides) that the
	 * client's configuration holds, so the engine's threshold, challenge types, minimum challenges, timeout and
	 * per-workflow policy follow the client's configuration. Keys that are not set keep the engine defaults.
	 */
	public static java.util.Properties engineProperties() {
		java.util.Properties p = new java.util.Properties();
		for (String key : io.sajeev.policy.Config.DEFAULTS.keySet()) {
			put(p, key);
			for (String w : io.sajeev.policy.Config.WORKFLOWS) {
				put(p, key + "." + w);
			}
		}
		return p;
	}

	private static void put(java.util.Properties p, String suffix) {
		String v = raw(PREFIX + suffix);
		if (v != null && !v.trim().isEmpty()) {
			p.setProperty(PREFIX + suffix, v.trim());
		}
	}

	/** @return the folder with guidance text overrides, or "none" (the default) */
	public static String guidanceOverrides() {
		String v = raw(GUIDANCE_OVERRIDES);
		return v == null || v.trim().isEmpty() ? "none" : v.trim();
	}

	/** @return false only when the value is exactly "false"; anything else keeps liveness on */
	public static boolean enabled() {
		String v = raw(ENABLED);
		return v == null || !"false".equalsIgnoreCase(v.trim());
	}

	public static int maxRetries() {
		try {
			String v = raw(MAX_RETRIES);
			int n = v == null ? DEFAULT_MAX_RETRIES : Integer.parseInt(v.trim());
			return n < 1 ? DEFAULT_MAX_RETRIES : n;
		} catch (NumberFormatException e) {
			return DEFAULT_MAX_RETRIES;
		}
	}

	public static long sessionTimeoutMillis() {
		try {
			String v = raw(SESSION_TIMEOUT_MS);
			long n = v == null ? DEFAULT_SESSION_TIMEOUT_MS : Long.parseLong(v.trim());
			return n < 1000 ? DEFAULT_SESSION_TIMEOUT_MS : n;
		} catch (NumberFormatException e) {
			return DEFAULT_SESSION_TIMEOUT_MS;
		}
	}
}
