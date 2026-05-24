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

## Writing a Hybrid Plugin

Hybrid plugins are standard Paper plugins with `hybrid: true` in `plugin.yml` and one or more
Mixin config files.

### Gradle setup

Use [paperweight userdev](https://docs.papermc.io/paper/dev/userdev/) for Mojang-mapped NMS
access, and add `laminate-api` for the typed accessor annotation processor:

```kotlin
plugins {
    java
    id("com.gradleup.shadow") version "9.4.1"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.fabricmc.net/")
}

dependencies {
    compileOnly("net.fabricmc:sponge-mixin:0.17.0+mixin.0.8.7")
    compileOnly(annotationProcessor("io.github.llamalad7:mixinextras-common:0.5.3"))

    // Accessor API — generates typed interfaces for @Unique mixin methods
    compileOnly("eu.lschreiber.laminate:laminate-api:1.21.11-R0.1-SNAPSHOT")
    annotationProcessor("eu.lschreiber.laminate:laminate-api:1.21.11-R0.1-SNAPSHOT")

    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
}

tasks.build { dependsOn(tasks.shadowJar) }
```

> **Do not** shade `sponge-mixin`, `mixinextras-common`, or `laminate-api` — Laminate provides them at runtime.

### `plugin.yml`

```yaml
name: MyPlugin
main: com.example.MyPlugin
version: 1.0.0
api-version: "1.21"

hybrid: true               # required: marks this as a hybrid plugin
mixins:
  - mixins.myplugin.json   # Mixin config file(s) packaged inside the JAR
```

### Mixin class

Laminate uses **Mojang-mapped (mojmap)** names. Prefix injected fields and methods with
`yourpluginid$`:

```java
import java.util.function.BooleanSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.level.ServerLevel")
public abstract class MyServerLevelMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void myplugin$onTick(BooleanSupplier hasTime, CallbackInfo ci) {
        // ...
    }
}
```

## Typed Accessors (`laminate-api`)

When your plugin code needs to call a mixin-injected method on a live server object, annotate it
with `@GenerateAccessor` to generate a typed interface at compile time.

### Declare the accessor in the mixin

```java
@Mixin(MinecraftServer.class)
public class ServerMixin {

    @GenerateAccessor(name = "write")   // generates MinecraftServerAccessor.write(...)
    @Unique
    public void myplugin$write(final String msg) {
        System.out.println("Mixin says: " + msg);
    }
}
```

`@GenerateAccessor` requires `@Unique`. The annotation processor generates a
`{TargetSimpleName}Accessor` interface in a sibling `.accessor` package with a matching method
signature and a static `of(Object)` factory.

### Use the accessor

```java
MinecraftServer server = ((CraftServer) getServer()).getServer();
MinecraftServerAccessor accessor = MinecraftServerAccessor.of(server);
accessor.write("Hello World");
```

## Further Reading

For detailed documentation see the [Laminate Wiki](https://github.com/henceiusegentoo/Laminate/wiki):

- [Writing a Hybrid Plugin](https://github.com/henceiusegentoo/Laminate/wiki/Writing-a-Hybrid-Plugin): full plugin development guide
- [Architecture](https://github.com/henceiusegentoo/Laminate/wiki/Architecture): bootstrap sequence, mixin pipeline, and module structure
- [Building from Source](https://github.com/henceiusegentoo/Laminate/wiki/Building-from-Source): build steps, Gradle tasks, and the test-plugin module
- [Troubleshooting](https://github.com/henceiusegentoo/Laminate/wiki/Troubleshooting): common errors and fixes
