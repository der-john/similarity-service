package de.zdf.service.similarity.kinesiseventhandler;

import java.nio.charset.Charset;
import java.util.List;

import de.zdf.service.similarity.EventBatchProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ThrottlingException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.ShutdownReason;
import com.amazonaws.services.kinesis.clientlibrary.types.InitializationInput;
import com.amazonaws.services.kinesis.clientlibrary.types.ProcessRecordsInput;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownInput;
import com.amazonaws.services.kinesis.model.Record;

/**
 * Die RecordProcessor-Implementierung, die die übergebenen Kinesis-Records in eine Liste von JSONObjects wandelt,
 * deren Tracking- und Doc-Ids extrahiert und diese dann in Neo4j speichert.
 */
public class RecordProcessor implements IRecordProcessor {
	private static final Logger LOGGER = LoggerFactory.getLogger(RecordProcessor.class);

	// Backoff and retry settings
	private static final long BACKOFF_TIME_IN_MILLIS = 3000L;
	private static final int NUM_RETRIES = 10;
	
	private final Charset utf8Charset = Charset.forName("UTF-8");

	private String kinesisShardId;
	private KinesisConfig kinesisConfig;
	private EventBatchProcessor eventBatchProcessor;
	
	public RecordProcessor(final KinesisConfig pKinesisConfig, final EventBatchProcessor
			pEventBatchProcessor) {
		this.kinesisConfig = pKinesisConfig;
		this.eventBatchProcessor = pEventBatchProcessor;
	}

	@Override
	public void initialize(final InitializationInput pInitializationInput) {
		LOGGER.info("Initializing record processor for shard: " + pInitializationInput.getShardId());
		this.kinesisShardId = pInitializationInput.getShardId();
	}

	@Override
	public void processRecords(final ProcessRecordsInput pProcessRecordsInput) {
		LOGGER.info("Processing " + pProcessRecordsInput.getRecords().size() + " records from " + kinesisShardId);

		// Process records and perform all exception handling.
		processRecordsWithRetries(pProcessRecordsInput.getRecords());

		// nach jedem Aufruf mit mind. 1 Record Checkpoint setzen
		if (this.kinesisConfig.isDoCheckpointing() && !pProcessRecordsInput.getRecords().isEmpty()) {
			checkpoint(pProcessRecordsInput.getCheckpointer());
		}
	}

	@Override
	public void shutdown(final ShutdownInput pShutdownInput) {
		LOGGER.info("Shutting down record processor for shard: " + kinesisShardId);
		// Important to checkpoint after reaching end of shard, so we can start
		// processing data from child shards.
		if (pShutdownInput.getShutdownReason() == ShutdownReason.TERMINATE) {
			checkpoint(pShutdownInput.getCheckpointer());
		}
	}

	/**
	 * verarbeitet die Liste von Kinesis-Records mithilfe von processEventBatch
	 * 
	 * @param records Liste der Kinesis-Records, die in diesem Batch verarbeitet werden sollen
	 */
	private void processRecordsWithRetries(final List<Record> records) {

		boolean batchProcessedSuccessfully = false;
		for (int i = 0; i < NUM_RETRIES; i++) {
			try {
				this.eventBatchProcessor.processEventBatch(records);
				
				batchProcessedSuccessfully = true;
				break;
			} catch (final Throwable t) {
				LOGGER.warn("Caught throwable while processing UserEvent batch...", t);
			}

			// backoff if we encounter an exception.
			try {
				Thread.sleep(BACKOFF_TIME_IN_MILLIS);
			} catch (final InterruptedException iex) {
				LOGGER.debug("Interrupted sleep", iex);
			}
		}

		if (!batchProcessedSuccessfully) {
			LOGGER.error("Couldn't process batch, probably due to database problems. Skipping the batch.");
		}
	}

	
	/**
	 * Checkpoint with retries.
	 * 
	 * @param pCheckpointer checkpointer instance from processRecords() call
	 */
	private void checkpoint(final IRecordProcessorCheckpointer pCheckpointer) {
		LOGGER.debug("Checkpointing shard " + kinesisShardId);
		for (int i = 0; i < NUM_RETRIES; i++) {
			try {
				pCheckpointer.checkpoint();
				break;
			} catch (final ShutdownException se) {
				// Ignore checkpoint if the processor instance has been shutdown (fail over).
				LOGGER.info("Caught shutdown exception, skipping checkpoint.", se);
				break;
			} catch (final ThrottlingException e) {
				// Backoff and re-attempt checkpoint upon transient failures
				if (i >= (NUM_RETRIES - 1)) {
					LOGGER.error("Checkpoint failed after " + (i + 1) + "attempts.", e);
					break;
				} else {
					LOGGER.info("Transient issue when checkpointing - attempt " + (i + 1) + " of " + NUM_RETRIES, e);
				}
			} catch (final InvalidStateException e) {
				// This indicates an issue with the DynamoDB table (check for table, provisioned IOPS).
				LOGGER.error("Cannot save checkpoint to the DynamoDB table used by the Amazon Kinesis Client Library.", e);
				break;
			}
			try {
				Thread.sleep(BACKOFF_TIME_IN_MILLIS);
			} catch (InterruptedException e) {
				LOGGER.debug("Interrupted sleep", e);
			}
		}
	}
}
