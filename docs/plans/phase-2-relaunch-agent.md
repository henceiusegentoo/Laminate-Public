# Laminate Standalone — Phase 2: Relaunch + Java Agent

## Context

Phase 0/1 (already complete — see `docs/plans/phase-0-1-standalone-bootstrap.md`) produced
`laminate-server-<version>.jar`, which boots an unmodified, official Paper 1.21.11 server end to end.
`LaminateMain` currently just reads `Bundler-Main-Class` from its own manifest and invokes it directly —
there is no agent and no relaunch yet.

The full architecture (see the original de-fork plan in the earlier conversation, and
`D:\Programmieren\Minecraft\Laminate`'s existing fork for the mechanism being ported) needs a JVM
**Instrumentation** handle captured before any server code runs, plus four `--add-opens` JVM flags that
can only be supplied at JVM *launch* time (they cannot be injected into an already-running JVM). Since
operators should not have to remember flags, Laminate self-relaunches: if the process isn't already running
with the right flags and agent, it re-execs itself as a child JVM with everything set correctly.

This plan phase ports that mechanism from the fork, which already has a working, battle-tested
implementation to reference (read, don't copy blindly — this is a fresh implementation, but the fork's
design is proven and should be followed closely):
- `D:\Programmieren\Minecraft\Laminate\paper-server\src\main\java\io\papermc\paper\LaminateLauncher.java`
  (relaunch detection + child-process construction + argument forwarding)
- `D:\Programmieren\Minecraft\Laminate\paper-server\src\main\java\io\papermc\paper\mixin\PaperMixinAgent.java`
  (premain/agentmain + Instrumentation stashing)

**Do not modify anything under `D:\Programmieren\Minecraft\Laminate`** — read-only reference only.

Mixin/quilt-loader runtime work is still out of scope for this phase. The only observable new behavior
after this phase: the launcher transparently relaunches itself with the right flags and agent, and an
`Instrumentation` handle is captured and available (though nothing consumes it yet — that's the next phase).

## Global Constraints (binding on every task below)

- Package: `eu.lschreiber.laminate.launcher` for the relaunch handler (same package as `LaminateMain`);
  `eu.lschreiber.laminate.agent` for the new agent class.
- **System property keys** (must match between the two classes, since they communicate only through JVM
  system properties across the parent/child-JVM boundary and across classloaders):
  - `laminate.agent.loaded` — set to `"true"` by the agent's `premain`/`agentmain` before either returns.
    `LaminateMain` checks this to decide whether to relaunch.
  - `laminate.instrumentation` — the agent stashes the `java.lang.instrument.Instrumentation` instance here
    via `System.getProperties().put(...)` (not `System.setProperty`, which only accepts `String` values —
    the fork's `PaperMixinAgent` does exactly this, read it for the precise mechanism).
- **Manifest attributes** to add to the `laminateServerJar` task's output (in addition to the existing
  `Main-Class`/`Bundler-Main-Class`/`Manifest-Version` from Phase 0/1, which stay unchanged):
  - `Premain-Class: eu.lschreiber.laminate.agent.LaminateAgent`
  - `Agent-Class: eu.lschreiber.laminate.agent.LaminateAgent`
  - **Do NOT add `Launcher-Agent-Class`.** *(Correction, added during Task 2: the original draft of this
    plan called for `Launcher-Agent-Class` as a "convenience fallback." Task 2's implementer proved via live
    testing that this is actively incompatible with the relaunch mechanism below — `Launcher-Agent-Class`
    makes the JVM invoke `agentmain` and set `laminate.agent.loaded=true` on the flag-less first launch too,
    which makes `LaminateMain` believe the agent is already loaded and skip the relaunch that's supposed to
    add the required `--add-opens` flags. Those flags can never be added after the fact, so the two
    mechanisms cannot coexist on the same jar. Task 2 removed `Launcher-Agent-Class` from Task 1's manifest
    wiring to match the reference fork's actual manifest, which never included it either. This note
    documents the correction for anyone reading this plan after the fact.)*
  - `Can-Retransform-Classes: true`
  - `Can-Redefine-Classes: true`
- **Relaunch command construction** (mirror the fork's `LaminateLauncher.relaunchWithAgent` precisely):
  - Java executable: prefer `ProcessHandle.current().info().command()`, fall back to
    `Path.of(System.getProperty("java.home"), "bin", "java")`.
  - Self jar path: `LaminateMain.class.getProtectionDomain().getCodeSource().getLocation()` resolved to an
    absolute `Path`.
  - Forward all existing JVM input arguments (`ManagementFactory.getRuntimeMXBean().getInputArguments()`),
    **except** strip any `-javaagent:` entry whose path (before any `=options` suffix) normalizes to the
    same file as the self jar (to avoid double-registering the agent) — preserve every other `-javaagent`
    entry unchanged (profilers, APM agents, etc. must survive).
  - Append, in order: the four `--add-opens` flags below, `-javaagent:<selfJar>`, `-jar`, `<selfJar>`, then
    every original application argument unchanged.
  - The four required flags (exact strings, must match what the fork uses):
    ```
    --add-opens=java.base/java.lang=ALL-UNNAMED
    --add-opens=java.base/java.lang.reflect=ALL-UNNAMED
    --add-opens=java.base/java.io=ALL-UNNAMED
    --add-opens=java.base/java.util=ALL-UNNAMED
    ```
  - Launch via `ProcessBuilder`, `inheritIO()`, and `System.exit(process.waitFor())` in the parent — the
    parent process's exit code must equal the child's.
- If `laminate.agent.loaded` is already `"true"` when `LaminateMain.main` runs (i.e. this JVM was already
  launched correctly, or is the relaunched child), skip straight to the existing Phase 0/1 behavior (read
  `Bundler-Main-Class`, build the interposed `URLClassLoader`, invoke it) — do not relaunch again.
- No behavior from Phase 0/1 may regress: the manifest still needs exactly the previously-required
  attributes plus the new ones above; `Bundler-Main-Class` resolution and the platform-classloader
  interposition in `LaminateMain` must be unchanged.
- Scope: only `laminate-launcher` module (new `agent` package, `LaminateMain` changes) and the
  `LaminateServerJarTask` in `buildSrc` (manifest attributes only — no merge-logic changes needed).

---

## Task 1: `LaminateAgent`

Create `laminate-launcher/src/main/java/eu/lschreiber/laminate/agent/LaminateAgent.java`.

Read `D:\Programmieren\Minecraft\Laminate\paper-server\src\main\java\io\papermc\paper\mixin\PaperMixinAgent.java`
first for the reference design (premain sets the loaded flag and stashes Instrumentation; agentmain
delegates to premain since the logic is identical for both entry points).

Implement:
- `public static void premain(String agentArgs, Instrumentation inst)` — sets
  `System.getProperties().put("laminate.instrumentation", inst)` and
  `System.setProperty("laminate.agent.loaded", "true")`.
- `public static void agentmain(String agentArgs, Instrumentation inst)` — delegates to `premain` (same
  behavior; this entry point exists so `Launcher-Agent-Class`/dynamic Attach-API loading both work).
- `public static Instrumentation getInstrumentation()` — reads back the stashed instance from
  `System.getProperties().get("laminate.instrumentation")`, cast to `Instrumentation`, or throws a clear
  `IllegalStateException` if absent (useful for the next phase; not consumed by anything yet in this one).

Then update `LaminateServerJarTask` (or `laminate-launcher/build.gradle.kts`, whichever currently owns
manifest-attribute construction — check `LaminateServerJarTask.java` from Phase 0/1 first) to add the five
new manifest attributes listed in Global Constraints, alongside the existing three.

**Verify:**
- `./gradlew :laminate-launcher:laminateServerJar` succeeds.
- Unzip the resulting jar's `META-INF/MANIFEST.MF` and confirm it contains all 7 attributes now (3 from
  Phase 0/1 + 4 new — see the `Launcher-Agent-Class` correction above, which reduced "5 new" to 4), with
  correct values, and nothing else unexpected.
- Write a small standalone check (a throwaway `main`-based smoke test is fine, doesn't need to be permanent
  test infrastructure) that runs
  `java -javaagent:<built-jar> -cp <built-jar> eu.lschreiber.laminate.agent.LaminateAgent` is NOT how agents
  work standalone — instead, verify the agent actually fires by launching a **trivial** separate Java
  program (not the full server — that's Task 2's job) with
  `java -javaagent:<built-jar> -cp <something-with-a-main> <that-main-class>` and having that trivial
  program call `LaminateAgent.getInstrumentation()` and print success, OR (simpler) rely on Task 2's
  end-to-end verification to prove the agent fires, and for this task just prove: (a) manifest correctness
  by inspection, (b) the class compiles and its logic is correct by direct unit-style testing of the
  stash/read round trip if you construct a fake `Instrumentation` — use your judgment on the cheapest real
  proof; document what you did and why in your report. Do not skip proving the agent code itself is
  correct just because full end-to-end proof lands in Task 2.

**Commit:** "Add LaminateAgent and wire agent manifest attributes into laminateServerJar".

---

## Task 2: Self-relaunch wired into `LaminateMain`

Modify `laminate-launcher/src/main/java/eu/lschreiber/laminate/launcher/LaminateMain.java` (read it first —
this is the Phase 0/1 file that currently reads `Bundler-Main-Class` and invokes it directly).

Add a `RelaunchHandler` class (new file, `laminate-launcher/src/main/java/eu/lschreiber/laminate/launcher/RelaunchHandler.java`)
implementing exactly the relaunch mechanics from Global Constraints (read
`D:\Programmieren\Minecraft\Laminate\paper-server\src\main\java\io\papermc\paper\LaminateLauncher.java`'s
`relaunchWithAgent` method first — port its logic faithfully into this fresh file, adapted to this repo's
package/class names).

Wire it into `LaminateMain.main`:
```
if "laminate.agent.loaded" system property is not "true":
    RelaunchHandler.relaunch(args)   // builds child JVM, inheritIO, System.exit(child exit code) — never returns
else:
    // existing Phase 0/1 behavior: read Bundler-Main-Class, build interposed URLClassLoader, invoke it
```

**Verify (this is the core proof for this whole phase):**
1. From a clean scratch directory with a pre-created `eula.txt` (`eula=true`), run
   `java -jar laminate-server-<version>.jar --nogui` **without** any manual flags — confirm:
   - The process actually relaunches exactly once (add a temporary diagnostic log line — or better, a
     permanent one at `INFO`/similar level, e.g. `"[Laminate] relaunching with agent + JVM flags"` printed
     by `RelaunchHandler`, and `"[Laminate] agent loaded, Instrumentation available"` printed once
     `LaminateAgent.getInstrumentation()` succeeds after relaunch — these are useful operator-facing
     diagnostics, not scope creep).
   - The relaunched process reaches `Done (` and shuts down cleanly on `stop`, exactly like the Phase 0/1
     boot test (same methodology: real live boot, not simulated).
2. Foreign `-javaagent` preservation: launch with an unrelated, harmless `-javaagent:` flag pointing at some
   other jar on this machine that will fail to load as a real agent target — actually, simpler and more
   reliable: instead of finding a real foreign agent jar, verify by **inspecting the constructed child
   command line** (log the full command `RelaunchHandler` builds, or capture it via a unit-style test of
   just the command-construction logic in isolation, feeding it a fake `RuntimeMXBean`-style input list)
   rather than relying on an actual foreign javaagent being present on this machine. Confirm: (a) a
   `-javaagent:` entry pointing at the self jar gets stripped, (b) a `-javaagent:` entry pointing at a
   different path is preserved unchanged in the constructed command. Use your judgment on the cleanest way
   to prove this without needing a real third-party agent jar available in this environment — a focused
   unit test of the command-building logic (extracted into a small pure function/method that's easy to test
   without actually spawning a process) is the recommended approach; do this rather than skipping the
   requirement.
3. Confirm running the built jar a **second time already carrying the required flags and agent** manually
   (i.e. construct the exact command `RelaunchHandler` would build and run it directly) does **not**
   relaunch again — it should detect `laminate.agent.loaded=true` and proceed straight to booting Paper.

Include real transcripts/log excerpts for the live relaunch+boot test in your report, matching the rigor of
the Phase 0/1 boot test evidence.

**Commit:** "Add self-relaunch with required JVM flags, wired into LaminateMain".

---

## End state after this plan

`java -jar laminate-server-<version>.jar --nogui`, run with **zero manual JVM flags**, transparently
relaunches itself with the four required `--add-opens` flags and the Laminate agent attached, captures a
JVM `Instrumentation` handle, then boots an unmodified Paper server exactly as it did after Phase 0/1.
Nothing yet consumes the `Instrumentation` handle for its real purpose (installing a mixin
`ClassFileTransformer`) — that is the next phase (mixin/quilt-loader runtime).
