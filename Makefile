# Cadence developer commands. Builds need JDK 21 (DECISIONS.md D2).
SHELL := /bin/bash
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null || { [ -d /opt/homebrew/opt/openjdk@21 ] && echo /opt/homebrew/opt/openjdk@21; })
export JAVA_HOME
COMPOSE := docker compose
INFRA := postgres redis kafka minio kafka-ui

.PHONY: help env up down logs ps seed test build run-api run-transcoder clean

help: ## Show targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-16s %s\n", $$1, $$2}'

env: ## Create .env from .env.example if missing
	@[ -f .env ] || { cp .env.example .env && echo "Created .env from .env.example"; }

up: env ## Start infrastructure (postgres, redis, kafka, minio, kafka-ui) and wait until healthy
	$(COMPOSE) up -d --wait $(INFRA)
	$(COMPOSE) up --no-log-prefix --exit-code-from minio-init minio-init

down: ## Stop infrastructure (keeps volumes; use `docker compose down -v` to wipe data)
	$(COMPOSE) down

logs: ## Follow infrastructure logs
	$(COMPOSE) logs -f --tail=100

ps: ## Show service status
	$(COMPOSE) ps

seed: ## Load demo catalog through the real upload flow (slice 1.6)
	@echo "Seeding arrives in slice 1.6." && exit 1

test: ## Build and run all unit + Testcontainers integration tests
	./mvnw -B verify

build: ## Compile and package without tests
	./mvnw -B -DskipTests package

run-api: env ## Run cadence-api on the host (needs `make up`)
	./mvnw -pl cadence-api -am -DskipTests install -q && ./mvnw -pl cadence-api spring-boot:run

run-transcoder: env ## Run cadence-transcoder on the host (needs `make up`)
	./mvnw -pl cadence-transcoder -am -DskipTests install -q && ./mvnw -pl cadence-transcoder spring-boot:run

clean: ## Remove build output
	./mvnw -B -q clean
