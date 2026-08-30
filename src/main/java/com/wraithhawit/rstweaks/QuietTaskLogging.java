package com.wraithhawit.rstweaks;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

/**
 * Silences Refined Storage's per-iteration debug logging on the crafting hot path.
 *
 * <p>{@code InternalTaskPattern.step} and {@code AbstractTaskPattern.extractAll} carry six
 * {@code LOGGER.debug} calls between them — "Stepping {}", "Extracted {}x {} from internal
 * storage", "Inserting {}x {} into root storage" and so on — and they run once per crafting
 * iteration. A multiblock crafter drives roughly 10<sup>5</sup> of those a tick.
 *
 * <p>In a pack with debug logging enabled, which All the Mods 10 is, those lines are not merely
 * evaluated and discarded: they are formatted and written. Profile {@code evmko3bHZl} put
 * {@code CompositeFilter.filter} alone at <b>4.85% of the server thread</b>, before any of the
 * formatting or the I/O behind it.
 *
 * <p>Raising these two loggers to INFO removes it. Nothing above debug is touched, so a genuine
 * warning or error from Refined Storage still reaches the log exactly as before — the only thing
 * lost is a per-iteration trace that no one can read at a hundred thousand lines a tick anyway.
 *
 * <p><b>Off by nothing but the config.</b> This reaches into logging that is arguably the pack's
 * business rather than ours, so it says what it did once at startup and can be switched off.
 */
public final class QuietTaskLogging {
    private static final String[] HOT_LOGGERS = {
        "com.refinedmods.refinedstorage.api.autocrafting.task.InternalTaskPattern",
        "com.refinedmods.refinedstorage.api.autocrafting.task.AbstractTaskPattern",
    };

    private QuietTaskLogging() {
    }

    public static void applyIfEnabled() {
        if (!Config.quietTaskLogging) {
            return;
        }
        try {
            for (final String name : HOT_LOGGERS) {
                Configurator.setLevel(name, Level.INFO);
            }
            RSTweaks.LOGGER.info("[rstweaks] Refined Storage's per-iteration crafting debug logging"
                + " is raised to INFO; set quietTaskLogging=false to keep it.");
        } catch (final RuntimeException | LinkageError e) {
            // Logging configuration is not something to fail a game launch over, and every other
            // optimization here is independent of it.
            RSTweaks.LOGGER.warn("[rstweaks] could not quiet Refined Storage's crafting debug"
                + " logging; leaving it as the pack configured it.", e);
        }
    }
}
