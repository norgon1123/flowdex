package dev.orgon.flowdex.api;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The header an analyst quotes has to be the id the GATEWAY logged, not the id
 * the Lambda invocation happened to get. They are different values, and only
 * one of them appears in the gateway's access logs and x-amzn-RequestId.
 */
class RequestIdTest {

    @Test
    void theGatewayRequestIdWinsOverTheLambdaInvocationId() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent()
                .withRequestContext(new APIGatewayProxyRequestEvent.ProxyRequestContext()
                        .withRequestId("gateway-abc"));

        assertThat(RequestId.of(event, context("lambda-xyz"))).isEqualTo("gateway-abc");
    }

    /** Console tests and direct SDK invokes carry no request context at all. */
    @Test
    void fallsBackToTheLambdaIdWhenThereIsNoRequestContext() {
        assertThat(RequestId.of(new APIGatewayProxyRequestEvent(), context("lambda-xyz")))
                .isEqualTo("lambda-xyz");
    }

    @Test
    void fallsBackWhenTheRequestContextCarriesNoId() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent()
                .withRequestContext(new APIGatewayProxyRequestEvent.ProxyRequestContext());

        assertThat(RequestId.of(event, context("lambda-xyz"))).isEqualTo("lambda-xyz");
    }

    /** Never null: Map.of would throw, and this is the path that must never throw. */
    @Test
    void yieldsAnEmptyStringWhenNeitherIdExists() {
        assertThat(RequestId.of(new APIGatewayProxyRequestEvent(), context(null))).isEmpty();
        assertThat(RequestId.of(null, null)).isEmpty();
    }

    private static Context context(String awsRequestId) {
        return new Context() {
            @Override public String getAwsRequestId() { return awsRequestId; }
            @Override public String getLogGroupName() { return "test"; }
            @Override public String getLogStreamName() { return "test"; }
            @Override public String getFunctionName() { return "test"; }
            @Override public String getFunctionVersion() { return "1"; }
            @Override public String getInvokedFunctionArn() { return "arn:test"; }
            @Override public CognitoIdentity getIdentity() { return null; }
            @Override public ClientContext getClientContext() { return null; }
            @Override public int getRemainingTimeInMillis() { return 30_000; }
            @Override public int getMemoryLimitInMB() { return 1024; }
            @Override public LambdaLogger getLogger() { return null; }
        };
    }
}
