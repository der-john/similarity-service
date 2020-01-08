package de.zdf.service.similarity.kinesiseventhandler;

import de.zdf.service.similarity.EventBatchProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessorFactory;

/**
 * erzeugt neue Instanzen des RecordProcessors.
 */
@Component
public class RecordProcessorFactory implements IRecordProcessorFactory {

	@Autowired
	private KinesisConfig kinesisConfig;
	@Autowired
	private EventBatchProcessor eventBatchProcessor;

	@Override
	public IRecordProcessor createProcessor() {
		return new RecordProcessor(kinesisConfig, eventBatchProcessor);
	}
}
