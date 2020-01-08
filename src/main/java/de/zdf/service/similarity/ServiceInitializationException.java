package de.zdf.service.similarity;

/**
 * Fehler beim Start eines Services. Führt normalerweise zum Abbruch.
 */
public class ServiceInitializationException extends RuntimeException {

	public ServiceInitializationException(String message, Exception e) {
		super(message, e);
	}

	public ServiceInitializationException(String message) {
		super(message);
	}

	/**
	 * 
	 */
	private static final long serialVersionUID = -2214159247336068987L;
}
