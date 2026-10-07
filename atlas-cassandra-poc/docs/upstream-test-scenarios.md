# Cassandra maintainer tests in plain language

`make grade-maintainer` runs these 16 upstream checks plus four Atlas-authored
overlay cases described below. The upstream checks test Cassandra's
conditional-write mechanism, not Atlas offers or the new archive adapter. Atlas
uses that mechanism to protect edits from conflicting writers. The upstream
method names remain unchanged so the harness can verify exactly which tests ran.

## Update and recovery checks

Source: pinned 4.0.5 [CASTest](https://github.com/apache/cassandra/blob/ec476e0e259efb62ee19804c3ff46dbbe4d1ded7/test/distributed/org/apache/cassandra/distributed/test/CASTest.java).

| Human goal | Simulated boundary and required result | Method in raw upstream reports |
|---|---|---|
| Apply only an eligible conditional edit | Matching conditions update the value; a mismatch leaves it unchanged. | `simpleUpdate` |
| Handle an interrupted first agreement stage | Drop prepare requests; subsequent operations must not invent an inserted row. | `incompletePrepare` |
| Resolve a partially proposed edit | Drop proposal requests, then expose retained proposal state; the next conditional update completes consistently. | `incompletePropose` |
| Resolve interrupted commit delivery | Drop commit requests, then encounter the accepted value; a later conditional update reaches the expected value. | `incompleteCommit` |
| Keep reads consistent after uncertainty | A successful serial read sees no insertion; a later read involving a previously excluded replica must agree. | `readConsistencyAfterWriteTimeoutTest` |
| Keep conditional rejections consistent | Two conditional operations across different replica groups must agree that the uncertain insertion did not apply. | `nonApplyingCasConsistencyAfterWriteTimeout` |
| Keep an edit consistent with an earlier read | A later conditional operation must respect a serial read that found no insertion. | `mixedReadAndNonApplyingCasConsistencyAfterWriteTimeout` |
| Keep a read consistent with an earlier rejection | A later serial read must respect an earlier conditional operation that found no insertion. | `mixedNonApplyingCasAndReadConsistencyAfterWriteTimeout` |

## Lost-message and uncertain-outcome checks

Source: pinned 4.0.5 [CasWriteTest](https://github.com/apache/cassandra/blob/ec476e0e259efb62ee19804c3ff46dbbe4d1ded7/test/distributed/org/apache/cassandra/distributed/test/CasWriteTest.java).

| Human goal | Simulated boundary and required result | Method in raw upstream reports |
|---|---|---|
| Establish a healthy conditional-write baseline | Insert and update without competing writers; read back the expected values. | `testCasWriteSuccessWithNoContention` |
| Surface lost prepare requests | Drop outgoing prepare messages; report the expected CAS timeout. | `testCasWriteTimeoutAtPreparePhase_ReqLost` |
| Surface lost prepare replies | Drop incoming prepare responses; report the expected CAS timeout. | `testCasWriteTimeoutAtPreparePhase_RspLost` |
| Surface lost proposal requests | Drop outgoing proposal messages; report the expected CAS timeout. | `testCasWriteTimeoutAtProposePhase_ReqLost` |
| Surface lost proposal replies | Drop incoming proposal responses; report the expected CAS timeout. | `testCasWriteTimeoutAtProposePhase_RspLost` |
| Surface lost commit requests | Drop outgoing commit messages; report the expected CAS timeout. | `testCasWriteTimeoutAtCommitPhase_ReqLost` |
| Surface lost commit replies | Drop incoming commit responses; report the expected CAS timeout. | `testCasWriteTimeoutAtCommitPhase_RspLost` |
| Report uncertainty during competing proposals | Insert a competing request between prepare and propose; report the expected unknown-result exception. | `testWriteUnknownResult` |

For Atlas, the consequence is that a timeout alone cannot mean “your edit did not
happen.” Exact-request recovery remains necessary. These upstream tests use
classloader-isolated nodes and flags that skip disk sync. They do not establish
remote archive persistence, whole-machine failure durability or Atlas batch
correctness. See [the harness assessment](testing-harness-assessment.md).

## Atlas-authored overlay (not upstream tests)

These four methods live in `maintainer/AtlasBatchPhaseTest.java`; the script copies
the overlay into the pinned source tree without changing server code. Evidence
overlays log checked operands while delegating to the original test comparisons.
[Run 37447187294](https://github.com/kverma/codex-playground/actions/runs/37447187294)
passed the twenty-case maintainer job and its artifact audit.

| Human goal | Witness and required result | Overlay method |
|---|---|---|
| Keep HEAD and receipt atomic at every Paxos message boundary | Six counted request/response cuts; SERIAL resolution permits only complete before/after state and exact reissue reaches the full after image. | `headReceiptAtEveryPaxosMessageBoundary` |
| Keep archive floor and row deletion atomic | The same six counted cuts applied to the conditional floor/delete shape; no partial floor/slot partition is accepted. | `floorDeleteAtEveryPaxosMessageBoundary` |
| Verify the row-set oracle rejects a real partial prune | Execute HEAD-only and observe the undeleted slot; reject that actual state. | `atomicityOracleRejectsAnActualSplitPrune` |
| Verify repair actually overlaps an edit | Hold an observed VALIDATION_RSP while an exact HEAD/receipt batch completes; require repair success, local sentinel healing and retained edit. | `editCompletesInsideWitnessedRepairValidationPause` |

See [overlay scope and limitations](maintainer-atlas-overlay.md). Twelve message
cuts are two JUnit cases, not twelve additional tests. The measured repair pause
is not evidence of disk-streaming overlap, independent-host durability or RF3/DC.
