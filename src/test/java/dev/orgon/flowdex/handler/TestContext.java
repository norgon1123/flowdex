package dev.orgon.flowdex.handler;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;

record TestContext(String requestId) implements Context {
    @Override public String getAwsRequestId() { return requestId; }
    @Override public String getLogGroupName() { return "test"; }
    @Override public String getLogStreamName() { return "test"; }
    @Override public String getFunctionName() { return "test"; }
    @Override public String getFunctionVersion() { return "1"; }
    @Override public String getInvokedFunctionArn() { return "arn:test"; }
    @Override public CognitoIdentity getIdentity() { return null; }
    @Override public ClientContext getClientContext() { return null; }
    @Override public int getRemainingTimeInMillis() { return 30_000; }
    @Override public int getMemoryLimitInMB() { return 1024; }
    @Override public LambdaLogger getLogger() { return new LambdaLogger() {
        @Override public void log(String message) { System.out.println(message); }
        @Override public void log(byte[] message) { System.out.println(new String(message)); }
    }; }
}
