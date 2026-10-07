# Atlas Cassandra POC

This directory is an independent Java project. Do not modify RevealSwift or its workflow.
Use JDK 25, Gradle 9.1.0 and the Make facade. Cassandra must remain 4.0.5.
Read README.md before changing protocol semantics. No Spring or Java EE.
Never mark Atlas PG-COMMIT or PG-CASS certified from these smoke tests.
Keep semantic hashes separate from concurrency tokens and business effective time.
Use one same-table subject partition for conditional state + receipt mutations.
Never use LOCAL_SERIAL, a custom LWT timestamp, or acknowledge an ambiguous timeout as failure.
Run make grade-model and make grade-cassandra; record missing infrastructure honestly.
Expanded fixture: Transactions.java defines the bounded group contract;
TransactionCassandraStore.java implements the packed HEAD + receipt candidate.
HistoryChecker.java is an independent bounded oracle: do not replace its admission
rules with calls to Transactions.check/apply or treat an INCONCLUSIVE result as passing.
Run make grade-history against make three-up for cross-DC evidence. Preserve failing
seeds/intervals, reduced counterexamples, and mutant replay tests. Updating group
semantics requires synchronized contract, oracle, adapter and README changes.
