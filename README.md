# laminate-standalone

Laminate is a standalone launcher/agent that wraps an unmodified official Paper server jar to enable
"hybrid plugins" (Bukkit plugins carrying SpongePowered Mixin transforms), without forking Paper's
sources. It supersedes the [`Laminate`](https://github.com/henceiusegentoo/Laminate) Paper fork by
downloading and bundling an official Paper build at build time instead of maintaining a patched
Paper checkout.

**Status:** the full pipeline works and is live-verified end to end. `laminateServerJar` bundles a
downloaded, unmodified Paper build with Laminate's launcher, agent, and mixin runtime into a single
jar; hybrid plugins (Bukkit plugins carrying SpongePowered Mixin transforms) load and fire correctly
via a two-stage bootstrap that installs the class-file transformer before any Minecraft class loads;
Laminate's own always-on core mixins reproduce the old fork's Paper-internal UX behaviors (failed
hybrid plugin culling, `/reload` blocking, a "Laminate Plugins" line in `/plugins`) without patching
Paper's source. See `docs/adoc/index.adoc` for the full internal-architecture writeup, and
`docs/adoc/13-hybrid-plugin-dev.adoc` for a step-by-step guide to writing a hybrid plugin.

Pinned to Paper `26.2` (Minecraft's newer `26.x` calendar-style versioning), which requires Java 25
at both compile and run time; the Gradle toolchain is set accordingly. CI/release automation is not
yet set up.
