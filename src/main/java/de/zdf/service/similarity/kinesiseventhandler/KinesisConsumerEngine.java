package de.zdf.service.similarity.kinesiseventhandler;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import com.amazonaws.services.kinesis.metrics.interfaces.MetricsLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.KinesisClientLibConfiguration;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker;

import de.zdf.service.similarity.ServiceInitializationException;
import de.zdf.service.similarity.kinesiseventhandler.KinesisConfig.InitialPosition;

/**
 * initialisiert die Kinesis Client Library und hinterlegt dort unseren Consumer (RecordProcessorFactory -> RecordProcessor).
 */
@Component
public class KinesisConsumerEngine {
	private static final Logger LOGGER = LoggerFactory.getLogger(KinesisConsumerEngine.class);
	
	@Autowired
	private RecordProcessorFactory recordProcessorFactory;

	@Autowired
	private KinesisConfig kinesisConfig;

	@Value("${server.port}")
	private int serverPort;
	
	private ExecutorService workerExecutor;
	private Worker kinesisWorker;

	@PostConstruct
	protected void init() throws UnknownHostException {

		if (this.serverPort <= 0) {
			throw new IllegalArgumentException("Server-Port ist nicht oder auf kleiner null konfiguriert - benötige positive Ganzzahl");
		}

		this.workerExecutor = Executors.newSingleThreadExecutor();

		// KCL-Worker initialisieren
		final AWSCredentialsProvider credentialsProvider = new AWSStaticCredentialsProvider(new BasicAWSCredentials(
				kinesisConfig.getTagStreamAccessKey(),
				kinesisConfig.getTagStreamSecretKey()));
		final String workerId = InetAddress.getLocalHost().getCanonicalHostName() + ":" + this.serverPort;
		final KinesisClientLibConfiguration kinesisClientLibConfiguration = new KinesisClientLibConfiguration(
				kinesisConfig.getTagStreamConsumerGroup(), kinesisConfig.getTagStreamName(), credentialsProvider, workerId)
				.withRegionName(kinesisConfig.getAwsRegion()).withMetricsLevel(MetricsLevel.NONE);;
		switch(kinesisConfig.getInitialPosition()) {
			case all:
			case latest:
				kinesisClientLibConfiguration.withInitialPositionInStream(kinesisConfig.getInitialPosition().getKinesisVal());
				break;
			case timestamp:
				kinesisClientLibConfiguration.withTimestampAtInitialPositionInStream(kinesisConfig.getInitialPositionTimestamp());
				break;
			default:
				throw new ServiceInitializationException("unknown value for initial stream position - aborting startup!");
		}

		this.kinesisWorker = new Worker.Builder()
				.recordProcessorFactory(recordProcessorFactory)
				.config(kinesisClientLibConfiguration)
				.build();

		LOGGER.info("Running {} to process stream {} in region {} as worker {}, initialPosition is {}...\n", kinesisConfig.getTagStreamConsumerGroup(),
				kinesisConfig.getTagStreamName(), kinesisConfig.getAwsRegion(), workerId, kinesisConfig.getInitialPosition().getKinesisVal());
		if (kinesisConfig.getInitialPosition() == InitialPosition.timestamp) {
			LOGGER.info("  initialPositionTimestamp is: {}",
					new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(kinesisConfig.getInitialPositionTimestamp()));
		}
		if (!kinesisConfig.isDoCheckpointing()) {
			LOGGER.info("ATTENTION: checkpointing is deactivated!!!");
		}

		// Und den KCL-Worker starten...
		LOGGER.info("Starting KCL worker...");
		this.workerExecutor.submit(this.kinesisWorker);
	}

	@PreDestroy
	protected void deinit() {
		LOGGER.info("deinit() requested by container. Trying to gracefully shut down KCL worker...");
		try {
			this.kinesisWorker.createGracefulShutdownCallable().call();
			LOGGER.info("KCL worker was gracefully shut down!");
		} catch (final Exception ex) {
			LOGGER.info("Exception during shutdown of KCL worker:", ex);
		}
		if (this.workerExecutor != null) {
			this.workerExecutor.shutdownNow();
			this.workerExecutor = null;
			LOGGER.info("workerExecutor has been shut down!");
		}
	}
}
