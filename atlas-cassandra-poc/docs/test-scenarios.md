# Atlas test scenarios in plain language

These are test goals, not a claim that every production Atlas flow is implemented.
An **offer** here is one bounded commercial-terms subject: price, eligibility and
royalty. **Draft** means an edit prepared against observed terms; **ticket** is its
retry permission; **receipt** records the exact accepted before/after states.
**Retirement/pruning** removes expired retry records without reopening old edits.
**Sealing** closes a prefix before archiving it. **Fencing** blocks authoring until
recovery can establish trustworthy history. An **intent ID** identifies commercial
content; it is not a date or an increasing revision number.

For example: a January offer is for NEW customers; a February edit also includes
CHURNED customers. They are different intents. Observing February's unchanged
configuration in March does not create another intent. Dates here are examples:
business effective-date fields and downstream notifications are not implemented.

## What each test layer means

| Layer / Make command | What actually runs | What it cannot establish |
|---|---|---|
| `make grade-model` | In-memory contract, history and deliberately broken-implementation tests | Cassandra behavior, physical crashes or remote archive durability |
| `make grade-cassandra` | Real Cassandra4.0.5, including the new archive fixture; shared contracts also run in the model | Multi-DC availability or a production archive service |
| `make grade-faults` | Actual batch-frame faults and verified process kills; archive-before-prune process recovery | Power loss, disk fsync guarantees or all internal Paxos phases |
| Three-node graders, including `make grade-archive` | Real coordinators in three logical DCs on one hosted runner | Independent-host/WAN failures, RF3/DC or global restoration fencing |
| `make grade-maintainer` | Sixteen selected upstream Cassandra phase tests, with a separate JDK11/Ant build | Atlas-specific archive or commercial workflows |
| `make grade-full-ha` | Separate nine-node scaffold | No qualified execution evidence yet |

The archive Cassandra fixture uses real HEAD and slot rows, a conditional batch,
and stale-guard rejection. Its archive/authority remain in-memory fixtures, shared
by serial orchestration. Restore replaces logical hot rows, not SSTables or a whole
database backup. Signed-draft integration, arbitrary old deployments and an
externally enforced cross-DC recovery barrier remain open.

The same scenario ID may appear under model and Cassandra classes because they
share a contract. Generated histories and broken variants are iterations inside
a JUnit case, not extra case counts. The IDs below also appear in JUnit display
names and source comments. The TSV keeps developer method mappings separate from
the user-facing explanations.

## Goals, simulated boundaries and required outcomes

| Scenario | Goal in Atlas terms | Simulated boundary | Required outcome |
|---|---|---|---|
| AT-001 | Recover the original offer edit after its reply is lost | Accept an edit, make a later edit, then retry the original request; also reuse its ID with changed terms | Return the original receipt without reverting later terms; reject changed content under the same ID. |
| AT-002 | Prevent two editors from silently overwriting each other | Two whole-offer edits start from the same saved version at the same time | Exactly one succeeds; the other receives a conflict. |
| AT-003 | Keep an old draft stale even when the offer returns to its old price | Change the offer and then restore its earlier commercial configuration | The intent ID matches the old configuration, but the old draft still conflicts. |
| AT-004 | Track changes in commercial intent rather than calendar time | Compare unchanged terms observed later with changed price or eligibility | Unchanged terms keep the same intent ID; changed terms produce a different ID. |
| AT-005 | Keep retry results stable through many later offer edits | Generate 100 sequences of 100 edits and repeatedly retry earlier requests | Every retry returns its own original receipt, not the latest offer state. |
| AT-006 | Give downstream teams reproducible intent identities | Compare the bounded offer schema against fixed expected bytes and hashes; reject a corrupt size summary | The canonical representation matches the published vectors and rejects inconsistent summaries. |
| AT-007 | Allow independent price and royalty edits without losing either | Prepare both from one version, accept the price edit first, then apply the royalty edit | Both values survive; the second receipt includes the first change. |
| AT-008 | Keep eligibility and price valid together | Race a price increase above the fixture limit against extending eligibility to churned customers | Only one can succeed; Atlas never stores the forbidden combination. |
| AT-009 | Apply a commercial amendment as one complete change | Submit an invalid price/eligibility bundle, then a valid price/eligibility/royalty bundle | Reject the invalid bundle without changes; accept all fields and the receipt together for the valid bundle. |
| AT-010 | Stop old drafts after an authoring-rules change | Change the admitted rules version while an unsubmitted draft and an older accepted request exist | Reject the stale draft, but still replay the accepted request's exact receipt. |
| AT-011 | Enforce the offer size limit across independent edits | Two individually small changes together exceed the shared payload budget | The later edit is rejected without losing the first accepted edit. |
| AT-012 | Reject incomplete or ambiguous commercial requests | Omit the price dependency from an eligibility edit; submit a price with a leading zero | Reject both requests and leave the offer unchanged. |
| AT-013 | Separate intent identity from edit concurrency | Write the same royalty value again, then expand eligibility | The no-op edit gets a fresh concurrency token but the same intent ID; eligibility changes intent identity. |
| AT-014 | Check that overlapping commercial edits have a coherent history | Run 400 generated model histories with overlapping edits, reads and lost replies | An independent checker finds a valid order, and separate assertions require successful recovery and progress. |
| AT-015 | Prove the commercial-history checker catches unsafe implementations | Deliberately lose edits or receipts, accept stale dependencies, apply partial bundles or reuse old version tokens | Reject all six broken variants and replay their reduced counterexamples. |
| AT-016 | Distinguish a legitimate overlapping read from a stale completed read | Place an old-value read during an edit, then after that edit has completed; also exhaust the checker budget | Allow the overlapping read, reject the later stale read and label an exhausted search inconclusive. |
| AT-017 | Require Atlas to make progress, not merely avoid bad writes | A transport reports uncertainty for every edit forever | The workload fails its recovery gate even if a no-change history is logically safe. |
| AT-018 | Keep the POC's cross-DC consistency assumptions explicit | Try weaker local-only consistency and unapproved automatic driver replay settings | The policy guard rejects those settings before they can count as valid evidence. |
| AT-019 | Let two independent authors receive distinct edit identities | Prepare price and royalty drafts from one view, allocate and accept them concurrently | Both receive distinct tickets and preserve their original dependencies; both changes and exact retries survive. |
| AT-020 | Allocate one edit identity for duplicate submissions | Submit the same signed draft concurrently, then reuse its identity with different terms | Duplicate submissions share one allocation; changed content is rejected. |
| AT-021 | Prevent retired drafts from becoming new edits | Prune an accepted draft, turn the clock back, alter its deadline or use it for another offer | The old draft remains closed; forged or cross-offer use is rejected; a genuinely new draft gets a new identity. |
| AT-022 | Close a waiting draft whose allocation anchor was retired | Prepare a later-expiring draft but do not allocate it before its older anchor is cleaned up | Reject that waiting draft even though its own deadline has not arrived. |
| AT-023 | Preserve a younger accepted edit while older drafts are cleaned up | Allocate a younger draft before its earlier anchor is retired | The younger ticket and original receipt still replay correctly. |
| AT-024 | Keep the original edit result available during retry retention | Make later edits, pass the deadline, then remove expired retry records | Before cleanup return the original receipt; after cleanup report the old request as too old without changing current terms. |
| AT-025 | Recover the same ticket when allocation is retried | Retry an allocation, then try to change its commercial content under the same identity | Return the same ticket with no extra allocation; reject identity reuse with changed content. |
| AT-026 | Ensure an expired unsubmitted edit never runs later | Issue a ticket without accepting its edit, expire and prune it, then retry | The old request never changes the offer; a new request with a new identity may succeed. |
| AT-027 | Bind each retry permission to its original offer and deadline | Extend a ticket's deadline without a valid signature or submit it to a different offer | Reject both uses without accepting an edit. |
| AT-028 | Do not discard unresolved edits just to free capacity | Fill the retry window with unresolved tickets and try cleanup before their deadlines | Reject further allocation until expiry; early cleanup preserves tickets and later cleanup frees capacity. |
| AT-029 | Clean up old retry records without deleting a younger result | Expire an older ticket while a younger accepted ticket is still valid | Remove only the expired prefix and preserve the younger receipt and current terms. |
| AT-030 | Keep retirement monotonic when clocks move backward | Issue tickets around a backward clock jump, then roll time back after cleanup | Later deadlines do not shrink and the retired prefix never reopens. |
| AT-031 | Keep retry and cleanup races safe | Retry an accepted edit at the same time its retained record is removed | Return the original receipt or the explicit too-old/unknown outcome; never execute a new edit. |
| AT-032 | Stop a delayed edit after its ticket is retired | Pause acceptance after it computes a change, then expire and clean up the ticket before resuming | The old mutation guard fails and the retired edit cannot enter the offer. |
| AT-033 | Bound live retry records during repeated cleanup | Repeat allocation, acceptance, expiry and retirement in the model | Live rows stay bounded and all retired identities remain closed; disk reclamation is not tested. |
| AT-034 | Check draft allocation and retry retention under overlap | Generate 200 model histories, including 100 with lost allocation or acceptance replies | The independent retention checker accepts every history and the workload requires progress. |
| AT-035 | Prove the retention checker detects broken storage | Split retirement from deletion, rewind allocation or floors, lose receipts or changes, or reuse metadata versions | Reject all seven broken variants and replay their saved histories. |
| AT-036 | Reject a retry service that never resolves an edit | Keep returning uncertainty during retention operations | The progress gate fails even when the safety checker can explain a no-effect history. |
| AT-037 | Avoid claiming proof outside the retention checker's assumptions | Exceed its search limits or cross unsupported clock boundaries | Return inconclusive, which cannot satisfy a passing workload gate. |
| AT-038 | Recover the latest offer without reviving old edit identities | Restore an old model snapshot after archiving accepted and unaccepted slots; reverse the clock | Rebuild the accepted history in acceptance order, close old IDs and allow only a new identity to edit. |
| AT-039 | Make every archive step safely retryable | Lose the request before effect or lose the reply after effect at eight model boundaries | All 16 exact-retry scenarios finish with a coherent retired prefix. |
| AT-040 | Do not delete retry records based only on an archive acknowledgement | The archive reports success with partial data or volatile data; archive access can also disappear | Certification and pruning stay blocked until complete records are verified. |
| AT-041 | Resume cleanup after a modeled worker crash | Persist model archive records, discard volatile acknowledgements and create a fresh worker facade | Recover from retained facts and complete idempotent cleanup without changing accepted terms. |
| AT-042 | Keep a restored offer closed when its history is uncertain | Take the model authority or archive offline, remove a record or corrupt it | Recovery remains unresolved and edits stay fenced. |
| AT-043 | Never silently discard a newer edit or reservation during restore | Restore an older snapshot while a later reservation or accepted edit lacks archive coverage | Recovery stays fenced instead of pretending the old snapshot is current. |
| AT-044 | Prove the archive checker catches unsafe recovery decisions | Deliberately bypass coverage, trust partial or volatile copies, split cleanup, rewind facts or reopen old edits | All nine broken models are rejected and their saved counterexamples replay. |
| AT-045 | Exercise many archive and restore interruption sequences | Run 100 serial model traces with lost replies, partial copies, crashes and old snapshots | Each trace must recover the exact offer and close old IDs; remaining fenced is not a successful trace. |
| AT-046 | Preserve the January and February offer intents for audit | Model a price edit for NEW customers, then extend eligibility to NEW and CHURNED; prune and restore | Keep both exact receipts and the latest intent; time alone does not change its hash. Actual effective-date fields are absent. |
| AT-047 | Do not label an unchecked archive history as safe | Give the archive checker less capacity than the trace requires | Return inconclusive rather than a passing verdict. |
| AT-048 | Recover an accepted offer edit after a client restart | Close the Cassandra client and open a new session | The original receipt remains available in real Cassandra. |
| AT-049 | Preserve retirement and younger retry results across client restart | Clean up expired records, close the client and reconnect to Cassandra | The retired prefix stays closed and younger receipts remain intact. |
| AT-050 | Safely retry an offer edit that never reached Cassandra | Intercept and drop the actual batch frame before it reaches the server | Observe unchanged state, then accept the exact retry once. |
| AT-051 | Recover an edit accepted before its reply was lost | Drop an actual server response, verify acceptance, kill Cassandra and restart it | Recover the same receipt; later edits do not alter the original retry result. |
| AT-052 | Prove that a claimed network fault really isolates a DC | Run a no-op partition injector and one that blocks only one direction | Reject both invalid fault setups using independent connectivity and packet-counter checks. |
| AT-053 | Keep offer editing coherent during a DC partition | Isolate one of three logical DCs while clients continue to reach it | The majority makes progress, the minority cannot claim acceptance, and state is coherent after healing. |
| AT-054 | Resolve an uncertain edit after its coordinator dies | Witness a batch send, kill that coordinator and recover through another DC | Resolve the same operation and preserve its original receipt; the internal Paxos phase is not identified. |
| AT-055 | Bring a rejoined replica back to the accepted offer state | Keep a replica away during edits, rejoin it and run full repair alongside authoring activity | The repaired local replica reaches the final token; exact server-phase overlap remains unproven. |
| AT-056 | Validate commercial histories across real Cassandra coordinators | Run 14 histories with concurrent edits, reads and partition recovery | The independent checker accepts them and separate gates require overlap, recovery and progress. |
| AT-057 | Validate retry retention across DC isolation and recovery | Run six real histories; one isolates dc1, then compare healed coordinator views | Every view must exactly match the checked final state; three bounded reads and fresh readiness checks cannot replace equality. |
| AT-058 | Keep retired offer edits closed across a partition | Attempt cleanup on an isolated DC, prune through the majority, then heal | Retain the younger receipt, prove old-row deletion and reject the retired request after healing. |
| AT-059 | Fail cleanup that never produces a definite result | A test supplier returns uncertainty for all three compaction attempts | The progress gate fails and preserves the unresolved cause. |
| AT-060 | Recover cleanup without changing which edits are expired | The model performs cleanup but loses its first reply; retry at the same clock and target | The next attempt observes the same completed cleanup, with unchanged accepted terms. |
| AT-061 | Explore availability with three replicas in each of three DCs | The separate nine-node scaffold removes an ingress DC and tests a three-replica minority | Its assertions require surviving-majority progress and minority rejection; this scaffold is not part of the qualified hosted run. |
| AT-062 | Keep Cassandra retry records until audit history is complete | Try pruning without archive coverage and with a partial archive object, then complete and certify the archive | Blocked attempts preserve all slot rows; verified coverage permits actual row deletion while preserving the offer. |
| AT-063 | Recover an offer from an old logical Cassandra snapshot | Restore old hot-state rows while the simulated external archive and authority survive; reopen through another coordinator | Keep edits fenced until complete history is reconstructed; old IDs stay closed and a new edit may proceed. |
| AT-064 | Reject an old writer after cleanup or recovery changes the offer guard | Capture a valid Cassandra mutation, then deliver it after sealing, after logical restore and after recovery | All three stale conditional writes must fail; no retired edit becomes accepted. |
| AT-065 | Block edits when Cassandra recovery lacks trusted facts | Delete an archive fixture record, leave a newer accepted edit unarchived or take the authority fixture offline | Keep the restored offer fenced and retain an explicit unresolved outcome. |
| AT-066 | Ensure Cassandra-backed archive tests detect the same unsafe decisions | Run nine broken transition variants through the real conditional-write adapter and read back its state | The independent checker rejects every actual observation sequence; external-service facts are still simulated. |
| AT-067 | Prove the checker notices a writer that ignores its stale guard | Deliberately replace an old request's guard with the current guard and write its outdated state to Cassandra | The unsafe write actually applies and the independent checker rejects the resulting history. |
| AT-068 | Resume archive-backed cleanup after a real Cassandra process crash | Keep verified archive facts in the external fixture, kill Cassandra before pruning and reconnect after restart | Accepted terms and slot rows survive; repeated pruning removes the rows safely. Remote archive and power-loss durability remain unproven. |
| AT-069 | Resolve uncertain sealing without losing accepted offer terms | Drop the exact seal batch before forwarding or drop its real server reply; reconnect through a fresh session | Full offer and receipt state matches the witnessed cut; retries converge, archive recovery preserves terms and retired edits stay closed. |
| AT-070 | Resolve uncertain cleanup without separating the floor from receipt deletion | Drop the exact prune batch before forwarding or drop its real server reply after complete archive coverage | The floor and all receipt rows change together; exact retries are no-ops and logical restore recovers the accepted offer. |
| AT-071 | Ensure a lost reply cannot hide unsafe partial cleanup | Run a broken batch that advances the cleanup floor but retains receipt rows, then drop its server reply | The write really applies and the independent checker rejects that exact observed prune boundary. |

## Reading failures and evidence

A timeout means the result is uncertain; it does not prove the edit failed.
Passing requires the stated state/receipt checks plus any progress gate. A bounded
checker result of INCONCLUSIVE never counts as a pass. Negative controls must fail
the independent checker for the intended reason; a thrown infrastructure error
alone is not a successful negative control.

`build/reports/tests/` contains the readable reports. `build/evidence/` contains
replayable histories and fault witnesses. Archive Cassandra histories and stale
writer witnesses are under `archive-cassandra-single/` and
`archive-cassandra-three/`; the process-crash case is in the single-node folder.
See [the adversarial review](adversarial-test-review.md) for the current run status,
failed attempts and unproven boundaries. All canonical Atlas proof gates remain
unproven.

To maintain this guide, edit `docs/test-scenarios.tsv`, run
`python3 scripts/describe-tests.py --write`, then `make check-test-descriptions`.
The check fails if a test lacks a description or generated text is stale.
