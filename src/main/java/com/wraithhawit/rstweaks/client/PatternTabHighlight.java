package com.wraithhawit.rstweaks.client;

/**
 * Lets us light Refined Storage's own pattern type tabs, or put them out.
 *
 * <p>{@code PatternTypeButton} and its {@code setSelected} are both package-private, so neither can
 * be named from here — but a mixin can target the class by string and give it this interface, the
 * same way the processing renderer is reached.
 */
public interface PatternTabHighlight {
    void rstweaks$setHighlighted(boolean highlighted);
}
