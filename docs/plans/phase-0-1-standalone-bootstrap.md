# Laminate Standalone — Phase 0/1: Scaffold, laminate-api migration, build-time Paper bundling

## Context

Laminate is a Paper (Minecraft 1.21.11) fork living at `D:\Programmieren\Minecraft\Laminate` that adds
"hybrid plugins" (Bukkit plugins carrying SpongePowered Mixin transforms) via a vendored quilt-loader
Knot-style launcher adapter. Updating Paper currently means merging upstream into the fork by hand.

This plan de-forks Laminate: instead of forking Paper's sources, the new repo (`D:\Programmieren\Minecraft\laminate-standalone`,
freshly `git init`-ed, empty) builds a **launcher/agent that wraps an unmodified official Paper (paperclip) jar**,
downloaded **at Gradle build time** and merged into a single distributable `laminate-server-<mcversion>.jar`.
Updating Paper becomes: bump `paperVersion` in `gradle.properties`, rebuild.

This plan covers only the first, foundational slice: repo scaffold, migrating the existing `laminate-api`
module verbatim, and getting a bundled jar that boots a **vanilla, unmodified** Paper server end to end.
The mixin/quilt-loader runtime, core mixins, and hybrid-plugin support are explicitly **out of scope** —
those come in a later plan once this foundation is proven.

The full target architecture (for orientation only — most of it is future work, not this plan):

```
laminate-server-<mcversion>.jar = paperclip contents + Laminate launcher classes + (later) nested
runtime libs under META-INF/laminate-libs/. Manifest: Main-Class=LaminateMain,
Bundler-Main-Class=<paperclip's own Main-Class, read from paperclip's manifest at build time>.
```

## Global Constraints (binding on every task below)

- **Java 21** toolchain, **Gradle Kotlin DSL** (`build.gradle.kts` / `settings.gradle.kts`), matching the
  fork's setup.
- **Package root:** `eu.lschreiber.laminate.*` for all new launcher/runtime code (`eu.lschreiber.laminateapi`
  stays as-is for the migrated `laminate-api` module — do not rename it).
- **Lombok everywhere** in new code: apply the `io.freefair.lombok` Gradle plugin (latest stable 8.x; if the
  exact version below fails to resolve, use the newest available 8.x release and note the substitution) to
  every new Java module. Use `@Getter`, `@RequiredArgsConstructor`, `@Builder`, `@UtilityClass`,
  `@SneakyThrows`, etc. wherever they remove boilerplate. Prefer Java `record`s for pure immutable data
  carriers; use Lombok for everything else (services, builders, mutable state).
  **Exception:** do not introduce Lombok into the migrated `laminate-api` module's copied source in Task 2 —
  that is a verbatim copy, not a refactor.
- **Module layout** (create all as Gradle subprojects; only `laminate-api` and `laminate-launcher` get real
  code in this plan — the rest are stubs for future plans):
  ```
  settings.gradle.kts        includes: laminate-api, laminate-bridge, laminate-core-mixins,
                              laminate-runtime, laminate-launcher
  laminate-api/               migrated in Task 2, package eu.lschreiber.laminateapi
  laminate-bridge/            stub only (Task 1) — future: cross-classloader state bridge
  laminate-core-mixins/       stub only (Task 1) — future: paperweight-dev-bundle mixins
  laminate-runtime/           stub only (Task 1) — future: mixin/quilt bootstrap
  laminate-launcher/          real code in Tasks 3-4: LaminateMain, Paper download, jar bundling
  ```
  A "stub" module = a `build.gradle.kts` with `java` + the Lombok plugin applied and nothing else; no
  source files. This lets `settings.gradle.kts` include all five now without inventing code that later
  plans will design properly.
- **Versions** (use these exact coordinates unless a task hits a real resolution failure, in which case use
  the closest available compatible version and note it): this plan does not add quilt-loader, sponge-mixin,
  mixinextras, or asm as dependencies anywhere — those belong to `laminate-runtime` in a future plan. Do not
  add them speculatively.
- **Paper version source of truth:** `gradle.properties` key `paperVersion` (e.g. `1.21.11`). An optional
  `paperBuild` key pins an exact build number; when absent, resolve the latest `STABLE` build for
  `paperVersion`.
- **Paper Fill API v3** is the primary source for downloads:
  `GET https://fill.papermc.io/v3/projects/paper/versions/{paperVersion}/builds/latest` (or
  `/builds/{paperBuild}` when pinned) → JSON body containing `downloads["server:default"].url` and
  `downloads["server:default"].checksums.sha256`. Requests **must** set a descriptive `User-Agent` header
  (e.g. `laminate-standalone/0.1 (+https://github.com/henceiusegentoo/laminate-standalone)`) — Fill rejects
  generic/default user agents. Fall back to the legacy v2 API
  (`https://api.papermc.io/v2/projects/paper/versions/{paperVersion}/builds`, sha256 under
  `downloads.application`) if v3 is unreachable or returns a non-2xx.
- **No runtime download.** Nothing in the shipped `laminate-server-<mcversion>.jar` may contact the network
  to fetch Paper. All download/verification happens in the Gradle build.
- **Do not modify anything under `D:\Programmieren\Minecraft\Laminate`** (the existing fork) — it is a
  read-only reference for this work, not a target.
- **Licensing note:** the bundled jar contains only paperclip (which itself contains no Mojang code —
  paperclip downloads the vanilla server on first boot, same as any stock Paper install) plus
  Laminate's own code. No Mojang/Paper source is vendored into this repo.
- Commit after each task with a clear message; do not use `--no-verify` or amend.

---

## Task 1: Repository scaffold

Set up the new Gradle multi-module repo skeleton at `D:\Programmieren\Minecraft\laminate-standalone`
(already `git init`-ed, currently empty — do not re-run `git init`).

Create:
- `settings.gradle.kts`: `rootProject.name = "laminate-standalone"`; includes the five modules listed in
  Global Constraints; plugin management repositories: `gradlePluginPortal()`,
  `maven("https://repo.papermc.io/repository/maven-public/")` (mirrors the fork's `settings.gradle.kts` at
  `D:\Programmieren\Minecraft\Laminate\settings.gradle.kts` — read it for the `foojay-resolver-convention`
  plugin block and reuse that block verbatim; drop the fork's git-clone-check `error(...)` block and the
  `optionalInclude`/build-cache machinery, none of which apply here).
- Root `build.gradle.kts`: configures Java 21 toolchain and applies the `io.freefair.lombok` plugin
  (`io.freefair.lombok` version `8.11` — see Global Constraints fallback note) to all subprojects via
  `subprojects { apply(plugin = "java"); apply(plugin = "io.freefair.lombok"); ... }`, sets
  `group = "eu.lschreiber.laminate"` and `version` from a `laminateVersion` gradle property (default
  `"0.1.0-SNAPSHOT"`).
- `gradle.properties`: `paperVersion=1.21.11`, `laminateVersion=0.1.0-SNAPSHOT`, plus the standard
  `org.gradle.jvmargs`/`org.gradle.parallel=true` entries copied from the fork's
  `D:\Programmieren\Minecraft\Laminate\gradle.properties` (drop fork-specific keys like the
  `paperBuildCache*` ones — those belong to Paper's own infra, not this repo).
- Gradle wrapper: copy `gradlew`, `gradlew.bat`, and `gradle/wrapper/*` **verbatim** from
  `D:\Programmieren\Minecraft\Laminate` (same Gradle version — no need to invent a new one).
- `.gitignore`: copy from the fork (`D:\Programmieren\Minecraft\Laminate\.gitignore`) and add `/laminate/`
  (a future runtime data dir) if not already covered by an existing `build/`-style pattern.
- `.editorconfig`: copy verbatim from the fork.
- `LICENSE.md`: copy verbatim from the fork (`D:\Programmieren\Minecraft\Laminate\LICENSE.md`).
- `README.md`: a short stub — project name, one-paragraph description (standalone Paper launcher/agent
  enabling hybrid mixin plugins; supersedes the `Laminate` fork), and a "Status: early scaffold, not yet
  functional" note. Do not write extensive docs here — that's a later plan.
- Four stub module directories (`laminate-bridge`, `laminate-core-mixins`, `laminate-runtime`) each with a
  minimal `build.gradle.kts` (just the module-specific bits not already covered by the root `subprojects`
  block — likely empty or near-empty given Lombok/Java are applied at the root). `laminate-launcher`'s
  `build.gradle.kts` is created in Task 3, not here — leave that directory empty in this task (just add it
  to `settings.gradle.kts`'s `include(...)` list).

**Verify:** `./gradlew help` (or `.\gradlew.bat help` on Windows) succeeds from the repo root and lists all
five subprojects under `Detailed task info` / `./gradlew projects`.

**Commit:** all of the above as one commit, message along the lines of "Scaffold laminate-standalone
multi-module Gradle project".

---

## Task 2: Migrate `laminate-api` verbatim

Copy the existing `laminate-api` module from the fork into this repo unchanged in behavior.

Source (read, then copy): `D:\Programmieren\Minecraft\Laminate\laminate-api\src\**` — this includes:
- `main/java/eu/lschreiber/laminateapi/accessor/{GenerateAccessor,GenerateAccessorProcessor,LaminateAccessors}.java`
- `main/java/eu/lschreiber/laminateapi/event/{InjectEvent,At,Rewrite,RewriteProcessor,InjectEventProcessor,LaminateEventBridge}.java`
- `main/java/eu/lschreiber/laminateapi/internal/{JvmDescriptors,MixinTargetResolver}.java`
- `main/resources/META-INF/services/javax.annotation.processing.Processor`
- `test/java/eu/lschreiber/laminateapi/**` (all 5 existing test classes)

Copy every file's content **unchanged** (package names, class names, logic all stay identical). Only the
**build file** is new: `laminate-api/build.gradle.kts`, adapted from
`D:\Programmieren\Minecraft\Laminate\laminate-api\build.gradle.kts` — read it and reproduce its dependency
set (`sponge-mixin` compileOnly, `paper-api` compileOnly, JUnit/AssertJ/Mockito test deps — check the fork's
exact coordinates and test framework versions) against this repo's own repositories (add
`maven("https://repo.papermc.io/repository/maven-public/")` and
`maven("https://maven.fabricmc.net/")` to this module's `repositories {}` block if the fork's root-level
repositories aren't inherited — check how the fork wires this and replicate at the module level here, since
this repo's root `build.gradle.kts` does not declare those repos). Do not carry over the fork's Maven
publishing block if it references fork-specific coordinates/URLs — a minimal `maven-publish` setup with this
module's own `group`/`artifactId` is fine, or omit publishing entirely if the fork's block is complex; note
what you did.

Do **not** add Lombok to this module's copied source (see Global Constraints exception) — the build file may
still have the Lombok plugin applied via the root `subprojects` block; that's fine, just don't use its
annotations in the copied code.

**Verify:** `./gradlew :laminate-api:build` passes, including all 5 test classes (report the exact test
count and pass/fail in your report).

**Commit:** "Migrate laminate-api module from the Laminate fork".

---

## Task 3: `downloadPaperclip` Gradle task

In the (currently empty) `laminate-launcher` module, create `laminate-launcher/build.gradle.kts` (`java`
plugin + Lombok from root `subprojects`, plus an `application`-style setup is NOT needed — this module
produces a plain jar via a custom task, not `application` plugin) and implement a Gradle task
`downloadPaperclip` that:

1. Reads `paperVersion` (required) and `paperBuild` (optional) from Gradle properties.
2. Resolves the build to fetch per Global Constraints (Fill v3 primary, legacy v2 fallback), sending the
   specified `User-Agent`.
3. Downloads the `server:default` (v3) / `application` (v2) artifact to
   `laminate-launcher/build/paperclip/paperclip-<paperVersion>-<resolvedBuild>.jar`.
4. Verifies the downloaded file's SHA-256 against the checksum from the API response; fails the build loudly
   (clear error message naming both checksums) on mismatch.
5. Is **incremental/up-to-date-aware**: if the target file already exists and its SHA-256 matches the
   already-known checksum (cache a small `.sha256` sidecar file, or use Gradle's `@OutputFile`/
   `@Input`-annotated task inputs so Gradle's own up-to-date check applies), skip re-downloading. Prefer
   wiring this as a proper Gradle custom task class (`abstract class DownloadPaperclipTask : DefaultTask()`)
   with declared `@Input` (`paperVersion`, `paperBuild` or resolved-build-number) and `@OutputFile`
   properties, rather than an ad hoc `doLast` with manual file existence checks — this gets Gradle's
   incremental build support for free.
6. Logs which build number/channel was resolved and used.

You will need an HTTP client and a JSON parser usable from Gradle's buildscript classpath. Options: (a) a
small `buildSrc`/included-build convention plugin with its own dependencies (e.g. add a JSON library like
`com.squareup.moshi` or reuse `com.google.code.gson:gson` — check what's easily addable), or (b) implement
the task logic in `laminate-launcher`'s own `src/main/java` as a plain class using `java.net.http.HttpClient`
(built into Java 21, no extra dependency) and minimal manual JSON field extraction (the response shape is
small and stable — extracting `downloads["server:default"].url` and `.checksums.sha256` by simple string/regex
parsing is acceptable if you want to avoid a JSON library dependency; use a real JSON parser instead if one is
already easy to add without much ceremony). Prefer whichever keeps the task's own dependencies minimal; note
your choice and why in your report. If you add any new dependency, add it to a `libs.versions.toml` version
catalog at `gradle/libs.versions.toml` (create it) rather than hardcoding the coordinate inline, and update
Task 1's Global Constraints assumption (there is none yet, since Task 1 didn't create a catalog — creating it
here is fine and expected).

Register `downloadPaperclip` in `laminate-launcher/build.gradle.kts`.

**Verify:** running `./gradlew :laminate-launcher:downloadPaperclip` with `-PpaperVersion=1.21.11` (or the
`gradle.properties` default) actually downloads a real paperclip jar from the live Fill API, verifies its
checksum, and reports success. Run it a second time and confirm it is reported `UP-TO-DATE` (or skips
re-download via your own logic) rather than re-downloading. Include the actual command output in your
report.

**Commit:** "Add downloadPaperclip build task with Fill v3/v2 resolution and sha256 verification".

---

## Task 4: `LaminateMain` + `laminateServerJar` bundling task

Still in `laminate-launcher`:

1. Create `laminate-launcher/src/main/java/eu/lschreiber/laminate/launcher/LaminateMain.java`. For this
   task's scope (no agent, no relaunch yet — that is future work), its `main(String[] args)` must:
   - Read this jar's own `META-INF/MANIFEST.MF`, extract the `Bundler-Main-Class` attribute (fail with a
     clear error if absent).
   - Invoke that class's `public static void main(String[])` via reflection, forwarding `args` unchanged.
   - Load that class through a **fresh `URLClassLoader`** whose classpath is this running jar's own location
     (`LaminateMain.class.getProtectionDomain().getCodeSource().getLocation()`) and whose **parent is the
     platform classloader** (`ClassLoader.getPlatformClassLoader()`), *not* the current thread's context/
     system classloader. This is the "parent interposition" mechanism the full architecture depends on later
     (so that a shared runtime loader can sit between platform and paperclip's own loader) — get this detail
     right even though there is no shared runtime loader to interpose yet in this task; a code comment
     explaining why should reference this future need briefly (one line, not a design essay).
   - Set that `URLClassLoader` as the current thread's context classloader before invoking, since paperclip
     reads `Thread.currentThread().getContextClassLoader()` when constructing its own server classloader.
2. Add a Gradle task `laminateServerJar` (custom task class, not ad hoc `doLast`) that:
   - Depends on `downloadPaperclip` and the module's own `compileJava`/`jar`.
   - Reads the downloaded paperclip jar's manifest to get its **own** `Main-Class` value (this becomes our
     `Bundler-Main-Class`).
   - Produces `laminate-launcher/build/libs/laminate-server-<paperVersion>.jar` by merging: all entries from
     the downloaded paperclip jar, **except** its `META-INF/MANIFEST.MF` and any `META-INF/*.SF`/`*.RSA`/
     `*.DSA` signature files (strip these — signed paperclip signatures don't survive being repackaged, and
     leaving them in produces a corrupt-signature error at runtime), plus the compiled `LaminateMain.class`
     (and its package directories) from this module's own `compileJava` output, then writes a new manifest
     with exactly: `Main-Class: eu.lschreiber.laminate.launcher.LaminateMain`,
     `Bundler-Main-Class: <value read from paperclip's manifest>`, `Manifest-Version: 1.0`. Do **not** add
     `Premain-Class`/`Agent-Class`/`Launcher-Agent-Class`/`Can-Retransform-Classes`/`Can-Redefine-Classes` —
     those are out of scope for this task (a future task adds the agent).
   - On duplicate entries between paperclip's jar and our own compiled output, our own classes win (there
     should be no real collisions here, but be defensive: same policy as the fork's existing
     `createLaminateServerJar` task at `D:\Programmieren\Minecraft\Laminate\paper-server\build.gradle.kts`
     — read that task for the merge-mechanics pattern, e.g. `DuplicatesStrategy.EXCLUDE` with our classes
     listed first, even though this task is written fresh rather than copied).

**Verify:**
- `./gradlew :laminate-launcher:laminateServerJar` succeeds and produces
  `laminate-launcher/build/libs/laminate-server-1.21.11.jar` (or whatever `paperVersion` resolves to).
- From a **clean temporary directory** (not the repo), run
  `java -jar <path-to-jar>/laminate-server-1.21.11.jar --nogui` with a `eula.txt` containing `eula=true`
  pre-created (accepting the Minecraft EULA is required for the server to start non-interactively — this is
  standard for any Paper/vanilla server boot, including in CI; create this file yourself for the test) and
  confirm the **unmodified vanilla Paper server actually reaches "Done" in its startup log** (grep for
  `Done (`), then send `stop` to its stdin and confirm a clean shutdown/exit code 0. This is the core
  end-to-end proof for this plan — do not skip it or substitute a shorter check. Include the relevant log
  excerpt (the "Done" line and the clean-shutdown lines) in your report.
- Run the boot a second time in the same temp directory to confirm it starts fine on an already-initialized
  world/data directory too.

**Commit:** "Add LaminateMain entry point and laminateServerJar bundling task".

---

## End state after this plan

`./gradlew :laminate-launcher:laminateServerJar` from a clean checkout produces a single jar that, run with
`java -jar`, boots an unmodified official Paper 1.21.11 server to completion — with zero manual Paper
download/setup by the operator. No mixin/quilt-loader/hybrid-plugin functionality exists yet; that is
deliberately deferred to a follow-up plan once this foundation is verified working.
