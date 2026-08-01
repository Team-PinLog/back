# PinLog Backend Agent Harness

1. Read `CONTRIBUTING.md`.
2. Read the relevant convention under `docs/development/`.
3. Read `docs/backend/spec/` for the domain you are touching, plus the recent entries in `docs/backend/worklog/`. If you are about to overturn a decision already made, read the matching record in `docs/backend/decisions/` first.
4. Inspect existing tests before changing code.
5. Write or update the failing test first.
6. Use PostgreSQL Testcontainers for every DB-dependent test.
7. Backend Flyway migrations use `V2`–`V99` only; `V100`–`V199` belongs to the AI part. Do not redefine `core.feed_event` (AI-owned `V102`), and do not depend on objects from another part's range — apply order differs by environment.
8. Run `./gradlew clean check --no-daemon` before reporting completion.
9. If code and documentation conflict, stop. Do not resolve it yourself — the canonical spec wins and it is not ours to edit. Leave a marked note at the conflicting spot in the document, and repeat it in the PR.
10. Document as you work, in your part's docs zone (backend: `docs/backend/`, AI: `docs/ai/` — follow each zone's `README.md`): decisions with their trade-offs as ADRs, implementation reports and troubleshooting as their own entries, plus one worklog entry per task in the form your zone's `README.md` prescribes.
11. Decision, implement, and troubleshooting records are preservation zones: never delete, update the status instead. Only `spec/` is a living document to edit in place.
