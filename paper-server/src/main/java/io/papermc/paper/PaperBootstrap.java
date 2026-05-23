package io.papermc.paper;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import io.papermc.paper.mixin.LaminateMixinBootstrap;
import joptsimple.OptionSet;
import net.minecraft.SharedConstants;
import net.minecraft.server.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PaperBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("bootstrap");

    private PaperBootstrap() {
    }

    public static void boot(final OptionSet options) {
        // Hybrid mixin bootstrap MUST run before any Minecraft class is loaded.
        // SharedConstants.tryDetectVersion() triggers class loading of Minecraft
        // classes; if the mixin transformer isn't installed yet, target classes
        // (like ServerLevel) would be loaded without mixin modifications and
        // cannot be retransformed to add fields. This matches Quilt's Knot
        // architecture where the class loader/transformer is active before the
        // game starts loading.
        bootstrapHybridMixins(options);

        SharedConstants.tryDetectVersion();

        getStartupVersionMessages().forEach(LOGGER::info);

        Main.main(options);
    }

    /**
     * Discovers hybrid plugins from the plugins directory and sets up the
     * Quilt/Knot mixin environment. Must be called before ANY Minecraft
     * class is loaded so that the {@code ClassFileTransformer} can intercept
     * class definitions and apply mixin modifications (including adding fields).
     */
    @SuppressWarnings("unchecked")
    private static void bootstrapHybridMixins(final OptionSet options) {
        try {
            final Path pluginDir = ((File) options.valueOf("plugins")).toPath();
            final List<Path> addFiles = ((List<File>) options.valuesOf("add-plugin"))
                .stream().map(File::toPath).toList();
            final List<Path> addDirs = ((List<File>) options.valuesOf("add-plugin-dir"))
                .stream().map(File::toPath).toList();

            LaminateMixinBootstrap.bootstrap(pluginDir, addFiles, addDirs);
        } catch (Throwable t) {
            LOGGER.error("Early hybrid mixin bootstrap failed — mixin plugins will not work", t);
        }
    }

    private static List<String> getStartupVersionMessages() {
        final String javaSpecVersion = System.getProperty("java.specification.version");
        final String javaVmName = System.getProperty("java.vm.name");
        final String javaVmVersion = System.getProperty("java.vm.version");
        final String javaVendor = System.getProperty("java.vendor");
        final String javaVendorVersion = System.getProperty("java.vendor.version");
        final String osName = System.getProperty("os.name");
        final String osVersion = System.getProperty("os.version");
        final String osArch = System.getProperty("os.arch");

        final ServerBuildInfo bi = ServerBuildInfo.buildInfo();
        return List.of(
            String.format(
                "Running Java %s (%s %s; %s %s) on %s %s (%s)",
                javaSpecVersion,
                javaVmName,
                javaVmVersion,
                javaVendor,
                javaVendorVersion,
                osName,
                osVersion,
                osArch
            ),
            String.format(
                "Loading %s %s for Minecraft %s",
                bi.brandName(),
                bi.asString(ServerBuildInfo.StringRepresentation.VERSION_FULL),
                bi.minecraftVersionId()
            )
        );
    }
}
