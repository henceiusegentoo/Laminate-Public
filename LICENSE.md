Laminate's own code — the launcher, agent, mixin runtime, cross-classloader bridge, core mixins,
`laminate-api`, build logic, and everything else in this repository — is original work. Unlike the
[`Laminate`](https://github.com/henceiusegentoo/Laminate) Paper fork this project supersedes, it
contains no copy of Paper's, Spigot's, or Bukkit/CraftBukkit's source: it downloads and bundles an
official, unmodified Paper *binary* at build time (see `docs/adoc/11-build-and-bundling.adoc`)
rather than patching a checkout of Paper's source tree, so it does not inherit their licensing the
way the old fork did.

Laminate's own code is licensed under the [PolyForm Noncommercial License
1.0.0](licenses/POLYFORM-NONCOMMERCIAL-1.0.0.md): free to use, copy, modify, and redistribute for
any noncommercial purpose, with attribution required on every copy per that license's own
`Required Notice` mechanism.

> Required Notice: Copyright henceiusegentoo (https://github.com/henceiusegentoo/laminate-standalone)

## A note on the distributed `laminate-server-<version>.jar`

The jar this project's build produces for distribution bundles Laminate's own PolyForm-licensed
code together with PaperMC's official, unmodified server build in a single merged jar (see
`docs/adoc/11-build-and-bundling.adoc`). That bundled Paper build is PaperMC's own compiled work,
licensed under the GNU General Public License v3, inherited from Spigot and the original
Bukkit/CraftBukkit projects — Paper's own licensing, not this project's, and unaffected by
anything in this file. This project does not modify, relicense, or claim any rights over that
bundled code; it is merged into the distributed jar unchanged.
