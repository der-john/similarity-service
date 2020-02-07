package de.zdf.service.similarity.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class SimilarityUtil {

    // cf https://stackoverflow.com/questions/109383/sort-a-mapkey-value-by-values
    public static final HashMap<String, Double> returnSubmapOfHighestValues(Map<String, Double> map, int submapSize) {
        List<Map.Entry<String, Double>> list = new ArrayList<>(map.entrySet());
        list.sort(Collections.reverseOrder(Map.Entry.comparingByValue()));

        HashMap<String, Double> result = new LinkedHashMap<>();
        int upperBound = Math.min(submapSize, list.size());
        for (Map.Entry<String, Double> entry : list.subList(0, upperBound)) {
            result.put(entry.getKey(), entry.getValue());
        }

        return result;
    }

    public static final HashMap<String, Double> getKinesisTagMap(ObjectNode kinesisJson) {
        JsonNode kinesisTags = kinesisJson.get("tags");
        HashMap<String, Double> tagMap = new HashMap<>();
        for (JsonNode kinesisTag : kinesisTags) {
            tagMap.put(kinesisTag.get("name").textValue(), kinesisTag.get("weight").asDouble());
        }
        // LOGGER.info("This is the tag map : " + tagMap.toString());
        return tagMap;
    }

    public static final String getTermKey(String tagProvider, String tagName) {
        return (tagProvider + "_term_" + tagName);
    }

    public static final String getIndicatorsKey(String tagProvider, String docId) {
        return (tagProvider + "_indicators_" + docId);
    }
}
