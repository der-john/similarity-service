package de.zdf.service.similarity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.zdf.service.similarity.config.ElasticsearchConfig;
import de.zdf.service.similarity.config.RedisConfig;
import de.zdf.service.similarity.elasticsearch.ElasticsearchRequestManager;
import org.apache.commons.collections.MapUtils;
import org.apache.http.StatusLine;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.amazonaws.services.kinesis.model.Record;
import com.fasterxml.jackson.databind.ObjectMapper;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.util.*;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class EventBatchProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventBatchProcessor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CharsetDecoder DECODER = Charset.forName("UTF-8").newDecoder();

    @Autowired
    private RedisConfig redisConfig;
    @Autowired
    private ElasticsearchConfig elasticsearchConfig;
    @Autowired
    private ElasticsearchRequestManager elasticsearchRequestManager;
    @Autowired
    private ObjectMapper mapper;


    public String name() {
        return "SimilarityService";
    }

    public void processEventBatch(final List<Record> records) {
        long bulkStartTime = System.nanoTime();

        try (JedisPool pool = new JedisPool(redisConfig.getHost(), redisConfig.getPort())) {
            try (Jedis jedis = pool.getResource()) {
                List<String> updateRequestsForBatch = new ArrayList<>();
                for (Record record : records) {
                    try {
                        String dataString = DECODER.decode(record.getData()).toString();
                        ObjectNode kinesisJson = (ObjectNode) MAPPER.readTree(dataString);

                        String docId = kinesisJson.get("docId").textValue();
                        String tagProvider = kinesisJson.get("tagProvider").textValue();

                        HashMap<String, Double> tagMap = getTagMap(kinesisJson);

                        waitForRedis(jedis);

                        List<String> updateRequests = calculateIndicators(jedis, docId, tagProvider, tagMap);

                        updateRequestsForBatch.addAll(updateRequests);

                        LOGGER.info("Received {} update requests.", updateRequests.size());

                    } catch (IOException e) {
                        LOGGER.error("IO Exception: ", e);
                    } catch (Exception e) {
                        LOGGER.error("Couldn't process the following record: " + record, e);
                    }
                }

                final RestClient restClient = elasticsearchRequestManager.getRestClient();
                updateDocuments(restClient, updateRequestsForBatch);
            }
        }
        long bulkEndTime = System.nanoTime();
        final double processTimeInMs = (bulkEndTime - bulkStartTime) / (1000 * 1000.);
        LOGGER.info("Processing time: {} ms for {} records, giving us an average of {} ms per item",
                processTimeInMs,
                records.size(),
                processTimeInMs/records.size());
    }


    private void waitForRedis(Jedis jedis)  {
        boolean isRedisHealthy = false;
        while (!isRedisHealthy) {
            isRedisHealthy = "PONG".equals(jedis.ping());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private List<String> calculateIndicators(Jedis jedis, String docId, String tagProvider, HashMap<String, Double> tagMap) {
        List<String> docIdsToBeUpdated = new ArrayList<>();

        String indicatorsKey = getIndicatorsKey(tagProvider, docId);

        for (String tagName : tagMap.keySet()) {

            Double tagWeight = tagMap.get(tagName);
            String termKey = getTermKey(tagProvider, tagName);

            Map<String, String> termStringMap = jedis.hgetAll(termKey);
            if (MapUtils.isNotEmpty(termStringMap)) {

                String previousWeightString = termStringMap.get(docId);
                Double previousWeight = 0d;
                if (previousWeightString != null) {
                    previousWeight = Double.parseDouble(previousWeightString);
                    if (previousWeight.equals(tagWeight)) {
                        continue;
                    } else {
                        previousWeight = Double.parseDouble(previousWeightString);
                    }
                }
                Double weightDiff = tagWeight - previousWeight;

                for (String similarDocId : termStringMap.keySet()) {

                    docIdsToBeUpdated.add(similarDocId);

                    Double similarDocWeight = Double.parseDouble(termStringMap.get(similarDocId));

                    Double previousSimilarity = 0d;
                    String previousSimilarityString = jedis.hget(indicatorsKey, similarDocId);
                    if (previousSimilarityString != null) {
                        previousSimilarity = Double.parseDouble(previousSimilarityString);
                    }
                    Double newSimilarity = previousSimilarity + weightDiff * similarDocWeight;

                    jedis.hset(indicatorsKey, similarDocId, newSimilarity.toString());
                }
            }

            jedis.hset(termKey, docId, tagWeight.toString());

            // LOGGER.info("This is the termMap called {}: {}", termKey, jedis.hgetAll(termKey));
        }
        docIdsToBeUpdated.add(docId);

        // LOGGER.info("This is the indicatorsMap called {}: {}", indicatorsKey, jedis.hgetAll(indicatorsKey));

        // LOGGER.info("These are the docIdsToBeUpdated: " + docIdsToBeUpdated);

        return generateUpdateRequests(jedis, tagProvider, docIdsToBeUpdated);
    }

    private List<String> generateUpdateRequests(Jedis jedis, String tagProvider, List<String> docIdsToBeUpdated) {
        List<String> updateRequests = new ArrayList<>();
        for (String id : docIdsToBeUpdated) {
            String key = getIndicatorsKey(tagProvider, id);
            Map<String, String> indicators = jedis.hgetAll(key);
            String indicatorFields = buildIndicatorsField(indicators, tagProvider);
            if (!indicatorFields.isEmpty()) {
                String indicatorFieldsAsJsonString = String.format(
                        elasticsearchRequestManager.getIndicatorsUpdateRequestTemplate(),
                        indicatorFields);
                if (indicatorFieldsAsJsonString != null) {
                    String updateRequest = elasticsearchRequestManager.buildIndicatorsUpdateRequest(id, indicatorFieldsAsJsonString);
                    updateRequests.add(updateRequest);

                    // LOGGER.info("This is an update request: " + updateRequest);
                }
            }
        }

        return updateRequests;
    }

    private String buildIndicatorsField(Map<String, String> indicators, String tagProvider) {
        ObjectNode indicatorsField = mapper.createObjectNode();
        try {
            ArrayNode indicatorsArray = mapper.createArrayNode();
            for (String docId : indicators.keySet()) {
                Double rating = Double.parseDouble(indicators.get(docId));
                ObjectNode indicator = mapper.createObjectNode();
                indicator.put("id", docId);
                indicator.put("rating", rating);
                indicatorsArray.add(indicator);
            }
            String tagProviderKey = tagProvider + elasticsearchConfig.getIndicatorsFieldSuffix();
            indicatorsField.set(tagProviderKey, indicatorsArray);

        } catch (Exception e) {
            LOGGER.error("JSON exception while initializing json.");
        }
        try {
            return mapper.writeValueAsString(indicatorsField);
        } catch (JsonProcessingException e) {
            LOGGER.error("JSON exception while processing " + indicatorsField);
            return null;
        }
    }

    public void updateDocuments(RestClient restClient, List<String> allIndicatorsUpdateRequests) {

        int chunkSize = elasticsearchConfig.getDocumentUpdateChunkSize();
        int offset = 0;
        int documentCount = allIndicatorsUpdateRequests.size();
        int totalUpdateCount = 0;

        while (offset < allIndicatorsUpdateRequests.size()) {
            StringBuffer bulkRequest = new StringBuffer();
            List<String> updateRequestChunk = allIndicatorsUpdateRequests.subList(offset, Math.min(offset + chunkSize, allIndicatorsUpdateRequests.size()));

            int updateRequestCounter = updateRequestChunk.size();
            updateRequestChunk.forEach(updateRequest -> bulkRequest.append(updateRequest));

            if (updateRequestCounter > 0) {
                // LOGGER.info("{}: Sending Bulk-Request for {} Document-Updates ...", name(), updateRequestCounter);
                // LOGGER.info(bulkRequest.toString());
                Response bulkResponse = elasticsearchRequestManager.performBulkRequest(restClient, bulkRequest.toString());
                StatusLine statusLine = bulkResponse.getStatusLine();
                if (statusLine.getStatusCode() < 400) {
                    LOGGER.info(statusLine.toString());
                    LOGGER.info("{}: {} Documents updated successfully (total number of Documents: {}).", name(),
                            updateRequestCounter, documentCount);
                    totalUpdateCount += updateRequestCounter;
                } else {
                    LOGGER.warn("ES bulk request threw status code {}: {}", statusLine.getStatusCode(), statusLine.getReasonPhrase());
                }
            } else {
                LOGGER.info("{}: Index is already up to date. {} Documents updated (total number of Documents: {}).", name(),
                        updateRequestCounter, documentCount);
            }
            offset += chunkSize;
        }
        LOGGER.info("{}: {} of {} Documents updated succesfully.", name(), totalUpdateCount, documentCount);
    }

    private static final HashMap<String, Double> getTagMap(ObjectNode kinesisJson) {
        JsonNode kinesisTags = kinesisJson.get("tags");
        HashMap<String, Double> tagMap = new HashMap<>();
        for (JsonNode kinesisTag : kinesisTags) {
            tagMap.put(kinesisTag.get("name").textValue(), kinesisTag.get("weight").asDouble());
        }
        // LOGGER.info("This is the tag map : " + tagMap.toString());
        return tagMap;
    }

    private static final String getTermKey(String tagProvider, String tagName) {
        return (tagProvider + "_term_" + tagName);
    }

    private static final String getIndicatorsKey(String tagProvider, String docId) {
        return (tagProvider + "_indicators_" + docId);
    }
}
