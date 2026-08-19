package dev.orgon.flowdex.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line of JSON per event so CloudWatch Logs Insights can query it.
 * Request bodies are never logged.
 */
public final class Log {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Log() {}

    public static void event(String message, Map<String, Object> fields) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("message", message);
        line.putAll(fields);
        try {
            System.out.println(MAPPER.writeValueAsString(line));
        } catch (Exception e) {
            System.out.println("{\"message\":\"" + message + "\",\"logError\":true}");
        }
    }
}
