package com.wraithhawit.rstweaks.storage;

/**
 * Marks Refined Storage's processing-input resource container.
 *
 * <p>The class is package-private, so it cannot be named from here — but a mixin can target it by
 * string and give it this interface. That turns "which of these slots are processing inputs?" into
 * an {@code instanceof} against a type we own, with no reflection and no split package.
 */
public interface ProcessingInputContainer {
}