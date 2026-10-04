GRADLE ?= gradle
.PHONY: help build test grade grade-model grade-cassandra up down clean version-check
help:
	@echo 'build test grade-model grade-cassandra grade up down clean version-check'
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
