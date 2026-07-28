# PinLog Backend Contributing Guide

This document is the single source of truth for PinLog Backend **repo-specific** development rules. Agents follow these rules too. Detailed rules for API, database, testing, and package structure live in [docs/development](docs/development/).

Organization-wide standards shared by every service repo (Git/PR/merge rules, deployment & runtime contracts) are **not duplicated here** — this repo references the `infra` repo as the authority. Read "Organization standards" below first.

## Organization standards (authoritative)

Rules that `back`, `front`, and `ai` all follow are owned by `Team-PinLog/infra`. If anything here conflicts with the infra docs, infra wins — fix this document to match.

- **Git / PR / merge rules** — [infra/docs/git-governance.md](https://github.com/Team-PinLog/infra/blob/main/docs/git-governance.md)
  - Merge by **squash**, delete the feature branch after merge.
  - PR body must carry a **Jira key + TDD evidence (RED/GREEN/Regression)**; no direct push to `main`/`dev`.
  - Pin external GitHub Actions to a **full commit SHA**.
- **Deployment & runtime contract** — [infra/docs/backend-conventions.md](https://github.com/Team-PinLog/infra/blob/main/docs/backend-conventions.md)
  - Service `context-path` is `/api/core` (never repeat the prefix in controller mappings).
  - Containers run **non-root as UID 1000**; actuator is required.
  - Image tag = **commit SHA**, deployed via GHCR + Argo CD GitOps.

This document (and `docs/development/`) assumes those standards and covers only **repo-specific rules** (package structure, Flyway version ranges, Testcontainers, etc.). To create a Jira ticket from plain language, use the separate tool `Team-PinLog/cowork` ("할 일 올리기").

## Canonical product & data specs (Team-PinLog/docs)

Product and data contracts are owned by `Team-PinLog/docs` (`static/`). Reference them — do not copy. On conflict, the canonical spec wins. Most relevant for backend domain work:

- [ERD](https://github.com/Team-PinLog/docs/blob/main/static/07_ERD.md) — entity relationships
- [API spec](https://github.com/Team-PinLog/docs/blob/main/static/08_API_명세.md) — endpoint contracts
- [Data model & integrity](https://github.com/Team-PinLog/docs/blob/main/static/06_데이터모델_및_무결성.md) — tables, constraints, integrity rules
- [Glossary](https://github.com/Team-PinLog/docs/blob/main/static/03_공식_용어사전.md) — official terms (use these names in code)
- [AI design](https://github.com/Team-PinLog/docs/blob/main/static/05_AI_설계.md) — AI contract (status values, internal APIs, AI data structures)
- [Cross-part requirements](https://github.com/Team-PinLog/docs/blob/main/static/05-1_파트간_요구사항.md) · [Policy](https://github.com/Team-PinLog/docs/blob/main/static/02_정책_정의서.md) · [MVP scope](https://github.com/Team-PinLog/docs/blob/main/static/10_MVP_기능범위.md) · [User flow](https://github.com/Team-PinLog/docs/blob/main/static/09_유저플로우.md)

Implement domain names, statuses, and API shapes to match these specs. When a spec is ambiguous or missing, raise it with the owning part rather than inventing a contract.

## New here? (reading order)

If you are new to the backend, read in this order.

1. [infra onboarding](https://github.com/Team-PinLog/infra/blob/main/docs/onboarding.md) — the infra big picture: deploy, addresses, logs.
2. **This document (CONTRIBUTING)** — setup, Git, verification, and PR rules.
3. [Development workflow](docs/development/workflow.md) — the order from ticket to merge.
4. The [development rules](docs/development/) for your task — package, API, error, logging, config, DB, testing.

For agents, [CLAUDE.md](CLAUDE.md) enforces this order.

## Prerequisites

- JDK 21
- Docker Desktop, or Docker Engine with Docker Compose
- A shell that can run the bundled Gradle Wrapper

## Local start

Run from the project root, in order.

```bash
cp .env.example .env
docker compose up -d --wait
docker compose ps
./gradlew bootRun
```

Compose waits until PostgreSQL and Redis are `healthy`. The app starts at `http://localhost:8080/api/core`. Do not repeat this context path in controller mappings.

Use `docker compose down` to stop services only. Use `docker compose down -v` only when you must also reset local PostgreSQL data.

## Work tracking and Git rules

For normal development, Jira is required and linking a GitHub Issue is optional. Get the Jira key before touching code, and use the same key in the branch and commits. See the [Jira workflow guide](docs/development/jira-workflow.md) for details (org-wide rule; to be moved to infra later).

```text
branch: {type}/{jira-key}-{summary}
commit: {type}({jira-key}): {summary}
```

For example, on `feat/S15P11A705-14-member-search` a commit reads `feat(S15P11A705-14): add member search`. Use `feat`, `fix`, `docs`, `refactor`, `chore`, `test`, or `perf` for `type`.

The backend foundation reset is an exception: it is tracked solely by [GitHub Issue #9](https://github.com/Team-PinLog/back/issues/9) without Jira, and that exception applies to its branch names, commit messages, and PRs. It does not apply to normal work, which keeps using Jira keys.

Never push directly to `dev`. Submit changes as PRs that pass the branch protection rules: `backend-ci / check` and conversation resolution. These rules apply to admins too. **Approving reviews are not required** — the org standard sets the count to 0 because a single-operator team gains nothing from a formal self-approval ([infra git-governance](https://github.com/Team-PinLog/infra/blob/main/docs/git-governance.md)). Review still happens; it just is not a merge gate.

## Before and during implementation

- Read existing code and tests first; write or update a failing test that captures the intended change.
- Do not create unused empty packages, `.gitkeep`, or speculative domain layers. Create a package only when a class lands in the location defined by the [package structure convention](docs/development/package-structure.md).
- Do not add H2. Use PostgreSQL Testcontainers for DB-dependent tests.
- Add authentication only in a dedicated auth PR that ships dependencies, the auth contract, security config, a local dev path, and tests together. See the [authentication PR contract](docs/development/authentication.md).
- When your change makes a decision that is hard to undo — adding or removing a dependency, moving a schema boundary, choosing a protocol or response contract, overriding a framework default — write the decision record **before** the code. Backend decisions go to [docs/backend/decisions/](docs/backend/decisions/) as `BD-##`; cross-part agreements stay in `docs/ai/proposals/` as `P##`. Record the trade-off you accepted, not just the choice.

The full path from starting a feature to merging is in the [development workflow](docs/development/workflow.md); review and merge criteria are in the [code review guide](docs/development/code-review.md). Detailed rules: [package structure](docs/development/package-structure.md), [code style](docs/development/code-style.md), [API](docs/development/api-conventions.md), [API documentation](docs/development/api-documentation.md), [error handling](docs/development/error-handling.md), [logging](docs/development/logging.md), [configuration](docs/development/configuration.md), [database](docs/development/database-conventions.md), [testing](docs/development/testing-conventions.md).

## Verification and PR

Before reporting completion, always run:

```bash
./gradlew clean check --no-daemon
```

This runs PostgreSQL Testcontainers, so Docker must be running. If Docker is down, do not skip DB tests — start Docker and rerun.

Additional verification by change type:

| Change | Required extra verification |
| --- | --- |
| DB access code | PostgreSQL integration test |
| Flyway migration | Migration verified against an empty PostgreSQL DB |
| API contract | Request/response and validation contract tests, plus doc updates |
| Auth / authorization | Success, 401, 403, or the agreed resource-hiding 404 tests |

Normal PRs use the [PR template](.github/pull_request_template.md), require a Jira key, and link a GitHub Issue only when one is relevant. Foundation reset PRs reference Issue #9 and may omit the Jira key, but this exception does not weaken the template's Jira requirement for normal work. Every PR records the verification commands and results, out-of-scope items, and any judgment calls needing review.

## Branch & environment at a glance

- **Branch**: branch off the latest `dev` as `{type}/{jira-key}-{summary}`. No direct push to `dev`/`main`; merge by squash with feature-branch auto-delete. Details in the [development workflow](docs/development/workflow.md).
- **Environment**: local uses Compose (`localhost`); production uses cluster addresses with env-var injection. Details in the [configuration convention](docs/development/configuration.md).
- **Authority**: merge, supply-chain, and deployment standards come from [infra git-governance](https://github.com/Team-PinLog/infra/blob/main/docs/git-governance.md) and [backend-conventions](https://github.com/Team-PinLog/infra/blob/main/docs/backend-conventions.md).

## Documentation roles

- [README.md](README.md): quick reference for tech stack, infra, and operations.
- [docs/development/](docs/development/): detailed rules for workflow, review, API, DB, testing, and more. **This is the source of truth for rules.**
- [docs/backend/](docs/backend/): the backend part's design, decision, and implementation records — spec / decisions / implements / troubleshooting plus a WORKLOG. `docs/development/` says **what the rule is**; [docs/backend/decisions/](docs/backend/decisions/) says **why it was decided and what we accepted in return**. Decision records are a preservation zone: never delete one, update its status instead.
- [CLAUDE.md](CLAUDE.md): a short harness that makes Claude Code read this document and the detailed rules in order. Always loaded, so keep it short and declarative — no step-by-step procedures.
- [AGENTS.md](AGENTS.md): links AGENTS-aware tools to `CLAUDE.md` and this document.
- [`.claude/skills/`](.claude/skills/): on-demand procedures for recurring tasks, loaded only when invoked (for example `/pr`). A skill orders and executes existing rules; it never introduces a new rule. When it restates something from `docs/development/` or a template, the document stays authoritative and the skill must be updated with it.

## Where each rule is enforced

Every rule has exactly one **authoritative** enforcement point. Anything else that repeats the rule is a convenience copy and must never be the only thing standing behind it.

| Rule | Authoritative enforcement | Convenience copies |
| --- | --- | --- |
| Tests and static analysis pass | `backend-ci / check` (required by branch protection) | `CLAUDE.md` rule 8 tells the agent to run it locally first |
| Applied Flyway migrations are never modified | `backend-ci / check` — "Verify applied migrations were not modified" | [database convention](docs/development/database-conventions.md), PR review |
| Duplicate migration versions | `FlywayMigrationTests` (Flyway fails on duplicate versions) | migration [README](src/main/resources/db/migration/README.md) |
| Jira key in branch, commit, PR | PR review + [Jira automation](docs/development/jira-workflow.md) | `/pr` skill |
| Conversation resolution, squash merge | Branch protection on `dev` (approving reviews: 0) | [workflow](docs/development/workflow.md) |
| Migration version ranges per part (V2–V99, V100–V199) | PR review — deliberately not automated (a wrong-range file is still valid SQL, and false blocks cost more than the rule is worth) | migration README |

Before adding a new rule, decide its enforcement point first. Prefer CI: it runs in one known environment and fails loudly. Local mechanisms (Claude Code hooks, git hooks) fail **open and silently** — when they break, nothing tells you, and an invisible safety net is worse than none because people rely on it.

## Claude settings boundary

Keep shared, repo-wide protections in [`.claude/settings.json`](.claude/settings.json) only. Put per-person permissions and environment settings in `.claude/settings.local.json` (Git-ignored); copy the [example file](.claude/settings.local.json.example) to start. Do not commit personal settings or add personal permissions to the team settings.

**Design docs stay local.** Brainstorming specs and plans go under `.claude/superpowers/`, which is Git-ignored — this repo deliberately excludes them (S15P11A705-28). What must outlive the branch goes to `docs/backend/` instead, where it is reviewed and preserved: decisions as `BD-##`, implementation reports and troubleshooting as their own entries.

**Hooks are personal, not shared.** `.claude/hooks/` is Git-ignored. Set one up if you want the same failure reported a few minutes before CI does, and register it in your own `settings.local.json` — never in the shared `settings.json`, which would error for everyone who does not have your scripts. Keep the rule itself in CI so a dead hook costs you convenience and nothing more. The example file explains the portability traps that make hand-written shell hooks fail silently.
