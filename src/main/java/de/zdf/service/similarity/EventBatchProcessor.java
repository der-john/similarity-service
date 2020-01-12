package de.zdf.service.similarity;

import com.amazonaws.services.kinesis.model.Record;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.zdf.service.similarity.config.ElasticsearchConfig;
import de.zdf.service.similarity.config.RedisConfig;
import de.zdf.service.similarity.elasticsearch.ElasticsearchRequestManager;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.StatusLine;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.*;

@Component
public class EventBatchProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventBatchProcessor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CharsetDecoder DECODER = Charset.forName("UTF-8").newDecoder();
    private static final String ZOMBIE = "ZOMBIE";

    @Autowired
    private RedisConfig redisConfig;
    @Autowired
    private ElasticsearchConfig elasticsearchConfig;
    @Autowired
    private ElasticsearchRequestManager elasticsearchRequestManager;
    @Autowired
    private ObjectMapper mapper;

    @Value("${similarity.tagProviders:corpus}")
    private String tagProvidersString;

    @Value("${similarity.minWeightDiff:0.0001}")
    private Double minWeightDiff;

    @Value("${similarity.maxIndicators:25}")
    private Integer maxIndicators;

    @Value("${similarity.maxDocsPerTerm:30}")
    private Integer maxDocsPerTerm;

    @Value("${similarity.maxTermsPerDoc:15}")
    private Integer maxTermsPerDoc;

    private String[] getTagProviders() {
        return tagProvidersString.trim().split(",");
    }

    public String name() {
        return "SimilarityService";
    }

    public void processEventBatch(final List<Record> records) {
        long bulkStartTime = System.nanoTime();

        try (JedisPool pool = new JedisPool(redisConfig.getHost(), redisConfig.getPort())) {
            try (Jedis jedis = pool.getResource()) {

                int i = 1;
                Set<Pair> indicatorFieldsForBatch = new HashSet<>();

                for (Record record : records) {
                    try {

                        if (indicatorFieldsForBatch.size() > elasticsearchConfig.getDocumentUpdateChunkSize()) {

                            List<String> currentUpdateRequests = generateUpdateRequests(jedis, indicatorFieldsForBatch);
                            final RestClient restClient = elasticsearchRequestManager.getRestClient();
                            updateDocuments(restClient, currentUpdateRequests);
                            restClient.close();

                            indicatorFieldsForBatch = new HashSet<>();
                        }

                        String dataString = DECODER.decode(record.getData()).toString();
                        ObjectNode kinesisJson = (ObjectNode) MAPPER.readTree(dataString);

                        String docIdToBeDeleted = checkActionAndGetId(kinesisJson);

                        if (docIdToBeDeleted != null) {

                            Set<Pair> affectedIndicatorFields = handleDeletion(jedis, docIdToBeDeleted);
                            indicatorFieldsForBatch.addAll(affectedIndicatorFields);


                            LOGGER.info("Received {} update requests due to deletion of {} ({}/{}).",
                                    affectedIndicatorFields.size(), docIdToBeDeleted, i, records.size());
                            i++;

                            continue;
                        }

                        if (null == kinesisJson.get("docId") || null == kinesisJson.get("tagProvider")) {
                            continue;
                        }

                        String docId = kinesisJson.get("docId").textValue();
                        String tagProvider = kinesisJson.get("tagProvider").textValue();

                        HashMap<String, Double> kinesisTagMap = getKinesisTagMap(kinesisJson);
                        HashMap<String, Double> tagMap = returnSubmapOfHighestValues(kinesisTagMap, maxTermsPerDoc);

                        waitForRedis(jedis);

                        Set<Pair> indicatorFields = calculateIndicators(jedis, docId, tagProvider, tagMap);

                        indicatorFieldsForBatch.addAll(indicatorFields);

                        LOGGER.info("Received {} update requests due to update/creation of {} ({}/{}).",
                                indicatorFields.size(), docId, i, records.size());
                        i++;

                    } catch (IOException e) {
                        LOGGER.error("IO Exception: ", e);
                    } catch (Exception e) {
                        LOGGER.error("Couldn't process the following record: " + record, e);
                    }
                }

                try {
                    List<String> currentUpdateRequests = generateUpdateRequests(jedis, indicatorFieldsForBatch);
                    final RestClient restClient = elasticsearchRequestManager.getRestClient();
                    updateDocuments(restClient, currentUpdateRequests);
                    restClient.close();

                } catch (IOException e) {
                    LOGGER.error("IO Exception: ", e);
                }
            }
        }
        long bulkEndTime = System.nanoTime();
        final double processTimeInMs = (bulkEndTime - bulkStartTime) / (1000 * 1000.);
        LOGGER.info("Processing time: {} ms for {} docs, giving us an average of {} ms per doc",
                processTimeInMs,
                records.size(),
                processTimeInMs/records.size());
    }
    private Set<Pair> handleDeletion(Jedis jedis, String docIdToBeDeleted) {
        Set<Pair> indicatorFieldsForBatch = new HashSet<>();
        for (String provider : getTagProviders()) {

            Set<Pair> indicatorFieldsToBeUpdated = removeIndicators(jedis, docIdToBeDeleted, provider);

            if (CollectionUtils.isEmpty(indicatorFieldsToBeUpdated)) {
                continue;
            }
            indicatorFieldsForBatch.addAll(indicatorFieldsToBeUpdated);
        }
        return indicatorFieldsForBatch;
    }

    private Set<Pair> removeIndicators(Jedis jedis, String docIdToBeDeleted, String tagProvider) {
        Set<Pair> indicatorFieldsToBeUpdated = new HashSet<>();
        String indicatorsKey = getIndicatorsKey(tagProvider, docIdToBeDeleted);
        Map<String, String> indicatorsMap = jedis.hgetAll(indicatorsKey);

        // Due to changes in tag provider setup, this exact indicator map may not exist
        if (null != indicatorsMap) {
            for (String affectedDocId : indicatorsMap.keySet()) {
                String affectedIndicatorsKey = getIndicatorsKey(tagProvider, affectedDocId);
                jedis.hdel(affectedIndicatorsKey, docIdToBeDeleted);
                indicatorFieldsToBeUpdated.add(Pair.of(affectedDocId, tagProvider));
            }
        }

        jedis.del(indicatorsKey);
        indicatorFieldsToBeUpdated.add(Pair.of(docIdToBeDeleted, tagProvider));

        return indicatorFieldsToBeUpdated;
    }

    private String checkActionAndGetId (JsonNode jsonObject) {
        Object action = jsonObject.get("action");
        if (action != null && StringUtils.equals(jsonObject.get("action").textValue(), "delete")) {
            String docId = jsonObject.get("_id").textValue();
            if (StringUtils.isNotBlank(docId)) {
                return docId;
            }
        }
        return null;
    }

    private void waitForRedis(Jedis jedis)  {
        boolean isRedisHealthy = "PONG".equals(jedis.ping());
        while (!isRedisHealthy) {
            isRedisHealthy = "PONG".equals(jedis.ping());
            try {
                LOGGER.info("Waiting for Redis.");
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private Set<Pair> calculateIndicators(
            Jedis jedis, String docId, String tagProvider, HashMap<String, Double> tagMap) {

        Set<Pair> indicatorFieldsToBeUpdated = new HashSet<>();

        String indicatorsKey = getIndicatorsKey(tagProvider, docId);
        boolean hasAnyTagChanged = false;

        for (String tagName : tagMap.keySet()) {

            Double tagWeight = tagMap.get(tagName);
            if (tagWeight == 0d) {
                continue;
            }

            String termKey = getTermKey(tagProvider, tagName);

            Map<String, String> termStringMap = jedis.hgetAll(termKey);
            if (MapUtils.isNotEmpty(termStringMap)) {
                TreeMap<Double, String> termMap = convertToTreeMap(termStringMap);

                if (termMap.size() + 1 > maxDocsPerTerm) {
                    Double lowestScoreInMap = termMap.firstKey();
                    if (tagWeight < lowestScoreInMap) {
                        continue;
                    }
                    jedis.hdel(termKey, termMap.firstEntry().getValue());
                    termMap.remove(termMap.firstKey());
                }

                // cleanup legacy inflated maps
                while (termMap.size() > maxDocsPerTerm) {
                    jedis.hdel(termKey, termMap.firstEntry().getValue());
                    termMap.remove(termMap.firstKey());
                }

                jedis.hset(termKey, tagName, tagWeight.toString());
                termMap.put(tagWeight, tagName);

                Double weightDiff = getTagWeightDiff(tagWeight, termStringMap.get(docId));
                if (weightDiff == null) continue;

                for (Map.Entry<Double, String> entry : termMap.entrySet()) {
                    String similarDocId = entry.getValue();
                    if (similarDocId.equals(docId)) {
                        continue;
                    }

                    Double similarDocWeight = entry.getKey();

                    // cleanup legacy zero-score entries
                    if (similarDocWeight == 0d) {
                        jedis.hdel(termKey, similarDocId);
                        continue;
                    }

                    hasAnyTagChanged = true;
                    indicatorFieldsToBeUpdated.add(Pair.of(similarDocId, tagProvider));

                    Double previousSimilarity = 0d;
                    String previousSimilarityString = jedis.hget(indicatorsKey, similarDocId);
                    if (previousSimilarityString != null) {
                        previousSimilarity = Double.parseDouble(previousSimilarityString);
                    }
                    Double newSimilarity = previousSimilarity + weightDiff * similarDocWeight;

                    String similarDocIndicatorsKey = getIndicatorsKey(similarDocId, tagProvider);
                    if (newSimilarity == 0d) {
                        jedis.hdel(indicatorsKey, similarDocId);
                        jedis.hdel(similarDocIndicatorsKey, docId);
                    } else {
                        jedis.hset(indicatorsKey, similarDocId, newSimilarity.toString());
                        jedis.hset(similarDocIndicatorsKey, docId, newSimilarity.toString());
                    }
                }
            } else {
                hasAnyTagChanged = true;
                jedis.hset(termKey, docId, tagWeight.toString());
            }

            LOGGER.info("This is the termMap called {}: {}", termKey, jedis.hgetAll(termKey));
        }
        if (hasAnyTagChanged) {
            indicatorFieldsToBeUpdated.add(Pair.of(docId, tagProvider));
        }

        // LOGGER.info("This is the indicatorsMap called {}: {}", indicatorsKey, jedis.hgetAll(indicatorsKey));

        // LOGGER.info("These are the indicatorFieldsToBeUpdated: " + indicatorFieldsToBeUpdated);

        return indicatorFieldsToBeUpdated;
    }

    private static final TreeMap<Double, String> convertToTreeMap(Map<String, String> termStringMap) {
        TreeMap<Double, String> treeMap = new TreeMap<>();
        for (String docId : termStringMap.keySet()) {
            Double score = Double.parseDouble(termStringMap.get(docId));
            treeMap.put(score, docId);
        }
        return treeMap;
    }

    private Double getTagWeightDiff(Double tagWeight, String previousWeightString) {
        Double previousWeight = 0d;
        if (previousWeightString != null) {
            previousWeight = Double.parseDouble(previousWeightString);
            if (isMoreOrLessEqual(previousWeight, tagWeight)) {
                return null;
            } else {
                previousWeight = Double.parseDouble(previousWeightString);
            }
        }
        return tagWeight - previousWeight;
    }

    private boolean isMoreOrLessEqual(Double previousWeight, Double tagWeight) {
        if (tagWeight.equals(previousWeight)) {
            return true;
        }

        if (0.9 * previousWeight > tagWeight || 1.1 * previousWeight < tagWeight) {
            return true;
        }

        if (tagWeight - previousWeight < minWeightDiff || previousWeight - tagWeight < minWeightDiff) {
            return true;
        }
        return false;
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
                    elasticsearchRequestManager.getIndicatorsUpdateRequestTemplate(),
                    indicatorFields);
            if (indicatorFieldsAsJsonString != null) {
                String updateRequest = elasticsearchRequestManager.buildIndicatorsUpdateRequest(id, indicatorFieldsAsJsonString);
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
                if (null == bulkResponse || null == bulkResponse.getStatusLine()) {
                    LOGGER.warn("ES bulk request failed and didn't even throw a response.");
                    return;
                }
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
    }

    // cf https://stackoverflow.com/questions/109383/sort-a-mapkey-value-by-values
    private static final HashMap<String, Double> returnSubmapOfHighestValues(Map<String, Double> map, int submapSize) {
        List<Map.Entry<String, Double>> list = new ArrayList<>(map.entrySet());
        list.sort(Collections.reverseOrder(Map.Entry.comparingByValue()));

        HashMap<String, Double> result = new LinkedHashMap<>();
        int upperBound = Math.min(submapSize, list.size());
        for (Map.Entry<String, Double> entry : list.subList(0, upperBound)) {
            result.put(entry.getKey(), entry.getValue());
        }

        return result;
    }

    private static final HashMap<String, Double> getKinesisTagMap(ObjectNode kinesisJson) {
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
