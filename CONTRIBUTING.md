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
- `comp:unity-examples`

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

### Before opening one

Run the local gate first; CI runs the same drift check and tests:

```bash
bb clean
bb build
bb check-drift   # fails on codegen, committed-DLL, dual-variant, or version drift
bb test
```

Keep the order: `check-drift` byte-diffs the committed `.clj.dll` binaries against the rebuild, so the fresh `bb build` before it is what surfaces bootstrap drift. The two C# runtime DLLs in the Unity Export folder embed a git-derived SourceRevisionId and cannot be byte-verified; `check-drift` restores them from HEAD.

See [Development](./README.md#development) for what each task does.

## Commits

We follow [Conventional Commits](https://www.conventionalcommits.org/):

    <prefix>(<scope>): <description>

Common prefixes: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`. Keep the title to one line; details belong in the PR description.

Reference the related GitHub issue in the title or body, e.g. `(#42)` or `Closes #42`.

### Paired bootstrap refresh

When a change affects the committed `.clj.dll`s under `nostrand/references/` and `magic-unity/Runtime/Infrastructure/Export/` (a stdlib or compiler `.clj` edit, or a C# runtime change that alters what the compiler emits), refresh them and commit the new binaries in a paired commit:

    chore(bootstrap): refresh <name> DLL for <short reason> (#<issue>)

Compilation is deterministic: rebuilding unchanged sources reproduces the committed bytes exactly, so only genuinely affected DLLs show up in `git status`, and `bb check-drift` fails if a stale one is left uncommitted.
