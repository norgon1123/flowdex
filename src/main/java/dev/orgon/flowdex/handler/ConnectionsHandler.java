package dev.orgon.flowdex.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import dev.orgon.flowdex.api.ApiException;
import dev.orgon.flowdex.api.Log;
import dev.orgon.flowdex.api.Params;
import dev.orgon.flowdex.api.Responses;
import dev.orgon.flowdex.store.Clients;
import dev.orgon.flowdex.store.IndexStore;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.LinkedHashMap;
import java.util.Map;

/** GET /connections?ip&from&to&limit&cursor */
public class ConnectionsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final IndexStore index;

    public ConnectionsHandler() {
        this(new IndexStore(Clients.dynamo(), Clients.table()));
    }

    ConnectionsHandler(IndexStore index) {
        this.index = index;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        String requestId = context.getAwsRequestId();
        try {
            Map<String, String> qs = event.getQueryStringParameters();
            String ip = Params.requireIp(qs);
            Params.Range range = Params.requireRange(qs);
            int limit = Params.limit(qs);
            String cursor = qs == null ? null : qs.get("cursor");

            IndexStore.Page page = index.queryConnections(ip, range.from(), range.to(), limit, cursor);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", page.items().stream().map(ConnectionsHandler::toItem).toList());
            if (page.nextCursor() != null) {
                body.put("nextCursor", page.nextCursor());
            }

            Log.event("connections.served", Map.of(
                    "requestId", requestId, "ip", ip, "returned", page.items().size(),
                    "hasMore", page.nextCursor() != null));

            return Responses.ok(200, body, requestId);
        } catch (ApiException e) {
            return Responses.error(e, requestId);
        } catch (RuntimeException e) {
            Log.event("connections.failed", Map.of("requestId", requestId, "exception", e.toString()));
            e.printStackTrace();
            return Responses.serverError(requestId);
        }
    }

    /** Rows are already oriented at write time, so this is a straight projection. */
    private static Map<String, Object> toItem(Map<String, AttributeValue> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        putString(item, row, "uid");
        putString(item, row, "ts");
        putString(item, row, "role");
        putString(item, row, "peer");
        putLong(item, row, "localPort");
        putLong(item, row, "peerPort");
        putString(item, row, "proto");
        putString(item, row, "service");
        putDouble(item, row, "duration");
        putLong(item, row, "bytesOut");
        putLong(item, row, "bytesIn");
        putString(item, row, "connState");
        putString(item, row, "s3Key");
        putLong(item, row, "s3Line");
        return item;
    }

    private static void putString(Map<String, Object> out, Map<String, AttributeValue> row, String field) {
        AttributeValue v = row.get(field);
        if (v != null && v.s() != null) {
            out.put(field, v.s());
        }
    }

    /**
     * Ports, byte counts and line numbers are always integers on the wire.
     * The type is chosen per field rather than by sniffing the stored string,
     * because DynamoDB trims trailing zeroes — a duration of 30.0 comes back as
     * "30", and sniffing would flip that field's JSON type from row to row.
     */
    private static void putLong(Map<String, Object> out, Map<String, AttributeValue> row, String field) {
        AttributeValue v = row.get(field);
        if (v != null && v.n() != null) {
            out.put(field, Long.valueOf(v.n()));
        }
    }

    /** duration is always floating point, even when its stored form has no decimal point. */
    private static void putDouble(Map<String, Object> out, Map<String, AttributeValue> row, String field) {
        AttributeValue v = row.get(field);
        if (v != null && v.n() != null) {
            out.put(field, Double.valueOf(v.n()));
        }
    }
}
