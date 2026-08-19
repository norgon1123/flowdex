package dev.orgon.flowdex.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;

/**
 * The id every response echoes as x-flowdex-request-id.
 *
 * There are two request ids in play and they are not interchangeable.
 * Context.getAwsRequestId() identifies the Lambda INVOCATION; it appears in the
 * function's own log stream and nowhere else. The API Gateway request id
 * identifies the HTTP REQUEST; it is what the gateway puts in its access logs
 * and returns in x-amzn-RequestId, and so it is the only one an analyst holding
 * a response can use to find the request again. Prefer it.
 *
 * The Lambda id remains the fallback for invocations that did not arrive
 * through the gateway — a console test, a direct SDK invoke — where an empty
 * header would leave nothing at all to correlate.
 */
public final class RequestId {

    private RequestId() {}

    public static String of(APIGatewayProxyRequestEvent event, Context context) {
        String gateway = event == null || event.getRequestContext() == null
                ? null
                : event.getRequestContext().getRequestId();
        if (gateway != null && !gateway.isBlank()) {
            return gateway;
        }
        String invocation = context == null ? null : context.getAwsRequestId();
        return invocation == null ? "" : invocation;
    }
}
