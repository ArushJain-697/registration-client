package io.mosip.registration.api.liveness;

/**
 * SPI implemented by the liveness engine. Registered as a Spring bean and found by {@link LivenessFacade}.
 */
public interface LivenessSessionProvider {

	/** @return a name for logs and audit */
	String getServiceName();

	/**
	 * @param workflow RESIDENT, OPERATOR or SUPERVISOR
	 * @return a new session
	 */
	LivenessSessionHandle open(String workflow);
}
