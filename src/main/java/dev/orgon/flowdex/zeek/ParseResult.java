package dev.orgon.flowdex.zeek;

import java.util.List;

/** received counts non-blank lines: records.size() + malformed.size(). */
public record ParseResult(List<ConnRecord> records, List<MalformedLine> malformed, int received) {
}
