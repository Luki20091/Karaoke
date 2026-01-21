package me.Luki.karaoke.util;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DebugLogger {

    private final Logger logger;
    private volatile boolean enabled;
    private volatile boolean httpEnabled;

    public DebugLogger(Logger logger, boolean enabled, boolean httpEnabled) {
        this.logger = logger;
        this.enabled = enabled;
        this.httpEnabled = httpEnabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setHttpEnabled(boolean httpEnabled) {
        this.httpEnabled = httpEnabled;
    }

    public void debug(String message) {
        if (!enabled) {
            return;
        }
        logger.info("[DEBUG] " + message);
    }

    public void debug(Supplier<String> messageSupplier) {
        if (!enabled) {
            return;
        }
        logger.info("[DEBUG] " + messageSupplier.get());
    }

    public void http(String message) {
        if (!enabled || !httpEnabled) {
            return;
        }
        logger.info("[HTTP] " + message);
    }

    public void warn(String message) {
        logger.warning(message);
    }

    public void warn(String message, Throwable t) {
        logger.log(Level.WARNING, message, t);
    }

    public void error(String message) {
        logger.severe(message);
    }

    public void error(String message, Throwable t) {
        logger.log(Level.SEVERE, message, t);
    }
}
