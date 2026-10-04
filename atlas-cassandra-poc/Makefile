GRADLE ?= gradle
.PHONY: help build test grade grade-model grade-cassandra up down clean version-check
help:
	@echo 'build test grade-model grade-cassandra grade-faults grade up down clean version-check'
	@echo 'grade-maintainer (upstream 4.0.5 phase tests; separate JDK11 + Ant)'
	@echo 'ha-up grade-full-ha ha-down (Linux Docker, 24 GiB+ memory)'
	@echo 'three-up grade-fault-witness grade-partition grade-coordinator-crash grade-repair grade-history grade-retention three-down'
	@echo 'grade-transactions (requires single-node fixture)'
	@echo 'Requires JDK 25, Gradle 9.1.0 and Docker Compose v2 for Cassandra.'
version-check:
	@$(GRADLE) --version | tee /dev/stderr | grep -q 'Gradle 9.1.0' || (echo 'Install Gradle 9.1.0'; exit 1)
build: version-check
	$(GRADLE) --no-daemon build
test: version-check
	$(GRADLE) --no-daemon test
grade-model: version-check
	$(GRADLE) --no-daemon gradeModel --rerun-tasks
grade-cassandra: version-check
	$(GRADLE) --no-daemon gradeCassandra
grade: version-check
	$(GRADLE) --no-daemon grade --rerun-tasks
up:
	docker compose up -d --wait --wait-timeout 300
down:
	docker compose down
clean:
	$(GRADLE) --no-daemon clean
.PHONY: grade-faults ha-up ha-down grade-full-ha
grade-faults: version-check
	$(GRADLE) --no-daemon gradeFaults
ha-up:
	bash scripts/ha-up.sh
ha-down:
	docker compose -f compose.ha.yaml down
grade-full-ha: version-check
	$(GRADLE) --no-daemon gradeFullHa
.PHONY: three-up three-down grade-partition grade-coordinator-crash grade-repair
three-up:
	bash scripts/three.sh up
three-down:
	docker compose -f compose.three.yaml down
grade-partition: version-check
	$(GRADLE) --no-daemon gradePartition
grade-coordinator-crash: version-check
	$(GRADLE) --no-daemon gradeCoordinatorCrash
grade-repair: version-check
	$(GRADLE) --no-daemon gradeRepair
.PHONY: grade-history grade-transactions
grade-history: version-check
	$(GRADLE) --no-daemon gradeHistory
grade-transactions: version-check
	$(GRADLE) --no-daemon gradeTransactions --rerun-tasks

.PHONY: grade-retention
grade-retention: version-check
	$(GRADLE) --no-daemon gradeRetention

.PHONY: grade-fault-witness
grade-fault-witness: version-check
	$(GRADLE) --no-daemon gradeFaultWitness

.PHONY: grade-maintainer
grade-maintainer: version-check
	$(GRADLE) --no-daemon gradeMaintainer
