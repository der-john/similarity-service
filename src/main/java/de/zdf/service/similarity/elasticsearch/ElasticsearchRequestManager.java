package de.zdf.service.similarity.elasticsearch;

import java.io.IOException;
import de.zdf.service.similarity.config.ElasticsearchConfig;
import org.apache.commons.collections.CollectionUtils;
import org.apache.http.HttpHost;
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
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class ElasticsearchRequestManager {

	private static final String POST = "POST";
	
	private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchRequestManager.class);


	private final String indicatorsUpdateRequestTemplate = "{ \"doc\" : %s }";
	
	private final String bulkUpdateRequestTemplate = "{ \"update\" : {\"_id\" : \"%s\", \"_type\" : \"%s\", \"_index\" : \"%s\"} }%n"
													.concat("%s%n");
	
	@Autowired
	private ElasticsearchConfig elasticsearchConfig;
	
	private ObjectMapper mapper;


	public String getIndicatorsUpdateRequestTemplate() {
		return indicatorsUpdateRequestTemplate;
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
