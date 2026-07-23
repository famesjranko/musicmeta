# Every command an agent or a developer needs, in one place. The point is not to wrap Gradle — it
# is that nothing has to be guessed or reconstructed from prose, so two runs of the same task are
# the same command.
#
# `./check` stays the implementation of verification (CI runs it directly, and it takes flags).
# `make check` calls it. They are not competing entry points.
#
# .PHONY on everything matters here: `check` and `demo` are also real paths in this repo, and
# without it make would see the file, call the target up to date, and silently do nothing.

SHELL := /usr/bin/env bash
GRADLE := ./gradlew

# The android module needs an SDK. Same fallback ./check uses, so a normally-configured machine
# does not need ANDROID_HOME exported.
ifeq ($(origin ANDROID_HOME), undefined)
ifneq ($(wildcard $(HOME)/Android/Sdk),)
export ANDROID_HOME := $(HOME)/Android/Sdk
endif
endif

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this list
	@grep -hE '^[a-z0-9-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk -F':.*?## ' '{printf "  \033[1m%-16s\033[0m %s\n", $$1, $$2}'

# --- setup ---

.PHONY: bootstrap
bootstrap: ## Install the pinned tools ./check requires (once per machine)
	./scripts/bootstrap.sh

# --- the gate ---

.PHONY: check
check: ## Everything CI runs: lint, types, tests, build, demo canary
	./check

.PHONY: check-fast
check-fast: ## Lint and types only — for the edit loop, never as evidence for a push
	./check --fast

# --- build and test ---

.PHONY: build
build: ## Compile all modules, run tests, verify the public ABI
	$(GRADLE) build

.PHONY: test
test: ## Core unit tests
	$(GRADLE) :musicmeta-core:test

.PHONY: test-all
test-all: ## Unit tests for every module
	$(GRADLE) :musicmeta-core:test :musicmeta-android:test :musicmeta-okhttp:test

.PHONY: test-e2e
test-e2e: ## E2E tests against live third-party APIs — never merge-gating, needs API keys
	$(GRADLE) :musicmeta-core:test -Dinclude.e2e=true

.PHONY: demo
demo: ## Compile demo/, the only in-tree consumer of the published surface
	cd demo && ../gradlew compileKotlin

# --- code quality ---

.PHONY: format
format: ## Rewrite Kotlin and Python to house style
	$(GRADLE) ktlintFormat
	ruff format .

.PHONY: lint
lint: ## All four layers without building
	$(GRADLE) ktlintCheck detekt
	ruff check .
	mypy
	python3 scripts/checks/check_conventions.py

.PHONY: mask-check
mask-check: ## Differential-test the conventions scanner against Kotlin's own lexer (also in ./check)
	python3 scripts/checks/test_code_mask.py --verbose

# --- public API ---

.PHONY: api-check
api-check: ## Fail if the public ABI diverges from the committed baseline
	$(GRADLE) apiCheck

.PHONY: api-dump
api-dump: ## Regenerate api/*.api after an intentional change — review the diff, it is the record
	$(GRADLE) apiDump

# --- release ---

.PHONY: publish-local
publish-local: ## Install to the local Maven repo for testing against a real consumer
	$(GRADLE) publishToMavenLocal

.PHONY: release-notes
release-notes: ## Preview the release note for VERSION=x.y.z
	@test -n "$(VERSION)" || { echo "usage: make release-notes VERSION=0.10.1" >&2; exit 64; }
	python3 scripts/github-workflows/build_release_notes.py $(VERSION)

.PHONY: versions
versions: ## Assert module versions agree with each other and the CHANGELOG
	./scripts/github-workflows/check_versions.sh

.PHONY: clean
clean: ## Remove build output
	$(GRADLE) clean
