package com.wraithhawit.rstweaks;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;

/**
 * Raises the log level of Refined Storage's autocrafting task engine so it stops
 * writing a line per crafting step to {@code debug.log}.
 *
 * <p>{@code InternalTaskPattern}, {@code TaskImpl} and {@code AbstractTaskPattern} log at
 * DEBUG once per pattern per iteration — "Stepping", "Stepped", "Inserting", "Extracted",
 * each one formatting a full {@code ItemResource} including its {@code DataComponentPatch}.
 * NeoForge writes {@code debug.log} at DEBUG by default, so on any pack this is on, and it
 * is done synchronously on the server thread.
 *
 * <p>Measured on a creative test world running compression crafts: <b>412,256 lines,
 * 209 MB, in 29 seconds</b> — 7 MB/s of blocking disk writes from the tick loop. Message
 * building and {@code RandomAccessFile.writeBytes0} together were <b>71.6% of the entire
 * server thread</b>, more than every mod's actual work combined.
 *
 * <p>Raising the level is what fixes it rather than filtering: log4j checks the level in
 * {@code logIfEnabled} <em>before</em> it builds the message object, so nothing is
 * allocated, nothing is formatted and nothing is written. A filter would run too late and
 * still pay for the message.
 *
 * <p>Only DEBUG is affected. Warnings and errors from the same classes still appear.
 */
final class AutocraftingLogSpam {
    /**
     * The package, not the three individual classes: log4j levels are inherited down the
     * dotted name, so this covers any task class added by a later Refined Storage version
     * without us having to notice.
     */
    private static final String LOGGER = "com.refinedmods.refinedstorage.api.autocrafting.task";

    /**
     * Effective level before we touched it, so the toggle can put it back. Captured once —
     * re-capturing after we have already raised it would record our own value as the
     * original.
     */
    private static Level original;
    private static boolean silenced;

    private AutocraftingLogSpam() {
    }

    static void apply(boolean silence) {
        try {
            if (silence == silenced) {
                return;
            }
            if (original == null) {
                LoggerContext context = (LoggerContext) LogManager.getContext(false);
                // getLoggerConfig falls back to the nearest ancestor, so this is the level
                // actually in force for the package whether or not it is configured itself.
                original = context.getConfiguration().getLoggerConfig(LOGGER).getLevel();
            }
            Configurator.setLevel(LOGGER, silence ? Level.INFO : original);
            silenced = silence;
            if (silence) {
                RSTweaks.LOGGER.info("[rstweaks] silenced DEBUG logging from {} (was {}) - it was "
                    + "writing megabytes per second to debug.log from the server thread. "
                    + "Set silenceAutocraftingDebugLog=false to restore it.", LOGGER, original);
            } else {
                RSTweaks.LOGGER.info("[rstweaks] restored {} logging to {}", LOGGER, original);
            }
        } catch (RuntimeException | LinkageError e) {
            // Reaching into the logging backend is the one thing here that could plausibly
            // break on a pack that ships its own log4j setup. Losing the fix is survivable;
            // failing to load is not.
            RSTweaks.LOGGER.warn("[rstweaks] could not adjust Refined Storage log levels", e);
        }
    }
}
