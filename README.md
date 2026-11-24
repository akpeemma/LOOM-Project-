# LOOM — a multimodal Android AI assistant

LOOM is an Android AI assistant that talks, listens, watches the screen,
and acts on it: voice chat, a floating assistant, on-screen pointer
guidance, and step-by-step task execution through Android accessibility
services. It runs on the phone itself — the model plans in words, and
LOOM's own code finds targets on screen, moves the pointer, and taps.

Package: `com.loom.assistant`. Full Kotlin, multi-module Gradle project.
minSdk 26 / targetSdk 35 / compileSdk 35.

## What it does

- **Voice + chat** — talk to LOOM or type; it plans the task and narrates
  what it is doing.
- **Screen awareness** — LOOM reads the live accessibility tree, finds a
  named thing on screen, and guides you to it with the pointer.
- **Pointer as a hand** — the pointer guides, shows LOOM's work live, and
  operates: tap, scroll, type, open apps.
- **Task plans** — a goal becomes ordered steps (open app, search, tap,
  type, submit, verify). Plans are approved before they run; sensitive
  apps (banking, financial, authenticator) are guidance-only, forever.
- **Pluggable LLM providers** — OpenRouter, Google Gemini, or any
  OpenAI-compatible endpoint, plus custom providers. Each provider keeps
  its own key.
- **AuthGate** — sensitive actions need your approval, every time.

## Honest state (November 2026)

This is a work in progress, and the state below is what has actually been
proven on a real phone (Redmi 14C), not what the code hopes to do:

- Voice, chat, and planning work.
- The pointer draws on screen.
- Screen control is roughly 3 out of 10: the hand has operated an app on
  the phone exactly once (playing a song in YouTube Music). Pause,
  play-again, scrolling, and in-app operation are not yet proven.
- No completion claim here is backed by a device test unless it says so.

## Relationship to Brahma

LOOM's architecture was designed after studying how Brahma behaves — not by
copying its code. No Brahma code was copied into this repository: no files,
no translated functions, no assets. Several planner modules reimplement
behavioral rules observed in Brahma (the retry / skip / replan / abort
decision names, the 3-attempt and 2-replan budgets, the rule that steps are
independent and critical steps never skip) in original Kotlin code written
for Android. LOOM is not a fork of Brahma.

## Build

Standard Gradle build (Android Studio or command line):

```bash
./gradlew assembleDebug
```

Requires JDK 17 and Android SDK 35. `tools/build-apk.sh` is the manual
build script used on the original development VM; it expects a local
toolchain and is not the supported path.

## API keys

Keys are entered inside the app under Settings → AI Connections. They are
stored in the Android Keystore and are never written into the code, the
build, or this repository. There are no keys, tokens, or credentials in
this repo — if you find one, it is a bug; please report it.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
