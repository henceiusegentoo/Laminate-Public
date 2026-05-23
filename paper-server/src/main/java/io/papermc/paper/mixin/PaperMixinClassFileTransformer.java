package io.papermc.paper.mixin;

import com.mojang.logging.LogUtils;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/**
 * A {@link ClassFileTransformer} that delegates to the SpongePowered Mixin
 * {@link IMixinTransformer} so that mixin modifications are applied to every
 * class as it is first loaded by the JVM.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Created and registered with the JVM's {@link java.lang.instrument.Instrumentation}
 *       by {@link HybridPluginMixinBootstrap} after the Mixin subsystem is
 *       fully initialized.</li>
 *   <li>For every class the JVM loads, the JVM calls
 *       {@link #transform(ClassLoader, String, Class, ProtectionDomain, byte[])}.</li>
 *   <li>If the Mixin transformer has modifications for that class, the
 *       transformed bytes are returned; otherwise {@code null} signals
 *       "no change".</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * <p>Instances are safe for concurrent use. The underlying
 * {@link IMixinTransformer} is thread-safe by contract of the Mixin library.</p>
 *
 * @see PaperMixinAgent
 * @see HybridPluginMixinBootstrap
 */
final class PaperMixinClassFileTransformer implements ClassFileTransformer {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private final IMixinTransformer mixinTransformer;

    /**
     * Creates a new transformer backed by the given Mixin transformer.
     *
     * @param mixinTransformer the fully-initialized Mixin transformer obtained
     *                         from {@code MixinServiceKnot.getTransformer()}
     */
    PaperMixinClassFileTransformer(final IMixinTransformer mixinTransformer) {
        this.mixinTransformer = mixinTransformer;
    }

    /**
     * Called by the JVM for every class being loaded or retransformed.
     *
     * <p>Delegates to {@link IMixinTransformer#transformClassBytes(String, String, byte[])}
     * which returns the original bytes if no mixin targets the class, or new
     * bytes incorporating mixin modifications otherwise.</p>
     *
     * @return transformed bytes if the class was modified, or {@code null} if unchanged
     */
    @Override
    public byte[] transform(final ClassLoader loader,
                            final String className,
                            final Class<?> classBeingRedefined,
                            final ProtectionDomain protectionDomain,
                            final byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }

        final String dotName = className.replace('/', '.');

        if (!canTransform(dotName)) {
            return null;
        }

        try {
            final byte[] transformed = mixinTransformer.transformClassBytes(
                    dotName, dotName, classfileBuffer);
            if (transformed != classfileBuffer && transformed != null) {
                LOGGER.debug("Mixin-transformed class: {}", dotName);
                return transformed;
            }
        } catch (final Throwable t) {
            LOGGER.error("Mixin transformation of {} failed — class will load untransformed",
                    dotName, t);
        }

        return null;
    }

    /**
     * Returns {@code false} for classes that must never go through the Mixin
     * transformer (mixin internals, ASM, logging, and Laminate infrastructure).
     */
    private static boolean canTransform(final String dotName) {
        if (dotName.startsWith("org.apache.logging.log4j.")) return false;
        if (dotName.startsWith("org.spongepowered.asm."))    return false;
        if (dotName.startsWith("org.objectweb.asm."))        return false;
        if (dotName.startsWith("io.papermc.paper.mixin."))   return false;
        return true;
    }
}
