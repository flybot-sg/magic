# Contributing

## Filing issues

We treat every issue as a **problem statement**, following Extreme Programming. The title states the problem; the body describes it; an optional suggestion section proposes how it might be fixed.

### Title

Phrase the title as a problem statement, not a solution or action.

- Good: `Query params with null values cause 500 errors`
- Bad: `Add null handling to query params`

### Body template

    ## Problem

    What is wrong. How to reproduce. Symptoms, error messages, relevant file paths.

    ## Suggestion

    Optional. How the fix could be approached. Omit the section entirely if you have no concrete suggestion.

Keep `## Problem` concrete: how to reproduce, observed vs expected behaviour. Two sections only.

## Labeling issues with affected components

MAGIC is a monorepo. Every issue should carry one or more `comp:` labels naming the top-level directories it touches:

- `comp:clojure-runtime`
- `comp:magic-runtime`
- `comp:mage`
- `comp:magic-compiler`
- `comp:nostrand`
- `comp:magic-unity`
- `comp:magic-unity-smoke`

## Pull requests

PRs target the `develop` branch. `main` only receives merges from `develop` via release PRs.

### Title

Same format as a commit title:

    <prefix>(<scope>): <short description>

Keep it to one line.

### Description

    Closes #<issue-number>

    ---

    - First change description
    - Second change description

Reference the issue with `Closes #<n>` in the description so GitHub closes it when the change reaches `main`. Keep bullets short; the issue carries the full context.

## Commits

We follow [Conventional Commits](https://www.conventionalcommits.org/):

    <prefix>(<scope>): <description>

Common prefixes: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`. Keep the title to one line; details belong in the PR description.

Reference the related GitHub issue in the title or body, e.g. `(#42)` or `Closes #42`.

### Paired bootstrap refresh

When a source change touches a stdlib namespace or magic-compiler internals, the committed `.clj.dll`s under `nostrand/references/` and `magic-unity/Runtime/Infrastructure/Export/` need a paired refresh commit:

    chore(bootstrap): refresh <name> DLL for <short reason> (#<issue>)

This keeps the committed binaries in lockstep with their sources.
