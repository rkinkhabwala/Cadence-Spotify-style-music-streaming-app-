# Cadence developer commands. Builds need JDK 21 (DECISIONS.md D2).
SHELL := /bin/bash
# Maven selects JDK 21 through toolchains (`make toolchains`); JAVA_HOME is set too for the Spring Boot plugin
JAVA_HOME := $(shell ./scripts/setup-toolchains.sh --print 2>/dev/null)
export JAVA_HOME
COMPOSE := docker compose
INFRA := postgres redis kafka minio elasticsearch kafka-ui

.PHONY: help env keys toolchains up app app-down down logs ps seed test build run-api run-transcoder web web-verify clean

help: ## Show targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-16s %s\n", $$1, $$2}'

env: ## Create .env from .env.example if missing
	@[ -f .env ] || { cp .env.example .env && echo "Created .env from .env.example"; }

keys: ## Generate the dev RS256 JWT key pair in secrets/ (never committed)
	./scripts/generate-dev-keys.sh

toolchains: ## Register JDK 21 in ~/.m2/toolchains.xml (once per machine)
	./scripts/setup-toolchains.sh

up: env ## Start infrastructure (postgres, redis, kafka, minio, elasticsearch, kafka-ui) and wait until healthy
	$(COMPOSE) up -d --wait $(INFRA)
	$(COMPOSE) up --no-log-prefix --exit-code-from minio-init minio-init

app: env keys ## Build and run api + transcoder + web client as containers too (Compose profile "app")
	$(COMPOSE) --profile app up -d --build --wait api transcoder web

app-down: ## Stop the api, transcoder and web containers (infra keeps running)
	$(COMPOSE) --profile app stop api transcoder web

down: ## Stop infrastructure (keeps volumes; use `docker compose down -v` to wipe data)
	$(COMPOSE) --profile app down

logs: ## Follow infrastructure logs
	$(COMPOSE) logs -f --tail=100

ps: ## Show service status
	$(COMPOSE) ps

seed: env ## Seed 5 artists, 10 albums, ~20 tracks through the real upload flow (needs api + transcoder running)
	python3 scripts/seed.py $(ARGS)

test: ## Build and run all unit + Testcontainers integration tests
	./mvnw -B verify

build: ## Compile and package without tests
	./mvnw -B -DskipTests package

run-api: env keys ## Run cadence-api on the host (needs `make up`)
	./mvnw -pl cadence-api -am -DskipTests install -q && ./mvnw -pl cadence-api spring-boot:run -Dspring-boot.run.profiles=dev

run-transcoder: env ## Run cadence-transcoder on the host (needs `make up` and ffmpeg on PATH)
	./mvnw -pl cadence-transcoder -am -DskipTests install -q && ./mvnw -pl cadence-transcoder spring-boot:run

web: ## Run the web client dev server on http://localhost:5173 (needs the API on :8080)
	cd cadence-web && npm install --no-audit --no-fund && npm run dev

web-verify: ## Browser check: plays a track in the real web client and navigates without interrupting it
	./scripts/verify-web.sh

clean: ## Remove build output
	./mvnw -B -q clean
