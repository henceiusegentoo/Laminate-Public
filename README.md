# Laminate

Laminate is a [Paper](https://papermc.io/) fork for Minecraft 1.21.11 that enables **hybrid
plugins**: standard Bukkit/Paper plugins that can apply
[SpongePowered Mixin](https://github.com/SpongePowered/Mixin) bytecode transformations to the
server at runtime.

## What makes Laminate special

Laminate lets you write plugins that use mixins for deep, low-level server modifications without
needing a separate mod loader. You get full access to NMS internals (injecting into private
methods, adding fields to server classes, intercepting return values) while keeping the familiar
Paper plugin lifecycle your plugins already use.

## Running the Server

```bash
java -jar laminate-server-1.21.11-R0.1-SNAPSHOT.jar nogui
```

No extra flags or configuration needed. All standard Paper/CraftBukkit arguments are supported.

## Further Reading

For detailed documentation see the [Laminate Wiki](https://github.com/henceiusegentoo/Laminate-Public/wiki):

- [Writing a Hybrid Plugin](https://github.com/henceiusegentoo/Laminate-Public/wiki/Writing-a-Hybrid-Plugin): full plugin development guide
- [Architecture](https://github.com/henceiusegentoo/Laminate-Public/wiki/Architecture): bootstrap sequence, mixin pipeline, and module structure
- [Building from Source](https://github.com/henceiusegentoo/Laminate-Public/wiki/Building-from-Source): build steps, Gradle tasks, and the test-plugin module
- [Troubleshooting](https://github.com/henceiusegentoo/Laminate-Public/wiki/Troubleshooting): common errors and fixes

> Comprehensive technical docs [here](/docs/adoc/index.adoc)
