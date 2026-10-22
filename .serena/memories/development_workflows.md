# Development Workflows & Standards

## Issue Tracking with Beads (bd)
This project uses **bd (beads)** with a Dolt database backend.
- Do NOT use markdown TODOs or TodoWrite.
- Claim work: `bd update <id> --claim`
- Check ready work: `bd ready`
- Close work: `bd close <id>`
- Sync Dolt: `bd dolt push` / `bd dolt pull`
- Database location: `.beads/dolt/kikinlex` served on port 63819.
- Store persistent project knowledge: `bd remember "<insight>" --key <key>`

## Code Style & Formatting
- Spotless + ktlint 1.4.1: `./gradlew spotlessApply` / `./gradlew spotlessCheck`.
- Conventional commits enforced by commitlint (`feat:`, `fix:`, `chore:`, etc.).
- Pre-commit hooks installed via `pre-commit install`.

## Mandatory Non-Interactive Commands
Always use `-f` and non-interactive flags (`cp -f`, `rm -rf`, `mv -f`) to prevent CLI hangs.

## Session Close Checklist
1. `bd close <id>` for completed issues.
2. Run quality gates: `./gradlew build` or relevant tests.
3. `git pull --rebase` && `bd dolt push` && `git push`.
