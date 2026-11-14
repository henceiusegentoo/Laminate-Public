package eu.lschreiber.laminate.launcher;

import eu.lschreiber.laminate.agent.LaminateAgent;
import eu.lschreiber.laminate.bridge.LaminateLog;
import eu.lschreiber.laminate.bridge.LaminateLog.Level;
import eu.lschreiber.laminate.bridge.LaminateLogMessages;
import eu.lschreiber.laminate.runtime.LaminateRuntimeBootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.util.jar.Manifest;

/** Launches the merged server jar and keeps paperclip's loader hierarchy intact. */
public final class LaminateMain {

    private static final LaminateLog LOGGER = LaminateLog.getLogger("laminate.launcher.LaminateMain");

    private static final String BUNDLER_MAIN_CLASS_ATTRIBUTE = "Bundler-Main-Class";

    private static final String AGENT_LOADED_PROPERTY = "laminate.agent.loaded";

    private LaminateMain() {
    }

    /** Relaunches if needed, then hands off to paperclip's main class. */
    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getProperty(AGENT_LOADED_PROPERTY))) {
            RelaunchHandler.relaunch(args);
            return;
        }

        final Instrumentation instrumentation = LaminateAgent.getInstrumentation();
        LOGGER.log(Level.INFO, LaminateLogMessages.AGENT_LOADED);

        final String bundlerMainClass = readBundlerMainClass();
        final URL location = resolveJarLocation();
        final URLClassLoader bundlerLoader = createBundlerLoader(location);
        final URLClassLoader paperclipLoader = createPaperclipLoader(location, bundlerLoader);

        setPaperclipContextClassLoader(paperclipLoader);

        bootstrapHybridMixins(instrumentation, args);

        invokeBundlerMain(bundlerMainClass, paperclipLoader, args);
    }

    private static URL resolveJarLocation() {
        final CodeSource codeSource = LaminateMain.class.getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            throw new IllegalStateException(
                    "LaminateMain has no CodeSource; cannot determine this jar's own location.");
        }
        return codeSource.getLocation();
    }

    private static URLClassLoader createBundlerLoader(URL location) {
        return new URLClassLoader(new URL[] { location }, ClassLoader.getPlatformClassLoader());
    }

    private static URLClassLoader createPaperclipLoader(URL location, URLClassLoader bundlerLoader) {
        return new SelfFirstClassLoader(new URL[] { location }, bundlerLoader);
    }

    private static void invokeBundlerMain(String bundlerMainClass,
                                          ClassLoader paperclipLoader,
                                          String[] args) throws Exception {
        final Class<?> bundlerClass = Class.forName(bundlerMainClass, true, paperclipLoader);
        final Method mainMethod = bundlerClass.getMethod("main", String[].class);
        try {
            mainMethod.invoke(null, (Object) args);
        } catch (final InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Bundler main class " + bundlerMainClass + " failed", cause);
        }
    }

    private static void setPaperclipContextClassLoader(URLClassLoader paperclipLoader) {
        Thread.currentThread().setContextClassLoader(paperclipLoader);
    }

    private static void bootstrapHybridMixins(Instrumentation instrumentation, String[] args) {
        try {
            PluginArguments pluginArguments = PluginArguments.parse(args);
            LaminateRuntimeBootstrap.bootstrap(
                    instrumentation,
                    pluginArguments.pluginDir(),
                    pluginArguments.addPluginFiles(),
                    pluginArguments.addPluginDirs());
        } catch (Throwable t) {
            LOGGER.log(Level.ERROR, LaminateLogMessages.HYBRID_BOOTSTRAP_FAILED, t);
        }
    }

    private static final class SelfFirstClassLoader extends URLClassLoader {

        SelfFirstClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("java.") || name.startsWith("javax.")) {
                return super.loadClass(name, resolve);
            }
            Class<?> loadedClass = findLoadedClass(name);
            if (loadedClass == null) {
                try {
                    loadedClass = findClass(name);
                } catch (ClassNotFoundException notFoundLocally) {
                    loadedClass = super.loadClass(name, false);
                }
            }
            if (resolve) {
                resolveClass(loadedClass);
            }
            return loadedClass;
        }
    }

    private static String readBundlerMainClass() throws IOException {
        try (InputStream manifestStream = LaminateMain.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (manifestStream == null) {
                throw new IllegalStateException(
                        "No META-INF/MANIFEST.MF found on the classpath; cannot determine which class to launch.");
            }
            Manifest manifest = new Manifest(manifestStream);
            String bundlerMainClass = manifest.getMainAttributes().getValue(BUNDLER_MAIN_CLASS_ATTRIBUTE);
            if (bundlerMainClass == null || bundlerMainClass.isBlank()) {
                throw new IllegalStateException(
                        "This jar's MANIFEST.MF has no " + BUNDLER_MAIN_CLASS_ATTRIBUTE
                                + " attribute; cannot determine which class to launch.");
            }
            return bundlerMainClass;
        }
    }
}
