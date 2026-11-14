package eu.lschreiber.laminate.runtime;

/** Thrown when a hybrid plugin descriptor is malformed. */
final class HybridPluginDescriptorException extends RuntimeException {

    HybridPluginDescriptorException(final String message) {
        super(message);
    }
}
