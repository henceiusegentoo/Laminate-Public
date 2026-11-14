# Laminate Standalone — Phase 3: Mixin / Quilt-Loader Runtime

## Context

Phases 0-2 (complete) produced `laminate-server-<version>.jar`: a self-relaunching, agent-carrying jar
that merges an unmodified official Paper build with `LaminateMain`, boots to a running vanilla server, and
captures a JVM `Instrumentation` handle before paperclip is even invoked. Nothing has consumed that handle
yet — this phase is the actual point of the whole project: **hybrid plugins**, standard Bukkit/Paper plugins
that ship SpongePowered Mixin configs applied to the live server.

This is a faithful, adapted port of a proven, already-shipped mechanism from the read-only reference fork at
`D:\Programmieren\Minecraft\Laminate` (`paper-server/src/main/java/io/papermc/paper/mixin/`). **Do not modify
anything under that path** — read only.

### Why this port is not a blind copy-paste

The fork runs as a single flat classpath: Paper's own classes and the mixin machinery are loaded by the same
application classloader. Our standalone architecture is different, and Phase 0's `LaminateMain` already
built the shape that matters: it loads paperclip's entry point through a fresh `URLClassLoader`
(`bundlerLoader`, parented to the *platform* classloader, not the system/context loader), and paperclip in
turn builds its own server classloader as `new URLClassLoader(urls, Paperclip.class.getClassLoader().getParent())`
— which resolves to **`bundlerLoader`**, since that's the loader that defined the `Paperclip` class in this
process. So the real hierarchy is:

```
platform classloader
 └─ bundlerLoader        (built by LaminateMain; will contain ALL of our own runtime code
    │                     + quilt-loader + sponge-mixin + mixinextras + asm, merged flat into
    │                     the same jar by an extended laminateServerJar bundling task)
    └─ serverLoader       (built by paperclip; loads every net.minecraft.*/io.papermc.paper.*
                            class, and delegates to bundlerLoader first for anything it doesn't
                            define itself — standard URLClassLoader parent-first behavior)
```

This means: **everything our runtime code needs (mixin/quilt/asm/laminate-api classes) is
already visible to Paper's own classes for free**, via ordinary parent delegation — no bridge classes,
no nested "shared loader extracted to disk" machinery of the kind an earlier draft of this project's plan
envisioned before the build-time-bundling pivot. One real wrinkle remains and is called out explicitly
below (the "pinned game loader" requirement) — everything else is a direct, faithful port.

### One real architectural difference from the fork: class-byte lookup for *target/referenced* classes

The fork's `PaperQuiltLauncher.classLoader` field (`getClass().getClassLoader()`) is, in the fork, the exact
same loader that defines Paper's own classes — so `classLoader.getResourceAsStream("net/minecraft/.../Foo.class")`
just works. In our standalone build, the equivalent class (`LaminateQuiltLauncher`) is loaded by
**`bundlerLoader`**, which is the *parent* of `serverLoader` — and a parent classloader cannot see resources
that only exist on a child classloader's classpath. So `bundlerLoader.getResourceAsStream(...)` for a
`net.minecraft.*` class returns `null`.

The fork's own fallback chain (server loader → thread-context loader → plugin loaders) already anticipates
exactly this kind of gap, and Paperclip's own behavior closes it *most* of the time: Paperclip sets
`Thread.currentThread().setContextClassLoader(serverLoader)` before invoking `org.bukkit.craftbukkit.Main`,
and Minecraft's core classes load synchronously on that same thread during startup — so the context-loader
fallback usually resolves correctly. But it is timing/thread-dependent, and this phase should not depend on
threading being exactly right for correctness. Instead, add a **pinned game loader**: `LaminateMixinTransformer`
receives the actual *defining* `ClassLoader` as the first parameter of every `transform(...)` call — reliable,
not threading-dependent. The first time it transforms a class whose defining loader is not `bundlerLoader`
itself and not a registered plugin loader, record that loader in a `volatile`, set-once static field.
`LaminateQuiltLauncher`'s resource/class-byte lookups then search, in order: **pinned game loader (if set) →
its own loader (`bundlerLoader`) → thread-context loader (if distinct) → registered plugin loaders**. This is
strictly more robust than the fork's chain (it doesn't disappear if a future refactor changes Paperclip's
threading) while still degrading gracefully to the same context-loader-based behavior before the pin is set.

## Global Constraints (binding on every task below)

- **Package root:** `eu.lschreiber.laminate.runtime` for **everything** in the `laminate-runtime` module,
  flat, no subpackages. *(Correction, added during Task 3: an earlier draft of this plan put the quilt
  launcher adapter in a `eu.lschreiber.laminate.runtime.quilt` subpackage while also asking for it to stay
  package-private like the fork's `PaperQuiltLauncher` — those two requirements are mutually exclusive, since
  `LaminateMixinTransformer` and `LaminateRuntimeBootstrap` live one package up and need to call it. The fork
  itself keeps `PaperQuiltLauncher`, `PaperMixinClassFileTransformer`, and `HybridPluginMixinBootstrap` flat in
  one package specifically so package-private encapsulation works between them — match that, not a
  subpackage.)* `eu.lschreiber.laminate.bridge` for `laminate-bridge`. `laminate-api`'s existing package
  (`eu.lschreiber.laminateapi`) is unrelated and unchanged.
- **Dependency coordinates** (exact, matching the fork — `laminate-runtime/build.gradle.kts`):
  ```kotlin
  repositories {
      maven("https://maven.quiltmc.org/repository/release/")
      maven("https://repo.spongepowered.org/repository/maven-public/")
      maven("https://maven.fabricmc.net/")
  }
  dependencies {
      implementation("org.quiltmc:quilt-loader:0.30.0-beta.7")
      implementation("net.fabricmc:sponge-mixin:0.17.0+mixin.0.8.7")
      implementation("io.github.llamalad7:mixinextras-common:0.5.3")
      implementation("org.ow2.asm:asm-util:9.9.1")
      implementation("org.yaml:snakeyaml:2.3")   // or newer stable 2.x - for plugin.yml parsing
      implementation(project(":laminate-bridge"))
  }
  ```
  These are declared `implementation` (not `compileOnly`) because, unlike `laminate-core-mixins` (which
  compiles against a dev bundle but never ships its own copy of Paper), this module's dependencies genuinely
  need to be merged into the final jar — there is no other copy of quilt-loader/sponge-mixin/mixinextras/asm
  anywhere at runtime. Record their coordinates in a new `gradle/libs.versions.toml` version catalog rather
  than hardcoding them inline (this repo doesn't have one yet; creating it here is expected and in scope).
- **Logging:** no `com.mojang.logging.LogUtils`/slf4j (that's Paper's own logging, not on `laminate-runtime`'s
  compile path and not guaranteed present this early in the boot sequence anyway — this all runs *before*
  paperclip, let alone Paper's own log4j setup, exists). Use `System.Logger` via
  `System.getLogger("laminate.runtime.<ClassName>")`, prefixing human-readable messages with `[Laminate]`
  for console consistency with `LaminateMain`/`RelaunchHandler`'s existing `System.out.println("[Laminate] ...")`
  style (`System.Logger` doesn't format a prefix for you — build the message string yourself).
- **`plugin.yml` parsing:** use `org.yaml:snakeyaml` directly (`new Yaml().load(inputStream)` → `Map<String, Object>`),
  not Bukkit's `YamlConfiguration` (which lives in `paper-api`/CraftBukkit, not a dependency of this module).
  Same semantics as the fork: `hybrid: true` (boolean, default `false` if absent → not a hybrid plugin, return
  `null`), `name` (string, required, blank/absent → throw), `mixins` (list of strings, required non-empty →
  throw if absent/empty), each mixin config path must exist as a real entry in the plugin JAR (validate via
  `JarFile.getJarEntry(...)`, throw naming the missing entry if not found).
- **`ClassFileTransformer` exclusion list** (`LaminateMixinTransformer.canTransform`) — the fork's four
  prefixes (`org.apache.logging.log4j.`, `org.spongepowered.asm.`, `org.objectweb.asm.`,
  `io.papermc.paper.mixin.` → adapted to `eu.lschreiber.laminate.runtime.`) **plus**, because our merged jar
  now also carries these where the fork didn't need to: `org.quiltmc.`, `com.llamalad7.mixinextras.`,
  `eu.lschreiber.laminate.bridge.`, `eu.lschreiber.laminate.launcher.`, `eu.lschreiber.laminate.agent.`.
  Do **not** exclude `eu.lschreiber.laminateapi.` — hybrid plugins and injected mixin code are expected to
  reference `LaminateEventBridge`/`LaminateAccessors` at runtime, and those classes have no reason to be
  mixin-transform targets themselves, but excluding the whole package isn't necessary or specified by the
  fork's equivalent (the fork never excluded its own `laminate-api`-equivalent either) — leave it transformable
  (harmless either way; only include it in the exclusion list if you find a concrete circularity issue during
  live testing, and document why if you do).
- **Bootstrap entry point signature** (mirrors the fork's `HybridPluginMixinBootstrap.injectHybridMixins`,
  renamed and re-homed):
  ```java
  // eu.lschreiber.laminate.runtime.LaminateRuntimeBootstrap
  public static Set<String> bootstrap(Instrumentation instrumentation,
                                       Path defaultPluginDir,
                                       List<Path> addPluginFiles,
                                       List<Path> addPluginDirs)
  ```
  Returns lower-cased names of plugins whose bootstrap failed (same contract as the fork). Idempotent (a
  second call returns the cached result) — though in this architecture it will realistically only ever be
  called once, since `LaminateMain` calls it exactly once per process, unlike the fork which called it twice
  (once from `PaperBootstrap`, once from `PluginInitializerManager`) for reasons that don't apply here.
- **Invocation point in `LaminateMain`:** call `LaminateRuntimeBootstrap.bootstrap(...)` **after**
  `Thread.currentThread().setContextClassLoader(bundlerLoader)` and **before**
  `Class.forName(bundlerMainClass, true, bundlerLoader)` — i.e. strictly before paperclip (and therefore
  before any `net.minecraft.*` class) loads. This is earlier than the fork's own timing guarantee, which had
  to race `SharedConstants.tryDetectVersion()`; here there is no race at all.
- **Argument parsing:** `--plugins <dir>` (overrides the default `plugins` directory, relative to CWD if not
  absolute), `--add-plugin <file>` (repeatable), `--add-plugin-dir <dir>` (repeatable) — read directly from
  `LaminateMain`'s raw `args` array (simple positional-value flag parsing; no library needed, this runs
  before Paper's own argument parser exists). Default plugin directory when `--plugins` is absent: `plugins`
  resolved against the current working directory, matching Paper's own default.
- **`laminateServerJar` bundling** must be extended to also merge, into the same flat output jar, alongside
  the existing paperclip contents and `laminate-launcher`'s own compiled classes: the compiled output and
  full resolved runtime classpath of `laminate-runtime` (which transitively pulls in `laminate-bridge`,
  quilt-loader, sponge-mixin, mixinextras-common, asm-util and its own transitive asm-tree/asm-analysis/
  asm-commons artifacts, and snakeyaml) plus `laminate-api`'s compiled output (needed at runtime so
  `LaminateEventBridge`/`LaminateAccessors` are resolvable from both injected server bytecode and hybrid
  plugin code). Same "ours wins, log any unexpected collision" duplicate policy as Phase 0/1's existing merge
  logic — extend it, don't replace it. `laminate-api` does **not** need its own compileOnly `sponge-mixin`/
  `paper-api` dependencies merged in (those stay `compileOnly` there, unchanged); only its own compiled
  classes are needed in the bundle.
- **Do not modify anything under `D:\Programmieren\Minecraft\Laminate`** (read-only reference fork).
- Mixin failures for individual plugins must not crash the server: catch `Throwable` per plugin JAR (matching
  the fork's `processPluginJar`), log a warning, add the plugin's lower-cased name to the failed set, continue
  with the rest. A `ClassFileTransformer.transform` failure for any single class must likewise not propagate
  (catch `Throwable`, log, return `null` so the class loads untransformed) — this is already how the fork's
  transformer behaves; preserve it exactly.

---

## Task 1: `laminate-bridge`

Create `eu.lschreiber.laminate.bridge.LaminateBridge` in the (currently empty stub) `laminate-bridge` module.
No dependencies beyond the JDK.

Static state, written once by `LaminateRuntimeBootstrap` after bootstrap completes, read afterward (by a
later phase's core mixins, and potentially by hybrid plugins themselves) from any classloader that can see
`bundlerLoader` (which, per the Context section above, is every classloader in this process):
- `void recordResult(Set<String> loadedPluginNames, Set<String> failedPluginNames)` (or equivalent — your
  call on exact shape, but it must capture both sets)
- `Set<String> loadedHybridPluginNames()` — unmodifiable view
- `Set<String> failedHybridPluginNames()` — unmodifiable view, lower-cased (matches the fork's convention for
  the failed set)
- `boolean isActive()` — `true` once bootstrap has recorded a result (even if both sets are empty)

Thread-safety: writes happen once, synchronously, during bootstrap on the main thread, before any reader
could plausibly run — but use a thread-safe publication mechanism anyway (e.g. `volatile` fields assigned
atomically, or `AtomicReference`) rather than relying on happens-before through incidental ordering, since
this is exactly the kind of cross-classloader, cross-"is this really the same thread later" state that's easy
to get subtly wrong.

**Verify:** unit tests covering the write-then-read round trip, the unmodifiable-view contract (mutating the
returned set throws or has no effect on internal state), and `isActive()` before/after recording.

**Commit:** "Add LaminateBridge cross-loader hybrid-plugin state holder".

---

## Task 2: Plugin descriptor scanning (`laminate-runtime`)

Add the dependency coordinates from Global Constraints to `laminate-runtime/build.gradle.kts` (create
`gradle/libs.versions.toml` for them). Create, under `eu.lschreiber.laminate.runtime`:

- `HybridPluginDescriptor` — a record: `(String name, List<String> mixinConfigs)` (matches the fork's
  `HybridDescriptor`, renamed for consistency with this repo's naming).
- `HybridPluginDescriptorReader` — port of `HybridDescriptorReader.read(Path)`, using snakeyaml per Global
  Constraints instead of `YamlConfiguration`. Same method contract: `static HybridPluginDescriptor read(Path pluginJar)`,
  returns `null` if not a hybrid plugin (no `plugin.yml`, or `hybrid` absent/false), throws a clear exception
  (your choice of exception type — the fork used Bukkit's `InvalidConfigurationException`, not available
  here; a plain `IllegalStateException` or a small custom exception type both work, pick one and be
  consistent) for a malformed hybrid descriptor (missing/blank name, empty/absent mixins list, a referenced
  mixin config that doesn't exist as a JAR entry).
- `PluginJarCollector` — direct port of `PluginJarScanner`, `System.Logger` instead of `LogUtils`, otherwise
  unchanged logic (scan default dir + explicit files + explicit dirs, normalize/absolute/de-duplicate,
  `.jar`-suffix filtering, missing/non-directory inputs are a no-op not an error).

**Verify:** build small fixture JARs in a temp directory within the test (a `.jar` with a `hybrid: true` +
valid `mixins:` `plugin.yml` and a dummy mixin config entry; one with `hybrid: false` or absent; one with
`hybrid: true` but no `mixins:`; one with `hybrid: true`, `mixins:` naming a config that doesn't exist in the
jar; one with no `plugin.yml` at all) and assert `HybridPluginDescriptorReader.read` behaves correctly for
each. Separately test `PluginJarCollector` against a real temp directory tree with a mix of `.jar` and
non-`.jar` files, an explicit extra file, and an explicit extra directory, asserting the collected set is
exactly right (normalized, deduplicated, absolute).

**Commit:** "Add hybrid plugin descriptor parsing and JAR scanning to laminate-runtime".

---

## Task 3: `LaminateQuiltLauncher` + `LaminateMixinTransformer`

Read `D:\Programmieren\Minecraft\Laminate\paper-server\src\main\java\io\papermc\paper\mixin\PaperQuiltLauncher.java`
and `PaperMixinClassFileTransformer.java` in full first (already summarized in this plan's Context section,
but read the real source before writing anything).

Create `eu.lschreiber.laminate.runtime.LaminateQuiltLauncher` (flat, no `.quilt` subpackage - see the
Global Constraints correction above) — a faithful port of `PaperQuiltLauncher` (package-private in the fork;
keep it package-private here too, only `LaminateRuntimeBootstrap` needs it),
`extends org.quiltmc.loader.impl.launch.common.QuiltLauncherBase`, with these changes from the fork:
- Constructor: same shape (`this.classLoader = getClass().getClassLoader(); setProperties(new HashMap<>());`).
- **Add the pinned-game-loader field and accessor** per the Context section: a `static volatile ClassLoader
  pinnedGameLoader` (or equivalent — your call whether this lives on `LaminateQuiltLauncher` itself or a tiny
  shared holder class `LaminateMixinTransformer` also writes to; simplest is probably a package-private static
  field on `LaminateQuiltLauncher` with a `static void pinGameLoaderIfAbsent(ClassLoader loader)` method that
  only ever sets it once — first-write-wins, subsequent calls are no-ops. Must not require synchronization on
  the hot transform path beyond a cheap volatile read; the compare-and-set on first write can use
  `AtomicReference` for correctness without contending on every subsequent call).
- `findResource`/`findResourceUrl`/`getClassByteArray`: extend the search order to **pinned game loader (if
  set and distinct from `classLoader`) → `classLoader` → thread-context loader (if distinct from both) →
  registered plugin loaders**, in that order. (The fork's order was `classLoader` → context → plugins; here
  the pinned game loader — Paper's own server loader, once known — should be searched *before* `classLoader`,
  since it's specifically Paper's own classes we're most often trying to resolve for hierarchy walks, and
  `classLoader`/`bundlerLoader` itself will almost never directly contain a `net.minecraft.*` resource.)
- All other overrides (`getEnvironmentType`, `isDevelopment`, `getTargetNamespace() → "mojang"`,
  `isClassLoaded() → false` always, `getMappingConfiguration() → null`, the classpath-tracking methods, all
  the documented no-op setters) — copy the fork's behavior and rationale exactly, adapting only
  package/class names in comments.

Create `eu.lschreiber.laminate.runtime.LaminateMixinTransformer implements ClassFileTransformer` — port of
`PaperMixinClassFileTransformer`, with:
- The expanded exclusion list from Global Constraints.
- **Call `LaminateQuiltLauncher.pinGameLoaderIfAbsent(loader)`** at the top of `transform(...)` (after the
  null/exclusion checks, before delegating to the mixin transformer) whenever `loader` is non-null — this is
  what actually captures the pin; do it unconditionally on every eligible call (the method itself is a cheap
  no-op after the first successful pin, so no need to gate the call site).
- Same `transformClassBytes` delegation, same `Throwable`-catching, same "return `null` for unchanged"
  contract as the fork.

**Verify:** this is inherently hard to unit-test in isolation (it's a thin adapter over quilt-loader/mixin
internals that only makes sense wired into a real bootstrap) — a full live proof happens in Task 7. For this
task, verify what you reasonably can standalone: that the class compiles against the real quilt-loader/
sponge-mixin dependencies (i.e. the `QuiltLauncherBase` contract is satisfied — this is enforced at compile
time by `extends`/`@Override`), and write a focused unit test of just the pinned-game-loader logic in
isolation (construct a couple of distinct `ClassLoader` instances, confirm first-write-wins semantics
directly against your holder's public surface, without needing a real Quilt/Mixin environment). Document in
your report what could only be verified in Task 7's live test and why.

**Commit:** "Add LaminateQuiltLauncher and LaminateMixinTransformer".

---

## Task 4: `LaminateRuntimeBootstrap`

Read `D:\Programmieren\Minecraft\Laminate\paper-server\src\main\java\io\papermc\paper\mixin\HybridPluginMixinBootstrap.java`
in full first.

Create `eu.lschreiber.laminate.runtime.LaminateRuntimeBootstrap`, porting `HybridPluginMixinBootstrap`'s
orchestration logic with the signature and idempotency contract from Global Constraints:
- Scans via `PluginJarCollector` (Task 2), reads descriptors via `HybridPluginDescriptorReader` (Task 2).
- Per plugin JAR: `ensureMixinBootstrap()` (constructs `LaminateQuiltLauncher` exactly once, sets
  `System.setProperty("mixin.service", MixinServiceKnot.class.getName())`, calls `MixinBootstrap.init()` then
  `MixinExtrasBootstrap.init()`), wraps the plugin JAR in its own pinned `URLClassLoader` (parent = current
  thread's context classloader at bootstrap time — matches the fork), registers it with the launcher, swaps
  context classloader to the plugin loader while calling `Mixins.addConfiguration(...)` for each config, swaps
  back.
- After all JARs processed: advance `MixinEnvironment` phases INIT → DEFAULT via the same reflective
  `gotoPhase` call the fork uses (package-private method, no public API) — obtain the `IMixinTransformer` from
  `MixinServiceKnot` (reflective, same as the fork), `instrumentation.addTransformer(transformer, true)`,
  then the retransform-already-loaded-classes safety net (should find zero classes in practice, since this
  runs before paperclip loads anything — keep it anyway, matching the fork's defensive posture; adapt the
  `net.minecraft.` prefix scan to also (or instead — your call, document reasoning) scan for `io.papermc.`
  prefixed classes, since in this earlier-than-the-fork invocation point there's an argument neither prefix
  will ever match anything, but keep the safety net general rather than deleting it).
- Write the result into `LaminateBridge.recordResult(...)` (Task 1) before returning.
- Same failure isolation as the fork: a `Throwable` from processing one plugin JAR is caught, logged, that
  plugin's lower-cased name added to the failed set, bootstrap continues with the rest.

**Verify:** given the real quilt/mixin machinery this drives can only be meaningfully proven live (Task 7),
focus this task's own verification on: the idempotency contract (call `bootstrap(...)` twice with the same
arguments, assert the second call returns the cached result without re-scanning — you can prove "didn't
re-scan" via a fake/counting variant of the plugin directory, or by asserting on `LaminateBridge` state
staying identical across both calls), and the per-plugin failure isolation (a fixture directory with one
valid hybrid plugin jar and one deliberately broken one — e.g. missing mixin config — assert the valid one's
name ends up in the loaded set and the broken one's name ends up in the failed set, without a real Quilt/
Mixin environment necessarily needing to succeed for the *broken* plugin's failure path specifically; if
exercising the valid plugin's success path requires more than descriptor-reading to work without a live JVM
context, note that clearly and defer full proof to Task 7).

**Commit:** "Add LaminateRuntimeBootstrap orchestrating quilt/mixin initialization".

---

## Task 5: Wire into `LaminateMain` + extend `laminateServerJar`

Two closely related changes:

1. **Argument parsing** (new, small, in `laminate-launcher`): a function/class that extracts `--plugins <dir>`
   (single value, default `"plugins"` resolved against CWD), `--add-plugin <file>` (repeatable, collect all
   occurrences), `--add-plugin-dir <dir>` (repeatable) from `LaminateMain`'s raw `args`. Pure function of
   `String[] args`, easily unit-testable without touching the mixin runtime.
2. **`LaminateMain.main`**: after `Thread.currentThread().setContextClassLoader(bundlerLoader)` and before
   `Class.forName(bundlerMainClass, ...)`, parse the plugin-related args and call
   `LaminateRuntimeBootstrap.bootstrap(instrumentation, defaultPluginDir, addFiles, addDirs)`. Log the result
   (loaded/failed counts) via a `System.out.println("[Laminate] ...")` line consistent with the existing
   style. A bootstrap failure for the *whole* mechanism (not an individual plugin — those are already isolated
   internally) should not crash server startup either: catch broadly around this call, log, and continue to
   `Class.forName(bundlerMainClass, ...)` regardless, exactly mirroring the fork's
   `PaperBootstrap.bootstrapHybridMixins` try/catch-and-log-only wrapper.
3. **`LaminateServerJarTask`** (`buildSrc`): extend the merge to also flatten in, per Global Constraints:
   `laminate-runtime`'s compiled output + its full resolved runtime classpath (quilt-loader, sponge-mixin,
   mixinextras-common, asm-util + transitive asm artifacts, snakeyaml, `laminate-bridge`'s compiled output),
   and `laminate-api`'s compiled output. Wire these via each module's `runtimeClasspath` configuration output
   (or a dedicated resolvable configuration on `laminate-launcher` depending on
   `project(":laminate-runtime")`/`project(":laminate-api")`, whichever fits the existing task's structure
   better — read `LaminateServerJarTask.java` first and follow its established pattern rather than inventing
   a parallel one). Log a warning (not a failure) for any unexpected duplicate entry between these new
   sources and what's already merged (paperclip + launcher's own classes) beyond the intentional "ours wins"
   cases already handled.

**Verify:**
- `./gradlew :laminate-launcher:laminateServerJar` succeeds; unzip the result and confirm classes from
  `org.spongepowered.asm.`, `org.quiltmc.`, `com.llamalad7.mixinextras.`, `eu.lschreiber.laminate.runtime.`,
  `eu.lschreiber.laminate.bridge.`, and `eu.lschreiber.laminateapi.` are all present in the merged jar.
- Boot the result with **no plugins present** (empty `plugins/` dir) from a clean scratch dir — must still
  reach `Done (` and shut down cleanly on `stop`, exactly like Phase 0-2 (this proves the mixin bootstrap
  path doesn't break the server when there's nothing to bootstrap). Real live transcript required.
- Argument-parsing unit tests for `--plugins`/`--add-plugin`/`--add-plugin-dir` (default, single value,
  repeated flags, absent).

**Commit:** "Wire LaminateRuntimeBootstrap into LaminateMain and extend laminateServerJar bundling".

---

## Task 6: Migrate `test-plugin`

Copy `D:\Programmieren\Minecraft\Laminate\test-plugin\src\main\java\eu\lschreiber\testplugin\**` (and
`src/main/resources/{plugin.yml,mixins.testplugin.json}`) into a new `test-plugin` module in this repo,
unchanged in behavior (`TestPlugin`, `events/ServerLevelTickStartEvent`, `listener/TickListener`,
`mixins/{ServerMixin,ServerLevelMixin}`).

New `test-plugin/build.gradle.kts` (this module was never scaffolded in Phase 0/1 — add it to
`settings.gradle.kts` now): use the **official** `io.papermc.paperweight.userdev` plugin +
`paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")` from `repo.papermc.io` (per this repo's README, which
already documents this exact setup for plugin authors — read `README.md` first and match it), `compileOnly`/
`annotationProcessor` on `project(":laminate-api")`, `compileOnly` on `net.fabricmc:sponge-mixin:0.17.0+mixin.0.8.7`
and `io.github.llamalad7:mixinextras-common:0.5.3` (same coordinates as `laminate-runtime`'s — these must be
compile-compatible with what's bundled at runtime; do not introduce a version skew). Shadow-jar it (matching
the README's documented plugin-author gradle setup) so it produces a single plugin jar with `hybrid: true`.
Do **not** shade `sponge-mixin`/`mixinextras-common`/`laminate-api` into the plugin jar — the README already
says why (Laminate provides them at runtime), and now that's concretely true (they're merged into
`laminate-server.jar`).

**Verify:** `./gradlew :test-plugin:build` (or whatever the shadow task is named) produces a plugin jar; unzip
it and confirm `plugin.yml` has `hybrid: true` and `mixins.testplugin.json`, and that it does **not** contain
`org/spongepowered/asm/**` or `com/llamalad7/mixinextras/**` classes (would indicate accidental shading).

**Commit:** "Migrate test-plugin module from the Laminate fork".

---

## Task 7: End-to-end live verification (the payoff of this whole phase)

This is the single most important proof in the project so far: **does a real hybrid plugin's mixin actually
apply to a real running Paper server, launched entirely through the standalone pipeline?**

From a clean scratch directory: place the built `test-plugin` jar (Task 6) into `plugins/`, pre-create
`eula.txt` (`eula=true`), run `java -jar laminate-server-<version>.jar --nogui` with **zero manual flags**
(proving relaunch + agent + mixin bootstrap + vanilla boot all compose correctly in one real run). Run this
fully synchronously within a single blocking tool call (per this repo's established pattern from Phases 0-2 —
poll the log file in a loop, do not end your turn waiting for an async notification).

Confirm, with real log excerpts in your report:
1. `[Laminate] agent loaded, Instrumentation available` (Phase 2 behavior, still correct).
2. A log line showing the hybrid plugin's mixin config was injected (matching the fork's
   `"Injected {} mixin config(s) for hybrid plugin {}"` style message, or your adapted equivalent).
3. A log line showing the transformer/mixin pipeline was installed successfully.
4. The server reaches `Done (`.
5. **The actual mixin fires**: `TickListener`/`ServerLevelTickStartEvent` (or whatever observable marker
   `test-plugin` produces — check its actual source, migrated in Task 6, for what to grep for) appears in the
   log during normal server ticking — this is the proof that `ServerLevelMixin` genuinely intercepted a real
   Paper server-tick call, not just that a config was registered.
6. Clean `stop` and exit code 0.

If any of this fails, **this is exactly the kind of finding this task exists to surface** — do not paper over
it. Report BLOCKED with precise diagnostics (which step failed, what the log actually showed) rather than
guessing at a fix blindly; the controller will help diagnose.

Additionally, run the same boot **without** `test-plugin` present (empty `plugins/`) one more time as a final
regression check that nothing in this phase broke the plugin-less path from Task 5.

**Commit:** none required for this task alone unless you needed to fix something to make it pass (in which
case commit that fix with its own message, following the pattern of Phase 2's Task 2, which had to fix a bug
discovered by its own live test).

---

## Post-Task-7 addendum: two architectural bugs found by the live test, not yet fixed

Task 7's live test found three things, in order: (1) a real packaging bug (ASM version self-detection broken
by the flat-jar merge — **fixed and committed** as `81a170e`), (2) mixin config injection then succeeding but
the actual `@Mixin` transforms never applying, traced to Sponge Mixin's eager, one-shot target-class
resolution running before Minecraft classes are resolvable, and (3), found by the controller while
investigating a secondary symptom from (2)'s report (`PluginClassLoader` unable to resolve `laminate-api`
classes): **the "parent interposition" classloader trick from Phase 0 does not actually work** —
empirically verified (`MinecraftServer.class.getClassLoader()`'s parent chain goes straight to the platform
classloader, never passing through `bundlerLoader`) — so none of the merged runtime classes are visible to
Paper's own code or plugin code at all. Two more tasks are needed before Task 7's checklist can pass.

### Task 8: Fix classloader interposition — add the missing intermediate loader

**Root cause:** `LaminateMain.main` currently loads paperclip's `Main` class directly via `bundlerLoader`:
```java
Class<?> bundlerClass = Class.forName(bundlerMainClass, true, bundlerLoader);
```
Paperclip's own source constructs its server classloader as
`new URLClassLoader(urls, Paperclip.class.getClassLoader().getParent())` (verified against the real
`PaperMC/Paperclip` source during original planning). Since `bundlerLoader` directly defines paperclip's
`Main`/`Paperclip` classes here, `Paperclip.class.getClassLoader()` **is** `bundlerLoader` itself, so
`.getParent()` resolves to `bundlerLoader`'s own parent — `ClassLoader.getPlatformClassLoader()` — completely
skipping `bundlerLoader`. The intended trick only works if paperclip's classes are defined by a loader whose
own parent is `bundlerLoader`, not by `bundlerLoader` directly.

**Fix:** introduce one more `URLClassLoader` layer between `bundlerLoader` and paperclip:
```java
URLClassLoader bundlerLoader = new URLClassLoader(new URL[] { location }, ClassLoader.getPlatformClassLoader());
URLClassLoader paperclipLoader = new URLClassLoader(new URL[] { location }, bundlerLoader);
Thread.currentThread().setContextClassLoader(paperclipLoader);
...
Class<?> bundlerClass = Class.forName(bundlerMainClass, true, paperclipLoader);
```
Now `Paperclip.class.getClassLoader()` is `paperclipLoader`, and `.getParent()` is `bundlerLoader` —
paperclip's own server classloader construction then genuinely parents itself to `bundlerLoader`, and
everything merged into `bundlerLoader`'s classpath (quilt-loader, sponge-mixin, mixinextras, asm,
`laminate-api`, `laminate-runtime`, `laminate-bridge`) becomes visible to Paper's own classes and to plugin
classes via ordinary parent delegation, exactly as this project's design has always assumed.

Update the class-level javadoc on `LaminateMain` (currently describes the single-loader "parent interposition
gap" incorrectly, since it doesn't mention this second loader) to match the corrected design.

**Verify:** empirically re-run the same diagnostic the controller used to find this bug — from a clean scratch
dir, boot with `test-plugin` (or any plugin) present, and confirm `MinecraftServer.class.getClassLoader()`'s
parent chain now includes `bundlerLoader` (a quick throwaway diagnostic print is fine for this, as the
controller did — revert it before committing, or better, write it as a small structural unit test if you can
express the loader-construction logic in a way that's testable without a full server boot; use your
judgment). Then re-run the full live boot test with `test-plugin` present and confirm
`eu.lschreiber.laminateapi.accessor.LaminateAccessors` now resolves correctly when `/testcmd` runs (the
`NoClassDefFoundError` from Task 7's report should be gone) — though the mixin itself still won't fire yet,
since Task 9's fix is still needed for that; this task's success criterion is specifically the classloader
visibility, not the mixin timing. Also re-run the empty-plugins regression boot to confirm no regression to
Phases 0-2's behavior.

**Commit:** "Fix classloader interposition: load paperclip through an intermediate loader parented to bundlerLoader".

### Task 9: Defer Mixin config registration until target classes are resolvable (the "lazy gate")

**Root cause (from Task 7's report, traced to Sponge Mixin source):** `MixinInfo.getTargetClass()` calls
`ClassInfo.forName(target.name)` **eagerly**, synchronously, when a mixin config is registered
(`Mixins.addConfiguration`) and/or when the environment advances to the `DEFAULT` phase (`gotoPhase`). If
`ClassInfo.forName` returns `null` (target not resolvable *at that moment* — not necessarily "not loaded",
but "no class bytes findable via the launcher's `getClassByteArray`/resource lookup at all"), the mixin is
**silently and permanently** never bound to that target — there is no retry later, even once the transformer
installed via `Instrumentation.addTransformer` starts seeing real class definitions. This project's design
calls `LaminateRuntimeBootstrap.bootstrap()` (which does both of these things) strictly before paperclip has
downloaded/patched/staged a single Minecraft class anywhere — so target resolution can never succeed under
the current sequencing, for *any* hybrid plugin, ever.

**Why the fork didn't hit this:** the fork's server jar is built (via paperweight, at compile time) as a
single self-contained jar with Paper's own `net.minecraft.*` classes already physically present as `.class`
files in the exact same classpath as its mixin machinery — `getClassByteArray` succeeds immediately, from
process start, regardless of whether the JVM has "loaded" (defined) that class yet. In this project's
build-time-bundled-launcher architecture, Minecraft's actual game code is never part of `laminate-server.jar`
at all — paperclip downloads/patches it into a local cache and constructs `serverLoader` pointing at those
files **as part of its own execution**, which happens *after* `LaminateMain` calls
`Class.forName(bundlerMainClass, ...)`. There is a genuine window — before that call — where the target class
bytes are not resolvable by any mechanism, no matter how the launcher's lookup chain is structured.

**Fix — defer target resolution to the earliest safe moment, not "before paperclip starts" but "before any
Minecraft class is actually defined":**

Split `LaminateRuntimeBootstrap`'s current single `bootstrap(...)` method into two stages:

- **Stage A (called from `LaminateMain`, at the same point `bootstrap()` is called today — before
  `Class.forName(bundlerMainClass, ...)`):** everything that does *not* require target classes to be
  resolvable — scan plugins (`PluginJarCollector`), read descriptors (`HybridPluginDescriptorReader` — this
  only touches the plugin JAR's own `plugin.yml`/mixin-config-JSON, never Minecraft classes),
  `ensureMixinBootstrap()` (construct `LaminateQuiltLauncher`, set the `mixin.service` system property,
  `MixinBootstrap.init()` + `MixinExtrasBootstrap.init()` — framework initialization, not config
  registration, should not need target classes), wrap each plugin JAR in its own pinned `URLClassLoader` and
  register it with the launcher. Then install a transformer via `instrumentation.addTransformer(...)`
  immediately — but *not yet* backed by a real `IMixinTransformer`; its only job for now is to detect the
  right moment to trigger Stage B (see below).
- **Stage B (triggered lazily, exactly once, from inside the installed transformer's own `transform(...)`
  call, on the *first* class load whose defining `loader` is recognized as the real game loader):** reuse the
  **exact same detection this phase already built for `LaminateQuiltLauncher`'s pinned-game-loader mechanism**
  (Task 3) — the first `transform(...)` call whose `loader` parameter is non-null, distinct from
  `bundlerLoader`/`paperclipLoader`, and not a registered plugin loader is, by construction, the first class
  paperclip's own fully-constructed `serverLoader` is loading — meaning `serverLoader`'s classpath is already
  complete (paperclip built it with its full URL list before invoking anything through it), so target class
  bytes for *any* Minecraft/Paper class are now genuinely resolvable, even though this specific triggering
  class (and everything after it) has not been *defined* by the JVM yet, since `transform(...)` runs strictly
  before the JVM defines the class it was called for. From within this same `transform(...)` call, Stage B
  runs synchronously: for each plugin, swap context classloader to the plugin's loader, call
  `Mixins.addConfiguration(...)` for each of its configs (target resolution now succeeds), swap back;
  `finishMixinPhases()` (the existing reflective `gotoPhase` INIT→DEFAULT sequence); obtain the real
  `IMixinTransformer` from `MixinServiceKnot` (existing reflective retrieval); record the final loaded/failed
  sets into `LaminateBridge`. Then — critically — **delegate the *current* triggering class through the
  now-ready real transformer before returning from this same `transform(...)` call**, so the class that
  triggered Stage B is itself correctly mixin-transformed too, not skipped. Guard Stage B with a one-shot
  idempotent guard (matches this phase's existing `ensureMixinBootstrap`/`mixinPhasesFinished` pattern in
  `LaminateRuntimeBootstrap` — reuse that style, don't invent a new one) so it cannot run twice even under
  concurrent transform calls.

Keep the existing retransform-already-loaded-classes safety net (should still find zero classes in the common
case — Stage B completes before even the triggering class is defined, let alone anything loaded afterward —
but it's a harmless, cheap defense-in-depth to keep).

This is a genuine restructuring of `LaminateRuntimeBootstrap` and `LaminateMixinTransformer`'s relationship
(the transformer now drives the second half of bootstrap, rather than bootstrap installing a fully-ready
transformer) — read both classes' current implementations in full before starting, and preserve every other
already-reviewed behavior (per-plugin failure isolation, exact log message styles, `System.Logger` usage,
`LaminateBridge` write contract) exactly as-is; only the *sequencing* of config-registration/phase-advance
relative to paperclip's execution changes.

**Verify:** the full Task 7 checklist, for real, this time — from a clean scratch dir with `test-plugin` in
`plugins/`, zero manual flags: agent loaded, mixin config injection log (now happening lazily, from inside a
transform call — log it clearly so this is observable), transformer installed, `Done (`, and — the actual
proof — `"[Laminate] @InjectEvent fired — tick start in world=..."` appearing repeatedly during real ticking,
`testcmd` producing `"[test-plugin] write() injected via mixin: Hello World"`, clean `stop`/exit 0. Also
re-run the empty-plugins regression boot. This task is not done until criterion 5 from Task 7's original
checklist genuinely passes with real evidence — if it still doesn't, report BLOCKED with the same rigor Task
7's report demonstrated (trace to source, don't guess) rather than claiming success prematurely.

**Commit:** "Defer Mixin config registration to the first real game class load (lazy gate)".

### A third bug, found by the same live test, fixed alongside Task 9: classes on `bundlerLoader` cannot statically reference Bukkit/Paper types

Once Task 8 correctly made `bundlerLoader` a genuine *ancestor* of `serverLoader`, and Task 9 got mixins
actually firing, a new failure appeared: `ServerLevelMixin`'s injected companion code (generated by
`laminate-api`'s `@InjectEvent` processor, living in `LaminateEventBridge`) crashed with
`NoClassDefFoundError: org/bukkit/Bukkit`. Root cause: `LaminateEventBridge` is part of `laminate-api`,
merged into the launcher jar and therefore defined by `bundlerLoader` — the *parent* of `serverLoader`, which
is what actually has the Bukkit API on its classpath. Parent delegation only goes one direction: a parent
classloader can never see a class only its child defines, so **any compile-time reference to an
`org.bukkit.*` type anywhere in a `bundlerLoader`-resident class's bytecode fails to link**, regardless of
whether that code path ever actually runs. (The reference fork never hits this — there, the equivalent class
sits directly on the server's own single flat classpath, a peer of the Bukkit API classes it calls, not a
parent of them.)

Fixed by rewriting `LaminateEventBridge` to resolve `Bukkit`/`PluginManager`/`Event`/the logger purely
reflectively, against the *target* class's own defining loader (i.e. `serverLoader`, which does have
`org.bukkit.*`) rather than `LaminateEventBridge`'s own loader — the same technique `LaminateAccessors`
already used successfully. `fireAndReturn`'s return type had to change from `Event` to `Object` for the
identical reason (a declared `Event` return type is itself a static reference that would fail to link);
`InjectEventProcessor`'s generated "cancellable" companion code was updated to cast the result explicitly,
safe there because that generated code is merged into the mixin's *target* class, not `LaminateEventBridge`
itself.

**This is a generalizable constraint the next phase must carry forward, not a one-off fix:** any code living
in `laminate-runtime`/`laminate-api`/`laminate-bridge` (all merged into `bundlerLoader`'s classpath) that
needs to touch a Paper/Bukkit/NMS type must resolve it reflectively against the *target*'s own classloader —
never via a static field, parameter, or return type referencing that type directly. `laminate-core-mixins`
(a future phase, injecting more deeply into Paper internals for `/plugins` categorization, `/reload`
blocking, etc.) will have a much larger surface area for this exact mistake to recur; its own plan should
state this constraint up front rather than rediscover it via another live test.

---

## End state after this plan

`java -jar laminate-server-<version>.jar --nogui`, with a hybrid plugin dropped in `plugins/` and zero manual
JVM flags, transparently relaunches, boots an unmodified Paper server, and applies that plugin's Mixin
transformations to the live server — the headline feature this entire de-fork project exists to deliver,
running without a single line of forked Paper source anywhere in this repository.

Explicitly **not** in scope for this phase (future work): the "core mixins" that replicate the fork's
Paper-internal UX polish (failed-plugin culling from `/plugins`, the "Laminate Plugins" category, `/reload`
blocking) — those depend on `laminate-core-mixins` compiling against the paperweight dev bundle and are a
separate, lower-risk follow-up phase now that the hard architectural problem (this phase) is solved. Also out
of scope: CI, and the `laminate-api` accessor/event-bridge features being exercised by `test-plugin` beyond
whatever it already does (its mixins already use `@GenerateAccessor`/`@InjectEvent` per the fork's design, so
Task 7's live test incidentally proves those too — call this out explicitly in the Task 7 report if you
observe it working, but it is not a separate task to add new coverage for).
