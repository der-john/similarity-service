package de.zdf.service.similarity.elasticsearch;

import java.io.IOException;
import java.util.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.zdf.service.similarity.config.ElasticsearchConfig;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpHost;
import org.apache.http.StatusLine;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Jedis;

import static de.zdf.service.similarity.util.SimilarityUtil.*;

@Component
public class ElasticsearchEngine {

	private static final String POST = "POST";
	
	private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchEngine.class);


	private final String indicatorsUpdateRequestTemplate = "{ \"doc\" : %s }";
	
	private final String bulkUpdateRequestTemplate = "{ \"update\" : {\"_id\" : \"%s\", \"_type\" : \"%s\", \"_index\" : \"%s\"} }%n"
													.concat("%s%n");
	
	@Autowired
	private ElasticsearchConfig elasticsearchConfig;

	@Autowired
	private ObjectMapper mapper;

	@Value("${similarity.maxIndicators:25}")
	private Integer maxIndicators;

	public void generateAndPerformBulkUpdates(Jedis jedis, Set<Pair> indicatorFieldsForBatch) throws IOException {
		List<String> currentUpdateRequests = generateUpdateRequests(jedis, indicatorFieldsForBatch);
		try (final RestClient restClient = getRestClient()) {
			updateDocuments(restClient, currentUpdateRequests);
		}
	}

	private List<String> generateUpdateRequests(Jedis jedis, Set<Pair> indicatorFieldsToBeUpdated) {

		List<String> updateRequests = new ArrayList<>();

		for (Pair indicatorPair : indicatorFieldsToBeUpdated) {
			String id = (String) indicatorPair.getKey();
			String tagProvider = (String) indicatorPair.getValue();
			String key = getIndicatorsKey(tagProvider, id);
			Map<String, String> indicators = jedis.hgetAll(key);

			String indicatorFields;
			if (indicators.size() == 0) {
				String tagProviderKey = tagProvider + elasticsearchConfig.getIndicatorsFieldSuffix();
				indicatorFields = String.format("{ \"%s\": [] }", tagProviderKey);
			} else {
				indicatorFields = buildIndicatorFields(indicators, tagProvider);
			}
			String indicatorFieldsAsJsonString = String.format(
					indicatorsUpdateRequestTemplate,
					indicatorFields);
			if (indicatorFieldsAsJsonString != null) {
				String updateRequest = buildIndicatorsUpdateRequest(id, indicatorFieldsAsJsonString);
				updateRequests.add(updateRequest);

				// LOGGER.info("This is an update request: " + updateRequest);
			}
		}

		return updateRequests;
	}

	private String buildIndicatorFields(Map<String, String> indicatorStringMap, String tagProvider) {
		ObjectNode indicatorFields = mapper.createObjectNode();
		try {

			Map<String, Double> indicators = new HashMap<>();
			for (Map.Entry<String, String> indicatorStringPair : indicatorStringMap.entrySet()) {
				indicators.put(indicatorStringPair.getKey(), Double.parseDouble(indicatorStringPair.getValue()));
			}
			Map<String, Double> sortedIndicators = returnSubmapOfHighestValues(indicators, maxIndicators);

			ArrayNode indicatorsArray = mapper.createArrayNode();

			for (String docId : sortedIndicators.keySet()) {
				ObjectNode indicator = mapper.createObjectNode();
				indicator.put("id", docId);
				indicator.put("rating", indicators.get(docId));
				indicatorsArray.add(indicator);
			}
			String tagProviderKey = tagProvider + elasticsearchConfig.getIndicatorsFieldSuffix();
			indicatorFields.set(tagProviderKey, indicatorsArray);

		} catch (Exception e) {
			LOGGER.error("JSON exception while building indicators field json:" + e);
		}
		try {
			return mapper.writeValueAsString(indicatorFields);
		} catch (JsonProcessingException e) {
			LOGGER.error("JSON exception while processing " + indicatorFields);
			return null;
		}
	}

	public void updateDocuments(RestClient restClient, List<String> allIndicatorsUpdateRequests) {

		int chunkSize = elasticsearchConfig.getDocumentUpdateChunkSize();
		int offset = 0;
		int documentCount = allIndicatorsUpdateRequests.size();

		while (offset < allIndicatorsUpdateRequests.size()) {
			StringBuffer bulkRequest = new StringBuffer();
			List<String> updateRequestChunk = allIndicatorsUpdateRequests.subList(offset, Math.min(offset + chunkSize, allIndicatorsUpdateRequests.size()));

			int updateRequestCounter = updateRequestChunk.size();
			updateRequestChunk.forEach(updateRequest -> bulkRequest.append(updateRequest));

			if (updateRequestCounter > 0) {
				// LOGGER.info("{}: Sending Bulk-Request for {} Document-Updates ...", name(), updateRequestCounter);
				// LOGGER.info(bulkRequest.toString());
				Response bulkResponse = performBulkRequest(restClient, bulkRequest.toString());
				if (null == bulkResponse || null == bulkResponse.getStatusLine()) {
					LOGGER.warn("ES bulk request failed and didn't even throw a response.");
					return;
				}
				StatusLine statusLine = bulkResponse.getStatusLine();
				if (statusLine.getStatusCode() < 400) {
					LOGGER.info(statusLine.toString());
					LOGGER.info("{} Documents updated successfully (total number of Documents: {}).",
							updateRequestCounter, documentCount);
				} else {
					LOGGER.warn("ES bulk request threw status code {}: {}", statusLine.getStatusCode(), statusLine.getReasonPhrase());
				}
			} else {
				LOGGER.info("Index is already up to date. {} Documents updated (total number of Documents: {}).",
						updateRequestCounter, documentCount);
			}
			offset += chunkSize;
		}
	}

	public RestClient getRestClient() {

		RestClientBuilder builder = RestClient.builder(
				new HttpHost(new HttpHost(elasticsearchConfig.getHost(),
						elasticsearchConfig.getPort(),
						elasticsearchConfig.getProtocol())));
		if (null != elasticsearchConfig.getProxyHost()) {
			final CredentialsProvider credentialsProvider =
					new BasicCredentialsProvider();
			credentialsProvider.setCredentials(AuthScope.ANY,
					new UsernamePasswordCredentials(
							elasticsearchConfig.getProxyUser(), elasticsearchConfig.getProxyPassword()));

			// cf. https://www.elastic.co/guide/en/elasticsearch/client/java-rest/current/_basic_authentication.html
			// and https://www.elastic.co/guide/en/elasticsearch/client/java-rest/current/java-rest-low-usage-initialization.html
			builder.setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
				@Override
				public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
					return httpClientBuilder
						.setDefaultCredentialsProvider(credentialsProvider)
						.setProxy( new HttpHost(
							elasticsearchConfig.getProxyHost(),
							elasticsearchConfig.getProxyPort(),
							elasticsearchConfig.getProxyScheme()));
				}
			});

		} else if (null != elasticsearchConfig.getUsername()) {
			final CredentialsProvider credentialsProvider =
					new BasicCredentialsProvider();
			credentialsProvider.setCredentials(AuthScope.ANY,
					new UsernamePasswordCredentials(
							elasticsearchConfig.getUsername(), elasticsearchConfig.getPassword()));

			// cf. https://www.elastic.co/guide/en/elasticsearch/client/java-rest/current/_basic_authentication.html
			// and https://www.elastic.co/guide/en/elasticsearch/client/java-rest/current/java-rest-low-usage-initialization.html
			builder.setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
				@Override
				public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
					return httpClientBuilder
							.setDefaultCredentialsProvider(credentialsProvider);
				}
			});
		}

		return builder.setDefaultHeaders(elasticsearchConfig.getRequestHeaders()).build();
	}


	public String buildIndicatorsUpdateRequest(String docId, String indicatorFieldsAsJsonString) {

		return String.format(bulkUpdateRequestTemplate, docId, elasticsearchConfig.getDocumentType(),
								elasticsearchConfig.getDocsIndex(), indicatorFieldsAsJsonString);
	}
	
	public Response performBulkRequest(RestClient restClient, String bulkRequest) {
		Request request = new Request(POST, elasticsearchConfig.getBulkEndpoint());
		request.setJsonEntity(bulkRequest);		
		try {
			Response response = restClient.performRequest(request);
			return response;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}
}
