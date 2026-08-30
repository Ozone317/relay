COMPOSE := docker compose -f docker-compose.dev.yml
JAVA_HOME_LOCAL := /usr/lib/jvm/java-21-openjdk-amd64

.PHONY: start down build clean test test-local format format-local format-check shell psql

start: ## Start the dev stack (app + Postgres) and follow app logs
	$(COMPOSE) up -d
	$(COMPOSE) logs -f app

down: ## Stop and remove the dev stack
	$(COMPOSE) down --remove-orphans

build: ## Rebuild the dev image (after changing pom.xml or Dockerfile.dev)
	$(COMPOSE) build

clean: ## Prune dangling Docker images/build cache older than 24h
	docker system prune -f --filter "until=24h"

test: ## Run the test suite in the dev container, against H2 (no DB needed)
	$(COMPOSE) run --rm --no-deps -e SPRING_PROFILES_ACTIVE= app ./mvnw test

test-local: ## Run the test suite on the host (requires a local JDK 21)
	JAVA_HOME=$(JAVA_HOME_LOCAL) ./mvnw test

format: ## Reformat all Java source via Spotless, in the dev container
	$(COMPOSE) run --rm --no-deps app ./mvnw spotless:apply

format-local: ## Reformat all Java source via Spotless, on the host
	JAVA_HOME=$(JAVA_HOME_LOCAL) ./mvnw spotless:apply

format-check: ## Check formatting without modifying files
	$(COMPOSE) run --rm --no-deps app ./mvnw spotless:check

shell: ## Open a shell inside the running app container
	$(COMPOSE) exec app bash

psql: ## Open a psql shell into the dev Postgres instance
	$(COMPOSE) exec db psql -U relay -d relay
