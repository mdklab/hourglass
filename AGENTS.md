# AGENTS.md

## Project Overview
- Project name: `hourglass`
- Main app: `HourglassApp.java`
- Type: single-file Java Swing desktop widget (timer + clock)
- Build output: `out/`

## Goals For Changes
- Keep the app lightweight and dependency-free.
- Preserve current behavior unless the task explicitly requests behavior changes.
- Favor clarity and maintainability over clever shortcuts.

## Local Commands
- Build: `.\build.cmd`
- Run: `.\run.cmd`
- Clean build output (optional): `Remove-Item -Recurse -Force .\out`

## Code Guidelines
- Use ASCII by default.
- Keep comments in English.
- Add short, meaningful comments only where logic is non-obvious.
- Keep UI behavior centralized through existing `render()` and mode state transitions.
- Avoid introducing extra frameworks or libraries.

## Functional Areas In `HourglassApp.java`
- Input parsing:
  - Relative durations (`59m`, `1h 20m`, `12:30`, `1:02:03`)
  - Absolute expressions (`tomorrow`, `fri at 9am`, `2026-02-26 14:00`)
- Runtime modes:
  - `STOPPED`, `RUNNING`, `PAUSED`, `EXPIRED`
- Views:
  - Timer view and clock view
- Persistence:
  - Uses properties file under `%APPDATA%\HourglassClone\settings.properties` (or user-home fallback)

## Safety Notes
- Do not delete user data or reset git history unless explicitly requested.
- If changing persisted keys in settings, keep backward compatibility or provide migration logic.
- Validate by running `.\build.cmd` after code edits.

