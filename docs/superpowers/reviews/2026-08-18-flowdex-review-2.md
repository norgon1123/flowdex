# flowdex — second design review

Date: 2026-08-18
Reviewers: four independent high-effort passes (write path, data model + read
path, infrastructure, cross-layer coherence) over spec, plan, and the five
commits of implementation on `flowdex-impl`.

Scope: does the design still hold now that implementation has begun. This
review supersedes nothing in the plan; it lists what the first review missed
and where the implementation has since drifted.

## Verdict

The architecture is sound and the hard parts are right. The single-table key
design, the exclusive-upper-bound argument, the idempotency mechanism, the byte
orientation, the hour-coverage math, and the SnapStart/alias/IAM Terraform
wiring were all attacked directly and all held.

Four of the first review's five findings stand. **Finding 1 does not.** The
concurrency fix it prescribes is necessary but not sufficient, and the batch cap
it introduces does not keep ingest inside the 29-second ceiling. That is the one
decision that needs to be remade before Task 9 is written.

Below, `[xN]` marks how many independent reviewers reached the finding.

---

## Blockers

### B1. The 29-second ingest budget is not met. Finding 1 is wrong as resolved. [x2]

The plan's resolution — 32 worker threads, 20,000-record cap — rests on four
assumptions that do not survive contact:

1. **Per-partition write ceiling.** DynamoDB caps ~1,000 WCU/s *per partition*,
   in every billing mode, and adaptive capacity cannot split a single partition
   key. A scan-shaped file — one scanner IP against 20,000 targets, which is
   precisely the file this tool exists to ingest — puts the scanner's index row
   (2 WCU, transactional) plus the scanner's rollup update (2 WCU) on one
   partition for every record. 20,000 × 4 WCU = 80,000 WCU through one
   partition ≥ **80 seconds**, before any contention. No amount of client
   concurrency changes this.
2. **Single-item rollup contention.** Every record for one IP+hour updates the
   same rollup item. 32 optimistic transactions on one item thrash with
   `TransactionConflict`. The retry ladder (`MAX_ATTEMPTS = 6`, `20·2ⁿ` capped
   at 800 ms) totals **620 ms of sleep with no jitter**, so all 32 threads retry
   in lockstep and exhaust the budget in under a second of real contention →
   500 mid-batch. Verified at plan lines 1899–1935.
3. **On-demand warm-up.** A new table serves ~4,000 WCU/s and grows by doubling
   previous peak. A full batch is 40,000 txns × 4 WCU = 160,000 WCU; at the
   claimed 10–15 s that is 11–16k WCU/s. The README's own demo path — first
   ingest into a fresh stack — throttles hard. Even self-paced,
   160,000 ÷ 4,000 = 40 s > 29 s.
4. **Arithmetic.** The finding sizes the pool against 34,000 transactions
   (5 MB ÷ ~300 B). But `MAX_RECORDS = 20_000` (plan:3318) admits **40,000**
   transactions. The margin is thinner than presented.

Recommended, in order:

- Shard the work queue **by partition key** so one worker owns each IP. This
  eliminates rollup conflicts entirely (the retry path becomes the rare case it
  was assumed to be) while keeping cross-IP parallelism. It is also the better
  interview answer.
- Lower `MAX_RECORDS` to ~5,000–7,000, or make the cap per-distinct-IP.
- Deepen the retry: full jitter, and budget against
  `context.getRemainingTimeInMillis()` rather than a fixed ladder.
- Document the single-hot-IP ceiling in the README's ceiling list as a real
  413/504 mode, not a theoretical one. Idempotency makes a 504 safely
  re-runnable — say so.

### B2. Gzip ingest is dead on arrival, and no test can catch it. [x2]

`binary_media_types = ["application/gzip", "application/octet-stream"]`
(plan:4259) matches the request **Content-Type**. Spec §7 promises
`Content-Encoding: gzip`. The two are different headers:

| Client sends | Result |
|---|---|
| `Content-Type: application/x-ndjson` + `Content-Encoding: gzip` (natural pairing) | API Gateway treats the body as UTF-8 text, mangles it, handler returns 400 — always |
| `Content-Type: application/gzip`, no `Content-Encoding` | body arrives intact, but `isGzip()` keys on content-encoding only, so it is never gunzipped → parse failure |
| both headers set | works — documented nowhere |

LocalStack ITs invoke handlers directly with constructed events, so the gateway
layer is never exercised and no planned test fails.

Fix: `binary_media_types = ["*/*"]` (safe under `AWS_PROXY`; the handler already
branches on `isBase64Encoded`), **and** sniff the gzip magic bytes `1f 8b` in
`Body.decode` rather than trusting a header, **and** put the exact working curl
in the README. Note the deployment redeploy trigger must also be extended (M9)
or this fix will not reach the live stage.

---

## Significant

### S1. IPv6 addresses have no canonical form on either path. [x4 — all reviewers]

The write path stores `id.orig_h` / `id.resp_h` **verbatim and unvalidated**
into the partition key. The read path (`Params.isLiteralAddress`) accepts any
spelling `InetAddress` will parse and passes it through un-normalized. So
`2001:db8::1`, `2001:DB8::1`, `2001:db8:0:0:0:0:0:1`, and `::ffff:10.0.0.5` are
four different partitions, three of them permanently empty — a `200` with zero
rows, which is exactly the silent-wrong-answer failure the spec's own truncation
rationale (§8) argues against.

This is the missing half of commit `fa369fb`. That commit's reasoning —
"`2130706433` could only ever build a partition key that matches nothing. A 400
beats a silent empty result" — is correct and is not applied to IPv6.

Fix: one canonicalizer in `Keys`, applied at **both** write and read. Caveat:
`Inet6Address.getHostAddress()` is *not* RFC 5952 (it emits the uncompressed
form), so it does not match Zeek's output — either write a small RFC 5952
formatter, or adopt `getHostAddress()` as the canonical form on both paths
consistently. Also validate addresses at ingest: a line with a garbage
`id.orig_h` should be a malformed line, not a poisoned partition key.

### S2. Finding 5 (cursor binding) is incompletely resolved — several shapes are 500s, not 400s. [x1, verified against code]

`CursorCodec.decode` validates PK presence and match only. These reach DynamoDB
and die as `ValidationException` → generic handler arm → **500**:

- A legitimate cursor replayed with a narrower or shifted `from`/`to`, so its SK
  falls outside the new `BETWEEN`. Real DynamoDB rejects an `ExclusiveStartKey`
  outside the key-condition range. This is the *common* misuse, not an exotic
  one.
- A hand-crafted SK pointing at an `H#` rollup row.
- Extra attributes: `decode` copies **every** JSON entry into the start key
  (`plain.forEach(...)`), and a 3-attribute start key on a 2-attribute schema is
  a ValidationException.
- An SK over the 1024-byte sort-key limit. There is no cursor length cap.

LocalStack is laxer than real DynamoDB about start-key validation, so the
planned IT can pass while production 500s.

Fix: copy exactly `PK` and `SK` and reject anything else; require the SK to start
with `C#`; require `connBound(from) < SK <= connBound(to)`; cap cursor length at
~1 KB; and map any DynamoDB ValidationException mentioning the starting key to a
400 as a backstop.

Sound as implemented: flattening to `Map<String,String>` instead of serializing
SDK v2 `AttributeValue` is the right call — `AttributeValue` is not
Jackson-round-trippable.

### S3. The two load-bearing tests are vacuous. [x1, cross-checked]

- **Truncation.** Spec §10 case 6 requires a fixture exceeding the peer row
  budget. Nothing anywhere seeds >5,000 rows.
  `SummaryBuilderTest.topPeersAreCappedAtTenAndTruncationIsCarriedThrough`
  passes a pre-built `PeerScan(rows, true)` — it asserts that a flag the test
  itself set survives. The budget logic in `scanPeers` is never driven true,
  including the exactly-5000-rows edge (LastEvaluatedKey present, no 6th page →
  likely false-positive `truncated`). Spec §10's unit item "peer tally and its
  truncation boundary" evaporated when truncation moved out of `PeerTally`.
- **Transaction-conflict retry.** The plan calls this "load-bearing; without it
  concurrent ingest silently drops rows," then proves it with
  `concurrentWritesToOneRollupAllLand` on LocalStack — which does not reliably
  emit `TransactionCanceledException(TransactionConflict)` under contention.
  This is the same argument the plan itself uses to skip the 503 test. If
  LocalStack serializes the 40 transactions, the test passes with the retry loop
  deleted. The reason-classification logic has zero direct coverage.

Fix: unit-test `writeRow` against a stub `DynamoDbClient` throwing crafted
`TransactionCanceledException`s per reason list; keep the LocalStack test as a
smoke test. Make `PEER_PAGE_SIZE` test-visible and assert both the 5001 and
exactly-5000 cases.

### S4. The response carries the wrong request ID. [x1, verified]

Spec §8 and §9: "Every response carries the **API Gateway request ID**." All
three handlers use `context.getAwsRequestId()` — the *Lambda invocation* ID. The
gateway ID is `event.getRequestContext().getRequestId()`, and it is the one that
appears in gateway access logs and `x-amzn-RequestId`. An analyst quoting
`x-flowdex-request-id` to correlate with gateway logs finds nothing.
`TestContext` fabricates the ID, so no test can catch it.

Fix: source from the event's request context, fall back to the Lambda ID, and
assert the event-sourced value wins. Separately, gateway-generated 403/429
responses carry neither the header nor the error envelope — spec §8's "errors
share one shape" is unachievable for those; one README caveat suffices.

### S5. HTTP connection pooling invalidates the per-transaction latency assumption. [x1, verified]

`UrlConnectionHttpClient.builder().build()` (plan:3049) wraps
`HttpURLConnection`, whose keep-alive cache holds **5 idle connections per
host** (`http.maxConnections`). With 32 workers against one DynamoDB endpoint,
~27 of every 32 completed connections are discarded and most requests pay a
fresh TLS handshake — tens of milliseconds and CPU-heavy. On
`memory_size = 1024` (plan:4205) that is **~0.58 vCPU**, not the "1 vCPU" the
finding claims, so handshake crypto serializes on a fractional core.

Fix: `System.setProperty("http.maxConnections", "34")` in `Clients` static init
so it is baked into the snapshot, or switch to
`ApacheHttpClient.builder().maxConnections(50)`. Consider 2048 MB (≥1.15 vCPU)
and measure. Correct the "1 vCPU" narrative either way.

### S6. Both read handlers get write permissions. [x1]

One shared `aws_iam_role` for all three functions grants ConnectionsHandler and
SummaryHandler `dynamodb:PutItem`, `UpdateItem`, and `s3:PutObject`. The plan's
own comment says "scoped to exactly the one table and the one bucket, and no
further" — per-function scoping is precisely what a reviewer checks.

Fix: `aws_iam_role` + `aws_iam_role_policy` with `for_each = local.functions`;
ingest gets PutItem/UpdateItem + s3:PutObject, readers get Query only. Also drop
`dynamodb:GetItem` and `s3:GetObject` — nothing calls either.

### S7. Ingest's Lambda timeout should exceed the gateway's, not match it. [x1]

The plan sets both to 29 s, reasoning that "a function that outlives the gateway
only burns money." That is backwards for an idempotent write path: if both die
at 29 s, indexing stops mid-flight and the client must re-POST from scratch. Let
ingest run to 60–120 s — the 504'd request *completes in the background*, the
client's retry reports all-duplicates, and partial states become rare instead of
routine. Costs fractions of a cent. Keep 29 s on the two readers.

### S8. Spec, plan, and code have diverged in four places nobody reconciled. [x2]

1. **Task 13 says to copy the spec's §7 ingest diagram "verbatim"** — but that
   diagram still shows a sequential loop over "each record, each endpoint",
   which findings 1 and 3 both replaced. The README would carry a diagram
   contradicting its own design-decisions bullets two sections later.
2. **Spec §6.2 still documents the nested `proto` map** with
   `SET proto.#p = if_not_exists(...)`. That mechanism was proven impossible
   (overlapping document paths) and replaced with top-level `proto#<name>` +
   `ADD` (`Keys.protoAttr`). The plan was amended; the spec never was. This is
   an undeclared **sixth** deviation and belongs in the README — it is also a
   good interview story, since the spec's version is subtly broken as written.
3. **Commit `fa369fb` was never back-ported into plan Task 4**, which still
   prints the DNS-vulnerable `addr.matches("[0-9.]+")` version. Anyone
   re-executing Task 4 reintroduces the bug.
4. **Spec §6.2 "written twice"** is stale for self-connections (finding 3).

Fix: a short errata pass on the spec, or a status note at its head pointing at
the plan's deviation list. Amend Task 13 to redraw the diagram.

### S9. Documented-contract behaviours with no test. [x1]

Beyond the plan's admitted 503→500 gap: the **20,000-record cap** (finding 1's
own resolution, a documented 413) is never exercised; **handler-level
concurrency** only ever processes 5 records, so `indexed`/`duplicates`
aggregation and exception propagation under real parallelism are untested; and
**partial-write recovery** (one endpoint's row written, then a re-POST) has no
test. Also, findings 4 and 5 have no README bullet in Task 13, violating the
plan's own rule at lines 17–19 that every deviation be documented there.

### S10. The Docker API pin will likely break CI and clean clones. [x2]

`api.version=1.51` (pom.xml:118–120, commit 652d5d6) was an ad-hoc fix for this
host's daemon, applied only to the failsafe fork and documented only in a commit
message. On any machine whose daemon supports less — GitHub `ubuntu-latest`
runners included — Testcontainers fails with "client version too new", the
inverse of the local problem. Success criterion §14.1 ("clean clone") and the
whole CI job assume otherwise.

Fix: bump Testcontainers (newer releases negotiate the API version) rather than
pinning; if the pin stays, make it an overridable property, document it, and
verify in CI before claiming §14.1.

### S11. Throttling exhaustion returns 500, and failure wastes the whole budget. [x2]

Spec §9 promises **503** on exhausted DynamoDB retries; the plan surfaces any
worker exception as a generic 500. Map throttling exhaustion to a 503
`ApiException`. Separately, `invokeAll` blocks until all 40k tasks finish, so a
deterministic failure at task #1 (an IAM misconfig, say) still burns 29 s of
doomed work and returns a 504 instead of a fast 500 — use a fail-fast flag or
`ExecutorCompletionService` with cancellation. The README should state that a
5xx from ingest means "progress unknown; re-POST is the recovery".

---

## Minor

- **M1. Duplicate uid with different content is silently first-write-wins.**
  Zeek re-logs long-lived connections (rotation, `conn_long`) with the same uid
  and start `ts` but updated byte counts; the second record cancels on the
  condition and its bytes are discarded. Acceptable — but it means a reported
  "duplicate" may be an *update you dropped*, which slightly weakens the
  duplicates-count claim. Document it.
- **M2. `nextCursor: null` on the final page** contradicts spec §8 "absent".
  Task 10 puts it unconditionally; tests use `hasNonNull` so both pass. Put only
  when non-null.
- **M3. Years outside 0001–9999 break the fixed-width invariant.**
  `Instant.parse` accepts `+10000-…` and negative years; `uuuu` then emits a
  leading `+`/`-`, which sorts *below* digits. Reject instants outside
  [1970, 2100] in both `Params` and the parser — one line each, and it closes
  the load-bearing lexicographic property completely.
- **M4. Sub-millisecond caller precision is truncated silently on
  `/connections`.** Internals stay mutually consistent (verified), and summary
  echoes `window` honestly, but `/connections` has no echo, so a caller can get a
  record 0.9 ms before their `from`. Truncate explicitly in
  `Params.requireRange` and document millisecond granularity.
- **M5. Negative/zero durations and negative byte counts pass through
  unvalidated** into rollup `ADD`, where negative bytes would *decrement*
  counters. Zeek should not emit them; a crafted file can. Clamp at parse.
- **M6. ICMP type/code land in `localPort`/`peerPort`** labelled as ports.
  Harmless, misleading in output — one README sentence. Ports are also
  unvalidated (negative, >65535 accepted).
- **M7. Retry backoff has no jitter** — 32 threads share a deterministic ladder.
  Add full jitter (folded into B1).
- **M8. No SnapStart priming hook.** Clients are built at class-init with no live
  connections (correct), but the first post-restore invoke still pays SDK
  marshaller init, first TLS, and JIT — 1–2 s, eating much of SnapStart's win. A
  dummy `DescribeTable`/`HeadBucket` in a CRaC `beforeCheckpoint` hook fixes it
  and is a strong interview talking point.
- **M9. Deployment redeploy trigger misses API-level changes.** It hashes only
  the method and integration, so `binary_media_types` (i.e. the B2 fix) and
  `path_part` renames will not roll the stage. Add them to the `jsonencode` list.
- **M10. Budget is account-wide and ACTUAL-only**, with billing data lagging
  8–24 h. Fine for a dedicated personal account — say so — and add a `FORECASTED`
  notification at 100% for earlier warning.
- **M11. Deploy IAM user policy is hand-waved.** Spec §12 claims "a dedicated IAM
  user with a scoped deploy policy"; no task authors one. Either write it or
  soften the claim.
- **M12. Rollup query is unbounded.** `queryRollups` pages without a
  window-duration cap; a 50-year window is bounded only by the Lambda timeout. A
  max-window guard or a README line.
- **M13. Non-reproducible jar** republishes and re-snapshots all three functions
  on every apply. Cosmetic; `project.build.outputTimestamp` fixes it.
- **M14. `versioning = "Suspended"` on a never-versioned bucket** — drop the
  resource entirely, keep a comment.
- **M15. CI runs unit tests twice** (`test` then `verify`), and push builds are
  scoped to `main` while spec §10 says "on push and pull request".
- **M16. Trailing empty page.** When remaining rows ≡ 0 mod `limit`, DynamoDB
  returns a full page *with* a LastEvaluatedKey, so the next call yields empty
  `items` and no cursor. Loop-until-no-cursor clients are fine; document it.
- **M17. S3 partitions are ingest time, not event time**, so the future-Athena
  story in §12 would prune on the wrong axis, and re-POSTs create duplicate raw
  objects Athena would double-count. One README sentence.
- **M18. Plan File Structure table drift.** `store/RollupUpdate.java` is listed
  but no task creates it (Task 6 inlined it as `bumpRollup`); the summary records
  are missing; Task 8's prose contradicts itself on whether `PeerStat.java` is
  separate; `LocalStackBase`'s javadoc says "per test class" while `@BeforeEach`
  provisions per method.
- **M19. Spec §8's own 202 example doesn't reconcile** (984 + 12 ≠ 1000 − 1).
- **M20. `202 Accepted` for a synchronously-completed operation** is technically
  miscoded (200/201). Contract is frozen; note only.
- **M21. `architectures = ["x86_64"]` should be explicit.** The default is
  correct and SnapStart for Java does not support arm64 — pinning it with a
  comment turns silent luck into a stated constraint.

---

## The first review's five findings — verdicts

| # | Finding | Verdict |
|---|---|---|
| 1 | Thread pool + 20k cap for the 29 s timeout | **Wrong as resolved.** Necessary but not sufficient — see B1 |
| 2 | Explicit TransactionConflict retry | Right diagnosis, **incomplete execution** — too shallow, no jitter, untested (B1, S3) |
| 3 | Self-connection → distinct endpoints | **Sound.** Preserves the key format and the exclusivity argument; honest count; tested |
| 4 | Hour-aligned `to` over-read | **Sound.** `truncate(to.minusMillis(1))` verified against aligned, mid-hour, sub-hour, +1 ms, and sub-ms inputs |
| 5 | Cursor partition binding | **Incomplete** — the PK check is right but insufficient (S2) |

A sixth deviation (`proto#<name>` + `ADD`) exists in code and is undeclared.

---

## Attacked and held

Recorded so these are not re-litigated:

- **The exclusive upper bound is rigorous** — and stronger than the spec's
  argument. `C#<to>#<uid>` vs bare `C#<to>` is decided by the byte-wise prefix
  rule alone, so exclusivity does not even depend on `#`'s byte value; it holds
  for any uid content, including empty. (Separately, `#` at 0x23 is indeed below
  Zeek's base62 alphabet.) The inclusive lower bound is likewise correct.
- **`C#` < `H#` separation** (0x43 < 0x48), disjoint prefixes, both rollup bounds
  correctly inclusive. PK is compared whole and never parsed, so no delimiter
  ambiguity.
- **Caller timestamp normalization** — `Instant.parse` → `formatTs` normalizes
  short fractions and explicit offsets to fixed-width UTC before key
  construction. Only residues are M3/M4.
- **The idempotency core is airtight** for byte-identical re-POSTs, including
  concurrent duplicate POSTs and mid-batch crashes. Counters cannot
  double-advance. `ConditionalCheckFailed` correctly takes precedence over
  retryable reasons.
- **Provenance survives.** S3 stores the decoded body; parser line numbers count
  blank and malformed lines identically; duplicate rows retain the original
  object key.
- **Body handling.** 5 MB measured on base64-*decoded* bytes and re-enforced on
  streamed gunzip output — the zip bomb is genuinely bounded. Record cap and
  malformed threshold run *before* the S3 put, so rejected batches leave no
  orphan object.
- **SnapStart Terraform wiring is correct end to end** — the highest-risk area.
  `publish = true` + `source_code_hash` publishes on code change; the alias
  tracks `version` so it does **not** pin at 1; the integration uses the alias
  `invoke_arn`; `aws_lambda_permission` carries `qualifier = "live"`.
  `snap_start` + `java21` + x86_64 is a valid combo.
- **Log-group hygiene** — groups are Terraform-created with retention and the
  role deliberately lacks `logs:CreateLogGroup`, so no orphans survive destroy.
  Teardown genuinely leaves nothing billable; §14.4 holds.
- **Packaging** — one shaded jar for three handlers,
  `ServicesResourceTransformer` and signature exclusions both present,
  `APIGatewayProxyRequestEvent` is the correct REST (v1) shape, well under size
  limits.
- **CI's `terraform init -backend=false` + `validate` is genuinely
  credential-free**, and `fmt -check -recursive` gates correctly.
- **Peer budget analysis** — the 1 MB page cap cannot bind before `Limit 1000` at
  these item sizes, so the budget really is 5,000 rows. The plan makes no false
  claim that projection reduces RCU (it does not).
- **`windowCovered` semantics**, edge queries via `Limit 1` forward/reverse,
  deterministic top-10 tie-break, unknown-IP zeroed 200, byte and port
  orientation for both roles, `limit` clamping, null query-string handling.
- **BigDecimal epoch conversion** avoids the `×1000` float error — genuinely
  correct.
- **§8 field-name audit passes exactly** for both endpoints and the error
  envelope, modulo M2.
- **Fixture arithmetic verified end to end** against real epoch conversions:
  Task 11's hand-computed summary, the boundary and pagination expectations, and
  the malformed-fixture ratio all check out.
- **Task ordering is sound** — no forward references; every consumed interface
  exists when consumed.
- **All committed code matches its plan task text line-for-line**, except the two
  known drifts (`Params`, the pom pin).

---

## Suggested order of work

1. B1 — remake the ingest concurrency decision (shard by PK, lower the cap,
   deepen the retry). This is the only one that changes the design.
2. B2 — gzip media types + magic-byte sniffing + redeploy trigger (M9).
3. S1 — IPv6 canonicalization on both paths.
4. S2, S4 — strict cursor decode; gateway request ID.
5. S3 — make the two vacuous tests real.
6. S8 — spec errata pass, so the three layers agree before Task 13 writes the
   README from them.
7. S6, S7, S10, S11 — per-function IAM, ingest timeout, Docker pin, 503 mapping.
8. Minors as they fall.
