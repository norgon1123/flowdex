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
            body.put("nextCursor", page.nextCursor());

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
        putIfPresent(item, row, "uid", false);
        putIfPresent(item, row, "ts", false);
        putIfPresent(item, row, "role", false);
        putIfPresent(item, row, "peer", false);
        putIfPresent(item, row, "localPort", true);
        putIfPresent(item, row, "peerPort", true);
        putIfPresent(item, row, "proto", false);
        putIfPresent(item, row, "service", false);
        putIfPresent(item, row, "duration", true);
        putIfPresent(item, row, "bytesOut", true);
        putIfPresent(item, row, "bytesIn", true);
        putIfPresent(item, row, "connState", false);
        putIfPresent(item, row, "s3Key", false);
        putIfPresent(item, row, "s3Line", true);
        return item;
    }

    private static void putIfPresent(Map<String, Object> out, Map<String, AttributeValue> row,
                                     String field, boolean numeric) {
        AttributeValue v = row.get(field);
        if (v == null) {
            return;
        }
        if (numeric) {
            String n = v.n();
            out.put(field, n.contains(".") ? Double.valueOf(n) : Long.valueOf(n));
        } else if (v.s() != null) {
            out.put(field, v.s());
        }
    }
}
