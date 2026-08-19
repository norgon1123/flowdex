package dev.orgon.flowdex.summary;

public record PeerStat(String addr, long connections, long bytesOut, long bytesIn) {
}
