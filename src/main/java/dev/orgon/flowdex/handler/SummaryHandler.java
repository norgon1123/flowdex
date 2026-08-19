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
import dev.orgon.flowdex.store.Keys;
import dev.orgon.flowdex.summary.HourWindow;
import dev.orgon.flowdex.summary.PeerStat;
import dev.orgon.flowdex.summary.Summary;
import dev.orgon.flowdex.summary.SummaryBuilder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** GET /ip/{addr}/summary?from&to */
public class SummaryHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final IndexStore index;

    public SummaryHandler() {
        this(new IndexStore(Clients.dynamo(), Clients.table()));
    }

    SummaryHandler(IndexStore index) {
        this.index = index;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        String requestId = context.getAwsRequestId();
        try {
            String addr = Params.pathAddr(event);
            Params.Range range = Params.requireRange(event.getQueryStringParameters());
            HourWindow hours = HourWindow.of(range.from(), range.to());

            Summary summary = SummaryBuilder.build(
                    addr,
                    range,
                    index.queryRollups(addr, hours.fromHour(), hours.lastHour()),
                    index.scanPeers(addr, range.from(), range.to()),
                    index.edge(addr, range.from(), range.to(), true),
                    index.edge(addr, range.from(), range.to(), false));

            Log.event("summary.served", Map.of(
                    "requestId", requestId, "addr", addr,
                    "connections", summary.connections(), "truncated", summary.truncated()));

            return Responses.ok(200, toBody(summary), requestId);
        } catch (ApiException e) {
            return Responses.error(e, requestId);
        } catch (RuntimeException e) {
            Log.event("summary.failed", Map.of("requestId", requestId, "exception", e.toString()));
            e.printStackTrace();
            return Responses.serverError(requestId);
        }
    }

    private static Map<String, Object> toBody(Summary s) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("addr", s.addr());
        body.put("window", Map.of("from", Keys.formatTs(s.from()), "to", Keys.formatTs(s.to())));
        body.put("windowCovered", Map.of(
                "from", Keys.formatTs(s.coveredFrom()), "to", Keys.formatTs(s.coveredTo())));
        body.put("connections", s.connections());
        body.put("bytesOut", s.bytesOut());
        body.put("bytesIn", s.bytesIn());
        body.put("protocols", s.protocols());
        body.put("topPeers", s.topPeers().stream().map(SummaryHandler::peer).toList());
        body.put("firstSeen", format(s.firstSeen()));
        body.put("lastSeen", format(s.lastSeen()));
        body.put("truncated", s.truncated());
        return body;
    }

    private static Map<String, Object> peer(PeerStat p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("addr", p.addr());
        m.put("connections", p.connections());
        m.put("bytesOut", p.bytesOut());
        m.put("bytesIn", p.bytesIn());
        return m;
    }

    /** Null rather than an empty string: "never seen" is not "seen at the epoch". */
    private static String format(Instant i) {
        return i == null ? null : Keys.formatTs(i);
    }
}
