# AGENTS.md

## Engineering principle: TDD

Test-Driven Development (TDD) is a mandatory development principle for this repository.

### Rule
For every new behavior, API contract, safety rule, or business rule, follow **Red → Green → Refactor**:

1. **Red** — write the smallest test that expresses the expected behavior and verify that it fails for the right reason.
2. **Green** — implement the minimum production code required to make the test pass.
3. **Refactor** — improve the design while keeping the tests green.

### Instructions for AI coding agents

- **Tests come before implementation** for new behavior whenever the change is testable.
- Do not implement an endpoint, contract, validation rule, provider behavior, or security boundary first and add tests afterward merely to increase coverage.
- When fixing a bug, add a regression test that reproduces the bug before changing production code when practical.
- Treat API schemas, validation rules, safety boundaries, error handling, and provider fallbacks as observable behavior that must be tested.
- Keep tests deterministic, isolated, readable, and focused on behavior rather than implementation details.
- Do not weaken, delete, skip, or bypass an existing test just to make the build pass.
- Do not change production behavior solely to satisfy a test unless that behavior is part of the intended requirement.
- If a requirement is ambiguous, clarify the expected behavior through tests before implementation.
- Existing tests are part of the contract. Preserve them unless the intended behavior has deliberately changed.
- Before considering a change complete, run the narrowest relevant tests and then the repository's normal verification command.

### AI-specific anti-shortcuts

AI agents must not:

- generate production code first and retrofit superficial tests;
- add tests that merely mirror the implementation;
- mock provider/domain boundaries unnecessarily when a focused contract test is clearer;
- increase coverage by testing trivial plumbing instead of meaningful behavior;
- remove a failing test without first identifying the intended contract; or
- declare a task complete without running relevant verification.

### Security and contract guidance

For security-sensitive or mutating behavior, tests must cover both allowed and rejected cases. A passing happy-path test is not sufficient for a safety boundary.

For versioned structured contracts, test compatibility and malformed-input rejection explicitly when the behavior is affected.

### Definition of done

A behavior change is not complete when the code compiles. It is complete when:

- the expected behavior is expressed by automated tests;
- the tests pass;
- the implementation is appropriately simple;
- regressions are covered where applicable; and
- the normal CI/verification checks pass.

### Commands

Use the repository's documented test and Gradle commands. Prefer the normal CI-equivalent verification command when available.
