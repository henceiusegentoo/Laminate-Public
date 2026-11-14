# Laminate Standalone — Phase 4: Core Mixins (Paper-Internal UX Polish)

## Context

Phase 3 (complete) delivered the headline feature: hybrid plugins' `@Mixin` transforms genuinely apply to a
real Paper server, launched entirely through the standalone pipeline. What's still missing, relative to the
old fork at `D:\Programmieren\Minecraft\Laminate`, is a small set of **Paper-internal UX behaviors** the fork
implemented by directly editing Paper's own source:

| Fork behavior | Fork mechanism |
|---|---|
| Failed hybrid plugins removed from `/plugins`, don't get initialized | direct edit to `PluginInitializerManager.load` |
| "Laminate plugins (N): ..." category logged at startup | same edit |
| Soft warning on programmatic reload while hybrid plugins are active | direct edit to `PluginInitializerManager.reload` |
| `/reload` hard-refused while hybrid plugins are active | direct edit to `ReloadCommand.execute` |
| "Laminate Plugins" section in `/plugins` output | direct edit to `PaperPluginsCommand.execute` |

This project doesn't fork Paper's source, so these become **Laminate's own mixin configs**, targeting
Paper's *unmodified* classes — registered through the exact same `LaminateRuntimeBootstrap` pipeline hybrid
plugins use, just always-on rather than plugin-supplied. This is "dogfooding" the pipeline: if core mixins
work, it's further proof the whole mechanism is solid.

**Classloader-direction lesson from Phase 3 (read this before writing any code) — CORRECTED, see the
addendum near the end of this document:** core mixin *classes* compile against Paper internals via the
paperweight dev bundle (like `test-plugin` does), and their *woven* code ends up physically inside Paper's
own classes (`PluginInitializerManager`, `ReloadCommand`, `PaperPluginsCommand` — all defined by
`serverLoader`). Static references to Paper-internal types (`LaunchEntryPointHandler`, `PluginProvider`,
etc.) inside a mixin's own method bodies are fine, since Mixin's bytecode weaving never actually
links/verifies the original `@Mixin`-annotated class as a standalone class — only the final woven target
class (`serverLoader`-resolvable) gets linked. **What this original draft got wrong:** it claimed calling
back into `LaminateBridge` from woven code was automatically safe via "ordinary parent delegation" and would
need no reflective workaround. That's true for *resource/class-byte resolution* but turned out **not** to be
true for *static field identity* — Task 4's live testing found a second, different cross-classloader bug this
reasoning missed entirely (`LaminateBridge` ended up as two independent `Class` objects with independently
empty state). See "A second cross-classloader identity bug" near the end of this document for the full,
corrected story — read it before assuming any cross-namespace state-sharing "just works" because the classes
involved are reachable from the same classpath.

**Vanilla Paper source used to design these mixins** (extracted from
`test-plugin/.gradle/caches/paperweight/taskCache/mappedServerJar.jar` — this is the actual, unmodified
1.21.11 source paperweight already downloaded for `test-plugin`; every task below should extract it the same
way rather than trust this plan's excerpts blindly, since paperweight may re-map on a clean checkout):
```
cd test-plugin && unzip -o .gradle/caches/paperweight/taskCache/mappedServerJar.jar \
  "io/papermc/paper/plugin/PluginInitializerManager.java" \
  "io/papermc/paper/command/subcommands/ReloadCommand.java" \
  "io/papermc/paper/command/PaperPluginsCommand.java" -d /some/scratch/dir
```
(If this cache doesn't exist yet in a fresh checkout, run `./gradlew :test-plugin:compileJava` once first —
paperweight populates it as a side effect of Paper dev-bundle setup.)

## Global Constraints

- **Module:** `laminate-core-mixins` (currently an empty stub from Phase 0). Package
  `eu.lschreiber.laminate.mixins.core`.
- **Build setup**, mirroring `test-plugin`'s already-proven pattern: `io.papermc.paperweight.userdev` plugin
  + `paperweight.paperDevBundle("${providers.gradleProperty("paperVersion").get()}-R0.1-SNAPSHOT")`,
  `compileOnly` on `net.fabricmc:sponge-mixin` (same version as `gradle/libs.versions.toml`'s `spongeMixin`),
  `implementation(project(":laminate-bridge"))` (core mixins genuinely need `LaminateBridge` at runtime, not
  just compile time — it's `implementation`, unlike `test-plugin`'s `compileOnly` pattern for things Laminate
  provides externally, because `laminate-core-mixins`' own compiled output gets merged into the same bundle
  that provides `LaminateBridge`, so there's no "provided externally" story here). Reference
  `gradle/libs.versions.toml` for exact version strings, don't hardcode independently.
- **Mixin config file:** `laminate-core-mixins/src/main/resources/laminate-core.mixins.json`, `"required": false`
  for every mixin (a core mixin failing to apply on a future Paper build must degrade — log a warning via
  Mixin's own `handleTargetError`/existing infrastructure — never crash the server; this is the same
  `required: false` posture the very first master-planning pass for this project always intended for this
  module).
- **Registration:** this config must be registered **unconditionally**, every boot, regardless of whether any
  hybrid plugin is present — unlike hybrid plugin configs, which only get registered when discovered. Extend
  `LaminateRuntimeBootstrap`'s Stage A to always queue this one config (own dedicated code path, not routed
  through `PendingHybridPlugin`/`HybridPluginDescriptor`, since there's no plugin JAR or `plugin.yml` involved
  — it's part of the merged jar itself). This also means Stage A must install the gate transformer even when
  zero hybrid plugins are found, since core mixins still need it — re-read `bootstrap()`'s current
  `if (anyDiscovered || mixinBootstrapped)` gating logic before changing it; the condition needs to become
  "always" now that there's always at least this one config to register, and the empty-plugins-boot Task 5/7
  regression check's assumption ("no gate transformer installed when zero plugins") is no longer true —
  that's an expected, correct change in this phase, not a regression to preserve.
- **No `@Mixin` class may be loaded as a real class outside Mixin's own weaving** — same as any mixin,
  standard practice, nothing new here.
- **`LaminateBridge` read-only from core mixins.** They read `loadedHybridPluginNames()`/
  `failedHybridPluginNames()`; nothing here writes to it (writing stays `LaminateRuntimeBootstrap`'s job).
- **Do not modify anything under `D:\Programmieren\Minecraft\Laminate`** (read-only reference fork).
- **Categorization is name-based**, not type-based: the fork used a `LaminatePluginProvider` marker subtype
  (a class that doesn't exist in this project — dropped in the very first architecture decision for this
  de-fork). Wherever the fork checks `provider instanceof LaminatePluginProvider`, this project checks
  `LaminateBridge.loadedHybridPluginNames().contains(provider.getMeta().getName())` instead (note: the fork
  lower-cases the *failed* set but keeps the *loaded* set in original case — match that existing convention
  from `LaminateBridge`, don't introduce a new casing rule here).

---

## Task 1: `laminate-core-mixins` module scaffold + `PluginInitializerManagerMixin`

Set up the module per Global Constraints. Create
`eu.lschreiber.laminate.mixins.core.PluginInitializerManagerMixin`, targeting vanilla Paper's
`io.papermc.paper.plugin.PluginInitializerManager` (extract and read the real source first, per the plan's
Context section — don't trust this brief's excerpt as gospel, verify against your own extraction):

- `@Inject(method = "load", at = @At("TAIL"))`: after vanilla `load(OptionSet)` has finished registering all
  providers (the injection point is the end of the method, after the existing Paper/Bukkit plugin-count
  logging), add:
  1. **Failed-hybrid-plugin culling**: for each entry in `LaunchEntryPointHandler.INSTANCE.getStorage()`,
     `removeIf` any provider whose `getMeta().getName()` (lower-cased) is in
     `LaminateBridge.failedHybridPluginNames()` — same defensive pattern as the fork (log a warning, don't
     throw, if a given entrypoint's provider storage isn't a mutable `Collection`).
  2. **Laminate plugin count logging**: scan the same provider storage for providers whose name is in
     `LaminateBridge.loadedHybridPluginNames()` (these are ordinary `SpigotPluginProvider`s at the Bukkit
     level — no subtype check needed) and log them the same way vanilla logs Paper/Bukkit plugins
     (`LOGGER.info("Laminate plugins ({}): ...")`, respecting `LOGGER.isDebugEnabled()` for one-per-line vs.
     comma-joined, matching vanilla's own existing style exactly since you're injecting right next to it).
  3. Use a plain `System.Logger`-backed logger consistent with `laminate-runtime`'s style — **not**
     `com.mojang.logging.LogUtils`/slf4j (same reasoning as Phase 3: this module's own classes, while they
     compile against Paper internals, shouldn't assume Paper's logging is the right tool for Laminate's own
     log lines; use `System.getLogger("laminate.mixins.core.PluginInitializerManagerMixin")`, prefixed
     `[Laminate]`, matching the established convention).
- `@Inject(method = "reload", at = @At("TAIL"))`: soft warning (not a hard block — that's Task 2) if
  `LaminateBridge.loadedHybridPluginNames()` is non-empty: log a `[Laminate]` warning that hybrid plugins do
  not support being reloaded, mirroring vanilla's own existing Paper-plugin warning block in the same method
  (match its exact log style/formatting for consistency, extracted from the real vanilla source).

**Verify:** this task cannot be meaningfully unit-tested (it's a thin mixin over live Paper internals) —
verification is necessarily live, via Task 4's boot test. For this task alone, verify what's reasonably
checkable now: the module compiles against the real paperweight dev bundle, and
`laminate-core-mixins/src/main/resources/laminate-core.mixins.json` is well-formed and lists this mixin.
Document in your report what remains unverified until Task 4.

**Commit:** "Add laminate-core-mixins module and PluginInitializerManagerMixin".

---

## Task 2: `ReloadCommandMixin`

Create `eu.lschreiber.laminate.mixins.core.ReloadCommandMixin`, targeting vanilla Paper's
`io.papermc.paper.command.subcommands.ReloadCommand` (extract and read the real source first — it's short,
reproduced faithfully in this plan's Context section reasoning, but verify against your own extraction):

- `@Inject(method = "execute", at = @At("HEAD"), cancellable = true)`: if
  `LaminateBridge.loadedHybridPluginNames()` is non-empty, broadcast two red messages via
  `org.bukkit.command.Command.broadcastCommandMessage(sender, ...)` (matching the fork's exact two-line
  message text: "Reloading the server while hybrid plugins are loaded is not supported" and "Consider
  restarting the server instead. Reload cancelled" — same `NamedTextColor.RED`), then
  `callbackInfoReturnable.setReturnValue(false)` (Mixin's cancellation mechanism for an `@Inject` on a
  non-void method — `cancellable = true` gives you a `CallbackInfoReturnable<Boolean>` parameter; use it to
  both cancel further execution of the original method AND supply the return value, matching
  `execute`'s own `boolean` return type and vanilla's own `return false`-on-cancel convention seen elsewhere
  in this same file).

**Verify:** same as Task 1 — compiles against the real dev bundle, config lists this mixin, full behavioral
proof deferred to Task 4.

**Commit:** "Add ReloadCommandMixin: hard-block /reload while hybrid plugins are active".

---

## Task 3: `PaperPluginsCommandMixin` (best-effort)

Create `eu.lschreiber.laminate.mixins.core.PaperPluginsCommandMixin`, targeting vanilla Paper's
`io.papermc.paper.command.PaperPluginsCommand` (extract and read the real source first — it's substantially
more complex than the other two targets: private static helper methods, Adventure `Component` formatting,
`TreeMap`-based sorting/coloring logic for two categories (Paper/Bukkit) that this mixin adds a third to).

**This is explicitly lower-fidelity than the fork's version** — the fork's `PaperPluginsCommand` edit
reused private static helpers (`formatProviders`, `header`) that aren't easily reachable from an injected
mixin without generating `@Invoker` accessor interfaces for each one (real option, but meaningfully more
surface area for a cosmetic feature). Prefer the simpler approach unless you find the accessor approach
genuinely isn't much more work once you're looking at the real code:

- `@Inject(method = "execute", at = @At("TAIL"))`: after vanilla's `execute` has sent the Paper/Bukkit
  category messages, if `LaminateBridge.loadedHybridPluginNames()` is non-empty, send one additional plain
  message line: something like `"Laminate Plugins (N): name1, name2, ..."` in a distinct color (the fork
  used `0xff4245`; matching that exact color is a nice touch but not required) via
  `context.getSource().getSender().sendMessage(...)`. You have direct access to `context` as the injected
  method's own parameter — no need to re-derive the sender.
- If, once you're reading the real code, the accessor-based approach to match the fork's fancier
  multi-line/hover-text formatting turns out to be straightforward, feel free to do that instead — this task
  explicitly grants you latitude on fidelity vs. effort here, unlike Tasks 1-2 which have exact behavioral
  targets. Document whichever choice you made and why.

**Verify:** same pattern as Tasks 1-2 (compile + config listing); full behavioral proof in Task 4.

**Commit:** "Add PaperPluginsCommandMixin: best-effort Laminate Plugins category in /plugins".

---

## Task 4: Wire core mixins into the always-on bootstrap path + bundling + live verification

1. **`LaminateRuntimeBootstrap`**: read the current `bootstrap()` method in full first (it changed
   substantially in Phase 3's lazy-gate rework). Add unconditional registration of
   `laminate-core.mixins.json` alongside (not instead of) hybrid plugin config discovery — this config
   registration happens in Stage B (deferred, like hybrid plugin configs, for the exact same eager-target-
   resolution reason from Phase 3's design), not Stage A. Update the `anyDiscovered || mixinBootstrapped`-style
   gating so the gate transformer always installs (there's always at least the core config to register now),
   and update the empty-hybrid-plugins log line so it doesn't claim "Mixin subsystem not engaged" when core
   mixins are in fact about to engage it.
2. **`LaminateServerJarTask`**: extend the merge to include `laminate-core-mixins`' compiled output +
   resources (the `laminate-core.mixins.json` file), following the exact same pattern already used for
   `laminate-runtime`/`laminate-api`/`laminate-bridge`. `laminate-core-mixins` has no third-party runtime
   dependencies of its own beyond `laminate-bridge` (already merged) and `sponge-mixin` (`compileOnly`,
   already merged via `laminate-runtime`), so this should be a small, low-risk addition to the existing merge
   configuration.
3. **Live verification** (this is the real proof for this whole phase): from a clean scratch directory, run
   through this checklist with real evidence, exactly matching the rigor of Phase 3's Task 7:
   - **Empty plugins, zero hybrid plugins**: boots fine, `Done (`, clean shutdown. Confirm core mixins are
     genuinely installed even with no hybrid plugin present (look for whatever log evidence you build in — a
     "core mixins config injected" style line) and that this doesn't regress anything.
   - **`test-plugin` present, plugin.yml intentionally left valid**: boots fine, hybrid mixin still fires
     (the Phase 3 regression check — don't let this phase break Phase 3's proof), `test-plugin` shows up in
     the normal plugin list, no culling happens (it didn't fail).
   - **A deliberately broken hybrid plugin present** (reuse or construct a fixture jar with `hybrid: true`
     but an invalid/missing mixin config, matching the kind of fixture earlier Phase 3 tasks built) alongside
     `test-plugin`: confirm the broken plugin's provider is genuinely removed from `/plugins` output (or
     doesn't appear in the startup plugin-count logging) while `test-plugin` still loads and works normally.
   - **`/reload` (or the equivalent console command) with `test-plugin` active**: confirm it's hard-blocked
     with the two red messages, server keeps running, `test-plugin`'s mixin keeps working afterward (send
     `testcmd` again post-reload-attempt to prove nothing broke).
   - **`/plugins` output** (send that command via console): confirm your Task 3 addition appears.
   Run everything fully synchronously in blocking tool calls, per this repo's established pattern. If
   anything fails, report BLOCKED with precise diagnostics rather than guessing — same standard as every
   prior live-test task in this project.

**Commit:** "Wire core mixins into the always-on bootstrap path and jar bundling" (plus whatever fix commits
the live verification reveals are needed, each with their own clear message, following this project's
established pattern).

### A second cross-classloader identity bug, found by Task 4's live verification: this plan's own "safe direction" claim was wrong

This plan's Global Constraints section (above) claimed core mixins were structurally immune to Phase 3's
`LaminateEventBridge` classloader-direction problem, reasoning that woven code calling back into
`LaminateBridge` is "ordinary, working parent delegation" needing "no reflective workaround." **Live testing
proved this incomplete.** The parent-delegation argument is correct for *resource/class-byte resolution*
(which is what `LaminateEventBridge`'s bug was actually about) but does not hold for **static field
identity**: `LaminateMain.main()` calls `LaminateRuntimeBootstrap.bootstrap()`/`transformerForGameLoad()` as
ordinary static method calls, so that entire call graph — including `LaminateBridge` itself, referenced from
`LaminateRuntimeBootstrap` at compile time — resolves through the JVM's own **application classloader** (this
jar's `-jar`/`Main-Class` loader), *not* `bundlerLoader`. `bundlerLoader` is a separate `URLClassLoader`
`LaminateMain` constructs later, at runtime, inside `main()`; it never delegates to the application
classloader, so it defines its own, entirely independent `LaminateBridge` `Class` object. Core mixins are
woven into vanilla Paper classes defined by `serverLoader` (a descendant of `bundlerLoader`, confirmed by
Phase 3 Task 8's classloader-interposition fix) — so their references to `LaminateBridge` resolve to
*that* copy, not the one `LaminateRuntimeBootstrap` actually writes to. Two distinct `Class` objects for
identical bytecode never share static field state, no matter how much classpath they have in common.

This was invisible through Phase 3 because no hybrid-plugin mixin ever read `LaminateBridge` from woven
code — only `laminate-api` types, whose accessors/event-bridge are purely reflective/stateless with respect
to this specific split (verified during this phase's final review; not a coincidence worth re-deriving,
just confirmed). Core mixins are the first consumers that read cross-namespace *state*, not just resolve
byte-level resources.

**Fix** (`LaminateRuntimeBootstrap.publishToBundlerNamespaceBridge`, see that method's own javadoc for full
detail): a best-effort *second* publish — after the ordinary `LaminateBridge.recordResult(...)` call on this
class's own (application-classloader) copy, also reflectively resolve `LaminateBridge` through
`excludedBundlerLoader` (a handle to `bundlerLoader`, already captured for an unrelated purpose) and call
`recordResult` on that copy too. Confirmed by live re-verification to cover all three core mixins equally
(they all resolve `LaminateBridge` the same way, via the same `serverLoader` → `bundlerLoader` chain), not
just the one that happened to surface the bug.

**Corrected guidance for any future phase writing code that spans this same boundary:** "reachable from
`bundlerLoader`'s classpath" is necessary but **not sufficient** for two pieces of code to share state — only
code that's actually *resolved through* the same classloader (or a delegation chain between the two) shares a
`Class` object. Byte-level resource lookups (mixin config JSON, `@Mixin` class bytecode, plain
`getResourceAsStream`) are unaffected by this distinction and will keep working across the split; anything
relying on shared *static state* needs an explicit publish mechanism like `LaminateBridge`'s, and that
mechanism itself must be re-examined whenever a new writer or reader is added, per `LaminateBridge`'s own
(now-corrected) class javadoc.

**Test coverage gap, not yet closed:** the only thing exercising `publishToBundlerNamespaceBridge`'s actual
working mechanism (successfully republishing into a *genuinely distinct* `Class` object, not the redundant
same-object case or the exception-caught failure case) is the live-boot verification transcripts in this
task's report — there is no unit test constructing two independent classloaders and proving state actually
crosses between them. A future phase (or a quick follow-up) should add one: build two `URLClassLoader`s over
the same classpath entry, parented so neither delegates to the other, resolve `LaminateBridge` through each,
publish via one, republish via the mechanism above, and assert the *other* object's state actually changed.
This is exactly the kind of earlier, cheaper signal that could catch a regression here without requiring
another live boot.

---

## End state after this plan

`laminate-server-<version>.jar` now replicates every Paper-internal UX behavior the old fork had — failed
hybrid plugins are cleanly disabled and hidden, active hybrid plugins are visible in `/plugins` and startup
logs, and `/reload` is safely blocked while any are loaded — all via Laminate's own mixin pipeline, with zero
lines of forked Paper source anywhere in this repository. This closes out the functional parity gap with the
old fork; what remains after this (a later phase, not covered here) is CI/release automation and
documentation.
