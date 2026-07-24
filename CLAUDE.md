# PinLog Backend Agent Harness

1. Read `CONTRIBUTING.md`.
2. Read the relevant file under `docs/development/`.
3. Inspect existing tests before changing code.
4. Write or update the failing test first.
5. Use PostgreSQL Testcontainers for every DB-dependent test.
6. Do not add H2, empty packages, `.gitkeep`, or speculative domain layers.
7. Do not add Security until the authentication PR includes its full contract and tests.
8. Run `./gradlew clean check --no-daemon` before reporting completion.
9. If code and documentation conflict, stop and record the conflict in the PR.
