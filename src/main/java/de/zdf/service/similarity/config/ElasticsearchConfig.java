package de.zdf.service.similarity.config;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.validation.constraints.NotNull;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.message.BasicHeader;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "elasticsearch")
public class ElasticsearchConfig {
	@NotNull
	private String protocol;
	@NotNull
	private String host;
	@NotNull
	private int port;
	
	private String basePath;
	
	@NotNull
	private String docsIndex;
	@NotNull
	private String documentType;
	@NotNull
	private int scrollSize;
	@NotNull
	private String openSearchContextTime;
	@NotNull
	private String indicatorsFieldSuffix;
	@NotNull
	private int documentUpdateChunkSize = 4096;

	private String searchEndpoint;
	private String initialScrollEndpoint;
	private String scrollEndpoint;
	private String bulkEndpoint;

	@NotNull
	private boolean useAuthHeader;
	private String authField;
	private String authToken;
	private boolean isBearer;
	private String username;
	private String password;

	private String proxyHost;
	private String proxyPassword;
	private int proxyPort;
	private String proxyScheme;
	private String proxyUser;
	
	private Header[] requestHeaders;
	
	@PostConstruct
	protected void init() {
		buildEndpoints();
		buildHeaders();
	}
	
	private void buildEndpoints() {
		searchEndpoint = String.format("/%s/_search", docsIndex);
		initialScrollEndpoint = String.format("%s?scroll=%s", searchEndpoint, openSearchContextTime);
		scrollEndpoint = "/_search/scroll";
		bulkEndpoint = "/_bulk";
		if (StringUtils.isNotBlank(getBasePath())) {
			searchEndpoint = basePath.concat(searchEndpoint);
			initialScrollEndpoint = basePath.concat(initialScrollEndpoint);
			scrollEndpoint = basePath.concat(scrollEndpoint);
			bulkEndpoint = basePath.concat(bulkEndpoint);
		}
	}
	
	private void buildHeaders() {
		List<BasicHeader> headers = new ArrayList<>();
		headers.add(new BasicHeader(HttpHeaders.CONTENT_TYPE, "application/json"));
		if (useAuthHeader) {
			String token = isBearer ? "Bearer ".concat(authToken) : authToken;
			headers.add(new BasicHeader(authField, token));
		}
		requestHeaders = headers.toArray(new Header[0]);
	}
	
	public String getHost() {
		return host;
	}
	public String getProtocol() {
		return protocol;
	}
	public int getPort() {
		return port;
	}
	public String getBasePath() {
		return basePath;
	}
	public String getDocsIndex() {
		return docsIndex;
	}
	public String getDocumentType() {
		return documentType;
	}
	public int getScrollSize() {
		return scrollSize;
	}
	public String getOpenSearchContextTime() {
		return openSearchContextTime;
	}
	public String getIndicatorsFieldSuffix() {
		return indicatorsFieldSuffix;
	}
	public int getDocumentUpdateChunkSize() {
		return documentUpdateChunkSize;
	}
	public String getSearchEndpoint() {
		return searchEndpoint;
	}
	public String getInitialScrollEndpoint() {
		return initialScrollEndpoint;
	}
	public String getScrollEndpoint() {
		return scrollEndpoint;
	}
	public String getBulkEndpoint() {
		return bulkEndpoint;
	}
	public Header[] getRequestHeaders() {
		return requestHeaders;
	}
	public boolean isUseAuthHeader() {
		return useAuthHeader;
	}
	public String getAuthField() {
		return authField;
	}
	public String getAuthToken() {
		return authToken;
	}
	public boolean isBearer() {
		return isBearer;
	}
	public String getUsername() { return username; }
	public void setUsername(String username) { this.username = username; }
	public String getPassword() { return password; }
	public void setPassword(String password) { this.password = password; }

	public void setHost(String host) {
		this.host = host;
	}
	public void setProtocol(String protocol) {
		this.protocol = protocol;
	}
	public void setPort(int port) {
		this.port = port;
	}
	public void setBasePath(String basePath) {
		this.basePath = basePath;
	}
	public void setDocsIndex(String docsIndex) {
		this.docsIndex = docsIndex;
	}
	public void setDocumentType(String documentType) {
		this.documentType = documentType;
	}
	public void setScrollSize(int scrollSize) {
		this.scrollSize = scrollSize;
	}
	public void setOpenSearchContextTime(String openSearchContextTime) {
		this.openSearchContextTime = openSearchContextTime;
	}
	public void setIndicatorsFieldSuffix(String indicatorsFieldSuffix) {
		this.indicatorsFieldSuffix = indicatorsFieldSuffix;
	}
	public void setDocumentUpdateChunkSize(int documentUpdateChunkSize) {
		this.documentUpdateChunkSize = documentUpdateChunkSize;
	}
	public void setSearchEndpoint(String searchEndpoint) {
		this.searchEndpoint = searchEndpoint;
	}
	public void setInitialScrollEndpoint(String initialScrollEndpoint) {
		this.initialScrollEndpoint = initialScrollEndpoint;
	}
	public void setScrollEndpoint(String scrollEndpoint) {
		this.scrollEndpoint = scrollEndpoint;
	}
	public void setBulkEndpoint(String bulkEndpoint) {
		this.bulkEndpoint = bulkEndpoint;
	}
	public void setRequestHeaders(Header[] requestHeaders) {
		this.requestHeaders = requestHeaders;
	}
	public void setUseAuthHeader(boolean useAuthHeader) {
		this.useAuthHeader = useAuthHeader;
	}

	public void setAuthField(String authField) {
		this.authField = authField;
	}

	public void setAuthToken(String authToken) {
		this.authToken = authToken;
	}
	public void setBearer(boolean isBearer) {
		this.isBearer = isBearer;
	}


	public String getProxyHost() {
		return proxyHost;
	}

	public void setProxyHost(String proxyHost) {
		this.proxyHost = proxyHost;
	}

	public String getProxyPassword() {
		return proxyPassword;
	}

	public void setProxyPassword(String proxyPassword) {
		this.proxyPassword = proxyPassword;
	}

	public int getProxyPort() {
		return proxyPort;
	}

	public void setProxyPort(int proxyPort) {
		this.proxyPort = proxyPort;
	}

	public String getProxyScheme() {
		return proxyScheme;
	}

	public void setProxyScheme(String proxyScheme) {
		this.proxyScheme = proxyScheme;
	}

	public String getProxyUser() {
		return proxyUser;
	}

	public void setProxyUser(String proxyUser) {
		this.proxyUser = proxyUser;
	}
}
