package eu.lschreiber.laminate.runtime;

import eu.lschreiber.laminate.bridge.LaminateLog;
import eu.lschreiber.laminate.bridge.LaminateLog.Level;
import eu.lschreiber.laminate.bridge.LaminateLogMessages;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/** Bridges JVM class loading to Stage B and the live Mixin transformer. */
final class LaminateMixinTransformer implements ClassFileTransformer {

    private static final LaminateLog LOGGER = LaminateLog.getLogger("laminate.runtime.LaminateMixinTransformer");

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

        final IMixinTransformer mixinTransformer = LaminateRuntimeBootstrap.transformerForGameLoad(loader);
        if (mixinTransformer == null) {
            return null;
        }

        try {
            final byte[] transformed = mixinTransformer.transformClassBytes(dotName, dotName, classfileBuffer);
            if (transformed != classfileBuffer && transformed != null) {
                LOGGER.log(Level.DEBUG, String.format(LaminateLogMessages.MIXIN_TRANSFORMED_CLASS, dotName));
                return transformed;
            }
        } catch (final Throwable t) {
            LOGGER.log(Level.ERROR, String.format(LaminateLogMessages.MIXIN_TRANSFORM_FAILED, dotName), t);
        }

        return null;
    }

    private static boolean canTransform(final String dotName) {
        if (dotName.startsWith("org.apache.logging.log4j."))         return false;
        if (dotName.startsWith("org.spongepowered.asm."))            return false;
        if (dotName.startsWith("org.objectweb.asm."))                return false;
        if (dotName.startsWith("eu.lschreiber.laminate.runtime."))   return false;
        if (dotName.startsWith("org.quiltmc."))                      return false;
        if (dotName.startsWith("com.llamalad7.mixinextras."))        return false;
        if (dotName.startsWith("eu.lschreiber.laminate.bridge."))    return false;
        if (dotName.startsWith("eu.lschreiber.laminate.launcher."))  return false;
        if (dotName.startsWith("eu.lschreiber.laminate.agent."))     return false;
        if (dotName.startsWith("eu.lschreiber.laminateapi."))        return false;
        return true;
    }
}
