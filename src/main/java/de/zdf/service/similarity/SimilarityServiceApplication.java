package de.zdf.service.similarity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Applikationsklasse für den Neo4j-Kinesis-Service.
 *
 * Der Dienst ist dafür gedacht, den Kinesis Stream zu konsumieren, der die Tracking-Events enthält, die die Benutzer
 * durch Verwendung der Mediathek erzeugen. Die aus dem Stream extrahierten Events werden dann in der angeschlossenen
 * Neo4j-DB abgespeichert, welche wiederum als Basis für die Berechnung der Indikatoren für das Collaborative Filtering
 * dient.
 */
@SpringBootApplication(scanBasePackages = { "de.zdf.service.similarity", "de.zdf.service.commons" })
public class SimilarityServiceApplication {

	/**
	 * Main-Methode der Neo4j-Kinesis-Service-Applikation.
	 * 
	 * @param args Kommandozeilenparameter
	 */
	public static void main(String[] args) {
		// Ensure the JVM will refresh the cached IP values of AWS resources
		// (e.g. service endpoints).
		java.security.Security.setProperty("networkaddress.cache.ttl", "60");

		SpringApplication.run(SimilarityServiceApplication.class, args);
	}
}
