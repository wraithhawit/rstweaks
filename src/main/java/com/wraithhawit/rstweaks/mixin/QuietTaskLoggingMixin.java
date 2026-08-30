package com.wraithhawit.rstweaks.mixin;

import com.wraithhawit.rstweaks.Config;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops Refined Storage's per-iteration crafting debug calls from being made at all.
 *
 * <h2>Why raising the logger's level did not work</h2>
 *
 * <p>0.11.0 tried the obvious thing and set these two classes' loggers to INFO. It ran, and the
 * cost did not move. The profile says why:
 *
 * <pre>
 *   6.50%  Log4jLogger.debug
 *   6.42%    AbstractLogger.logIfEnabled
 *   6.42%      Logger$PrivateConfig.filter
 *   6.38%        CompositeFilter.filter
 *   0.01%  LoggerConfig.log            &lt;- nothing is actually written
 * </pre>
 *
 * <p>All of it is in {@code isEnabled}, and {@code LoggerConfig.log} at 0.01% shows nothing reaches
 * an appender. Log4j consults the <em>configuration-wide</em> filter before the level check, so a
 * pack with a filter chain — this one has one — pays for it on every call whatever the logger's
 * level is. The only way past it is for the call not to happen.
 *
 * <h2>What is silenced</h2>
 *
 * <p>Six calls, all of them per crafting iteration and all of them a trace nobody can read at
 * 10<sup>5</sup> lines a tick: "Stepping {}", "Stepped {} with {} iterations remaining",
 * "Extracted {}x {} from internal storage", and the three "Inserting {}x {}" variants.
 *
 * <p>Nothing above debug is touched, here or anywhere: a warning or an error from Refined Storage
 * arrives exactly as it always did. {@code require = 0} throughout, so if Refined Storage moves or
 * removes one of these the optimization quietly does less rather than failing a launch.
 */
@Mixin(targets = {
    "com.refinedmods.refinedstorage.api.autocrafting.task.InternalTaskPattern",
    "com.refinedmods.refinedstorage.api.autocrafting.task.AbstractTaskPattern",
})
public abstract class QuietTaskLoggingMixin {
    @Redirect(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Lorg/slf4j/Logger;debug(Ljava/lang/String;Ljava/lang/Object;)V"
        ),
        require = 0
    )
    private void rstweaks$skipOneArgDebug(final Logger logger, final String message,
                                          final Object argument) {
        if (!Config.quietTaskLogging) {
            logger.debug(message, argument);
        }
    }

    @Redirect(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Lorg/slf4j/Logger;debug(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"
        ),
        require = 0
    )
    private void rstweaks$skipTwoArgDebug(final Logger logger, final String message,
                                          final Object first, final Object second) {
        if (!Config.quietTaskLogging) {
            logger.debug(message, first, second);
        }
    }
}
