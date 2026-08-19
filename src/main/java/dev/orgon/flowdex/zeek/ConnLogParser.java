package dev.orgon.flowdex.zeek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orgon.flowdex.api.ApiException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ConnLogParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String[] REQUIRED = {
        "ts", "uid", "id.orig_h", "id.orig_p", "id.resp_h", "id.resp_p", "proto"
    };

    public ParseResult parse(String ndjson) {
        List<ConnRecord> records = new ArrayList<>();
        List<MalformedLine> malformed = new ArrayList<>();
        int lineNo = 0;

        for (String raw : ndjson.split("\n", -1)) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            try {
                records.add(toRecord(MAPPER.readTree(line), lineNo));
            } catch (ApiException e) {
                malformed.add(new MalformedLine(lineNo, e.getMessage()));
            } catch (Exception e) {
                malformed.add(new MalformedLine(lineNo, "not valid JSON"));
            }
        }
        return new ParseResult(List.copyOf(records), List.copyOf(malformed),
                records.size() + malformed.size());
    }

    /** Rejects the whole batch when more than 10% of lines failed to parse. */
    public void enforceMalformedThreshold(ParseResult result) {
        if (result.received() == 0) {
            throw ApiException.badRequest("EMPTY_BATCH", "body contained no records");
        }
        int bad = result.malformed().size();
        if (bad * 10 > result.received()) {
            throw ApiException.badRequest("MALFORMED_BATCH",
                    "more than 10% of lines failed to parse; wrong file?",
                    Map.of("received", result.received(), "malformed", bad));
        }
    }

    private ConnRecord toRecord(JsonNode n, int lineNo) {
        for (String field : REQUIRED) {
            JsonNode v = n.get(field);
            if (v == null || v.isNull()) {
                throw ApiException.badRequest("MALFORMED_LINE", "missing " + field);
            }
        }
        // A non-literal address (a hostname, say) would be indexed under a
        // PK that Params rejects on every read path, producing a row nothing
        // can ever address. Zeek always writes literal addresses, so this
        // rejects nothing legitimate.
        requireIpLiteral(n, "id.orig_h");
        requireIpLiteral(n, "id.resp_h");
        return new ConnRecord(
                toInstant(n.get("ts")),
                n.get("uid").asText(),
                n.get("id.orig_h").asText(),
                n.get("id.orig_p").asInt(),
                n.get("id.resp_h").asText(),
                n.get("id.resp_p").asInt(),
                n.get("proto").asText(),
                optionalString(n, "service"),
                optionalDouble(n, "duration"),
                optionalLong(n, "orig_bytes"),
                optionalLong(n, "resp_bytes"),
                optionalString(n, "conn_state"),
                lineNo);
    }

    private void requireIpLiteral(JsonNode n, String field) {
        String addr = n.get(field).asText();
        if (!dev.orgon.flowdex.api.Params.isLiteralAddress(addr)) {
            throw ApiException.badRequest("MALFORMED_LINE", field + " is not an IP address");
        }
    }

    /**
     * Zeek ts is epoch seconds as a double. BigDecimal, not double arithmetic:
     * 1787061802.451 * 1000 in binary floating point lands on ...450.9999, and
     * a millisecond lost here is a millisecond lost in every sort key.
     */
    private Instant toInstant(JsonNode ts) {
        if (!ts.isNumber() && !isNumericText(ts)) {
            throw ApiException.badRequest("MALFORMED_LINE", "ts is not a number");
        }
        BigDecimal seconds = new BigDecimal(ts.asText());
        return Instant.ofEpochMilli(seconds.movePointRight(3).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact());
    }

    private boolean isNumericText(JsonNode n) {
        try {
            new BigDecimal(n.asText());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Zeek writes "-" for unset fields in some exports; treat it as absent. */
    private boolean isAbsent(JsonNode n) {
        return n == null || n.isNull() || (n.isTextual() && n.asText().equals("-"));
    }

    private String optionalString(JsonNode parent, String field) {
        JsonNode n = parent.get(field);
        return isAbsent(n) ? null : n.asText();
    }

    private double optionalDouble(JsonNode parent, String field) {
        JsonNode n = parent.get(field);
        return isAbsent(n) ? 0.0 : n.asDouble(0.0);
    }

    private long optionalLong(JsonNode parent, String field) {
        JsonNode n = parent.get(field);
        return isAbsent(n) ? 0L : n.asLong(0L);
    }
}
