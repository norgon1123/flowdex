package dev.orgon.flowdex.store;

import dev.orgon.flowdex.zeek.ConnRecord;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One stored index row, oriented to a single endpoint.
 *
 * Orienting bytes and ports at write time means read paths never need to know
 * which side of the connection they are looking at.
 */
public record IndexRow(
        String addr,
        Instant ts,
        String uid,
        String role,
        String peer,
        int localPort,
        int peerPort,
        String proto,
        String service,
        double duration,
        long bytesOut,
        long bytesIn,
        String connState,
        String s3Key,
        int s3Line) {

    /**
     * Distinct endpoints, originator first. A self-connection (loopback,
     * hairpin NAT) yields one endpoint: two rows would collide on PK+SK, and
     * one connection involving one address is the honest count anyway.
     */
    public static List<String> endpointsOf(ConnRecord r) {
        return r.origH().equals(r.respH()) ? List.of(r.origH()) : List.of(r.origH(), r.respH());
    }

    public static IndexRow forSide(ConnRecord r, String addr, String s3Key) {
        boolean isOrig = addr.equals(r.origH());
        return new IndexRow(
                addr,
                r.ts(),
                r.uid(),
                isOrig ? "orig" : "resp",
                isOrig ? r.respH() : r.origH(),
                isOrig ? r.origP() : r.respP(),
                isOrig ? r.respP() : r.origP(),
                r.proto(),
                r.service(),
                r.duration(),
                isOrig ? r.origBytes() : r.respBytes(),
                isOrig ? r.respBytes() : r.origBytes(),
                r.connState(),
                s3Key,
                r.line());
    }

    public String pk() { return Keys.pk(addr); }

    public String sk() { return Keys.connSk(ts, uid); }

    public Map<String, AttributeValue> item() {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("PK", s(pk()));
        item.put("SK", s(sk()));
        item.put("uid", s(uid));
        item.put("ts", s(Keys.formatTs(ts)));
        item.put("role", s(role));
        item.put("peer", s(peer));
        item.put("localPort", n(localPort));
        item.put("peerPort", n(peerPort));
        item.put("proto", s(proto));
        if (service != null) item.put("service", s(service));
        item.put("duration", n(duration));
        item.put("bytesOut", n(bytesOut));
        item.put("bytesIn", n(bytesIn));
        if (connState != null) item.put("connState", s(connState));
        item.put("s3Key", s(s3Key));
        item.put("s3Line", n(s3Line));
        return item;
    }

    private static AttributeValue s(String v) { return AttributeValue.builder().s(v).build(); }
    private static AttributeValue n(Number v) { return AttributeValue.builder().n(v.toString()).build(); }
}
