# execution:interaction (Stage 2C)

The universal interaction layer. Owns `InteractionLayer`, `HaltController`,
`GuidanceRendererContract`, `HaltSource`.

Dependency direction (per STAGE2C-PLAN §1.3): depends on `core:common`,
`intelligence:authgate`, `execution:screenawareness`, `interaction:pointer`,
`platform:storage`. **Never on `platform:services`** — services hosts the
layer and wires it (the host direction is one-way).

Manual build wiring: `tools/build-apk.sh` compiles this module's
`src/main/java` directly (same as `:execution:devicecontrol`); the Gradle
`include` in `settings.gradle.kts` is for a future runnable Gradle.
