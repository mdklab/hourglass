# Hourglass

Hourglass is a compact desktop timer and clock widget implemented as a single Java Swing application.

It is designed to stay minimal, fast, and easy to run on Windows without external dependencies.

## Features

- Borderless, always-on-top style timer window
- Timer states: stopped, running, paused, expired
- Optional loop mode
- Optional close-on-expire behavior
- Clock mode with selectable date format and time zone
- Hover-based link fade animation in active views
- Human-friendly time input parser
- Persistent settings and active timer restore across restarts

## Project Structure

- `HourglassApp.java`: main application source code
- `build.cmd`: compiles the app into `out/`
- `run.cmd`: starts the compiled app
- `out/`: compilation output (git-ignored)

## Requirements

- Windows
- Java JDK (for `javac`) available in `PATH`
- Java runtime available in `PATH`

Quick check:

```powershell
javac -version
java -version
```

## Build

```powershell
.\build.cmd
```

This compiles `HourglassApp.java` into the `out` directory.

## Run

```powershell
.\run.cmd
```

`run.cmd` launches the app with `javaw` so it opens as a desktop window.

## Timer Input Formats

The timer input field accepts both durations and absolute time/date expressions.

### Duration examples

- `59` (minutes)
- `59m`
- `90s`
- `1h 30m`
- `2.5h`
- `1:30` (mm:ss)
- `1:02:03` (hh:mm:ss)

### Absolute examples

- `tomorrow`
- `tomorrow at 08:30`
- `fri`
- `fri at 9am`
- `sun next week at 14:00`
- `2/26`
- `2/26/2026`
- `Feb 26`
- `February 26 2026`
- `2026-02-26`
- `2026-02-26 14:00`

If an absolute target is in the past, the app shifts to the next valid future occurrence where applicable.

## UI Behavior

- Click title text to edit timer title.
- `Start`:
  - From stopped/expired: parses and starts timer input.
  - From running: pauses.
  - From paused: resumes.
- `Stop`: resets to stopped state.
- `Restart`: starts countdown again with the same duration.
- `Clock`/`Timer`: toggles main view mode.
- `Close`: exits the app.

Keyboard shortcuts:

- `Ctrl+P`: pause/resume
- `Ctrl+S`: stop

## Context Menu

Right-click on the window to open settings:

- New timer
- Always on top
- Full screen
- Loop timer
- Pop up when expired
- Close when expired
- Clock submenu:
  - Date only
  - Date format selection
  - Time zone selection

## Persistence

Settings are stored as Java properties:

- Primary path (Windows): `%APPDATA%\HourglassClone\settings.properties`
- Fallback path: `%USERPROFILE%\.hourglass-clone\settings.properties`

Persisted values include:

- title
- last input
- view mode
- clock date format
- clock date-only setting
- clock zone
- option toggles (always on top, loop, popup, close on expire)
- active timer runtime state for resume

## Development Notes

- Keep comments and docs in English.
- Favor small, focused comments for non-obvious logic.
- Keep external dependencies at zero.
- Run `.\build.cmd` after any code changes.

## Troubleshooting

- `Build failed`:
  - Ensure JDK is installed and `javac` is available.
- App does not launch:
  - Run `.\build.cmd` first.
  - Verify `out\HourglassApp.class` exists.
- Wrong time zone/date:
  - Use the right-click menu, then switch to Clock mode to verify.

