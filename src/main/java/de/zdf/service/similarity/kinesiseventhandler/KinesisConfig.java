package de.zdf.service.similarity.kinesiseventhandler;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.annotation.PostConstruct;
import javax.validation.constraints.NotNull;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.amazonaws.services.kinesis.clientlibrary.lib.worker.InitialPositionInStream;

import de.zdf.service.similarity.ServiceInitializationException;

/**
 * Die Konfiguration rund um die Kinesis-Anbindung.
 */
@Configuration
@ConfigurationProperties(prefix = "kinesis")
public class KinesisConfig {
	private static final String DEFAULT_CONSUMER_GROUP = "similarity-service";

	@NotNull
	private String tagStreamAccessKey;
	@NotNull
	private String tagStreamSecretKey;
	@NotNull
	private String awsRegion;
	@NotNull
	private String tagStreamName;
	private boolean doCheckpointing = true;

	private String tagStreamConsumerGroup;
	@NotNull
	private Long requestTimeout;
	@NotNull
	private Long recordMaxBufferedTime;

	@NotNull
	private int collectionMaxCount;
	private Date initialPositionTimestamp = null;
	private InitialPosition initialPosition = InitialPosition.latest;

	@PostConstruct
	protected void init() {
		if (StringUtils.isBlank(this.tagStreamConsumerGroup)) {
			this.tagStreamConsumerGroup = DEFAULT_CONSUMER_GROUP;
		}
		if (this.getInitialPosition() == InitialPosition.timestamp && this.getInitialPositionTimestamp() == null) {
			throw new ServiceInitializationException("initialPosition was set to 'timestamp' without providing a timestamp!");
		}
		// TODO konfigurierte Daten direkt validieren bspw. über einen describe-stream-Call via SDK...
	}

	public String getTagStreamAccessKey() {
		return tagStreamAccessKey;
	}
	public void setTagStreamAccessKey(String accessKey) {
		this.tagStreamAccessKey = accessKey;
	}
	public String getTagStreamSecretKey() {
		return tagStreamSecretKey;
	}
	public void setTagStreamSecretKey(String secretKey) {
		this.tagStreamSecretKey = secretKey;
	}
	public String getAwsRegion() {
		return awsRegion;
	}
	public void setAwsRegion(String awsRegion) {
		this.awsRegion = awsRegion;
	}

	public String getTagStreamName() {
		return tagStreamName;
	}
	public void setTagStreamName(String streamName) {
		this.tagStreamName = streamName;
	}
	public boolean isDoCheckpointing() {
		return doCheckpointing;
	}
	public void setDoCheckpointing(boolean doCheckpointing) {
		this.doCheckpointing = doCheckpointing;
	}

	public String getTagStreamConsumerGroup() {
		return tagStreamConsumerGroup;
	}
	public void setTagStreamConsumerGroup(String streamConsumerGroup) {
		this.tagStreamConsumerGroup = streamConsumerGroup;
	}
	public InitialPosition getInitialPosition() {
		return initialPosition;
	}
	public void setInitialPosition(InitialPosition initialPosition) {
		this.initialPosition = initialPosition;
	}
	public Date getInitialPositionTimestamp() {
		return initialPositionTimestamp;
	}
	public void setInitialPositionTimestamp(String initialPositionTimestampStr) throws ParseException {
		if (StringUtils.isNotBlank(initialPositionTimestampStr)) {
			this.initialPositionTimestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").parse(initialPositionTimestampStr);
		}
	}

	public Long getRequestTimeout() {
		return requestTimeout;
	}

	public void setRequestTimeout(Long requestTimeout) {
		this.requestTimeout = requestTimeout;
	}

	public Long getRecordMaxBufferedTime() {
		return recordMaxBufferedTime;
	}

	public void setRecordMaxBufferedTime(Long recordMaxBufferedTime) {
		this.recordMaxBufferedTime = recordMaxBufferedTime;
	}

	public int getCollectionMaxCount() {
		return collectionMaxCount;
	}

	public void setCollectionMaxCount(int collectionMaxCount) {
		this.collectionMaxCount = collectionMaxCount;
	}

	public static enum InitialPosition {
		latest(InitialPositionInStream.LATEST),
		all(InitialPositionInStream.TRIM_HORIZON),
		timestamp(InitialPositionInStream.AT_TIMESTAMP);
		
		private InitialPositionInStream initialPosKinesis;
		private InitialPosition(final InitialPositionInStream pInitialPosKinesis) {
			this.initialPosKinesis = pInitialPosKinesis;
		}
		
		public InitialPositionInStream getKinesisVal() {
			return this.initialPosKinesis;
		}
	}
}
