package io.mosip.registration.service.liveness;

/**
 * Where the guidance text of a liveness check is shown when there is no capture screen of its own (login, packet
 * authentication). The client implements it with a small window; without an implementation the check runs silently.
 * Methods may be called from any thread and must not block.
 */
public interface LivenessPrompt {

	void show(String text);

	void close();
}
