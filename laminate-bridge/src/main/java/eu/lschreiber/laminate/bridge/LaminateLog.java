package eu.lschreiber.laminate.bridge;

/**
 * Minimal logger used by runtime code that has to write to {@link System#out} instead of Paper's
 * logging stack.
 */
public final class LaminateLog {

    /** Mirrors the handful of levels this project uses. */
    public enum Level {
        /** Debug-level diagnostic output. */
        DEBUG,
        /** Informational output. */
        INFO,
        /** Warning conditions. */
        WARNING,
        /** Error conditions. */
        ERROR
    }

    private LaminateLog() {
    }

    /** Returns the shared logger instance. */
    public static LaminateLog getLogger(final String name) {
        return INSTANCE;
    }

    private static final LaminateLog INSTANCE = new LaminateLog();

    /** Returns whether the given level is emitted. */
    public boolean isLoggable(final Level level) {
        return level != Level.DEBUG;
    }

    /** Logs a message without a throwable. */
    public void log(final Level level, final String message) {
        log(level, message, null);
    }

    /** Logs a message and optional throwable. */
    public void log(final Level level, final String message, final Throwable throwable) {
        if (!isLoggable(level)) {
            return;
        }
        System.out.println(level == Level.INFO ? message : "[" + level + "] " + message);
        if (throwable != null) {
            throwable.printStackTrace(System.out);
        }
    }
}
