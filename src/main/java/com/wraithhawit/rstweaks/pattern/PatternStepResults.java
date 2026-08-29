package com.wraithhawit.rstweaks.pattern;

/**
 * Refined Storage's {@code PatternStepResult} constants, held as {@link Object}.
 *
 * <p>The enum is package-private in {@code ...autocrafting.task}, so nothing outside that package
 * can name the type — and putting our class in that package is not an option: mods are named
 * modules on NeoForge, and a class in another mod's package fails module resolution at load. A
 * mixin can still <em>return</em> one, because the callback's generic type is erased; it just
 * cannot write the type down.
 *
 * <p>Resolved once here rather than per step. The batched stepping path runs on the hottest loop in
 * the mod, and a {@code Class.forName} per iteration would cost more than the batching saves.
 */
public final class PatternStepResults {
    private static final String CLASS_NAME =
        "com.refinedmods.refinedstorage.api.autocrafting.task.PatternStepResult";

    public static final Object COMPLETED = lookup("COMPLETED");
    public static final Object RUNNING = lookup("RUNNING");

    private PatternStepResults() {
    }

    /** Whether the constants resolved. False means Refined Storage moved them; batching stands down. */
    public static boolean available() {
        return COMPLETED != null && RUNNING != null;
    }

    private static Object lookup(final String name) {
        try {
            for (final Object constant
                : Class.forName(CLASS_NAME, false, PatternStepResults.class.getClassLoader())
                    .getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(name)) {
                    return constant;
                }
            }
            return null;
        } catch (final ClassNotFoundException | RuntimeException e) {
            // Returning null rather than throwing: this class is initialised from a mixin on a
            // task step, and an exception there is treated by TaskContainer as the task having
            // completed -- it drops the internal storage and tells the player it worked.
            return null;
        }
    }
}
