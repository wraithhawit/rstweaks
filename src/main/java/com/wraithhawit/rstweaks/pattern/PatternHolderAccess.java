package com.wraithhawit.rstweaks.pattern;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;

/**
 * Our view of Refined Storage's private {@code PatternRepositoryImpl$PatternHolder} record.
 *
 * <p>The record is private and lives in RS's module, so {@code setAccessible} is refused --
 * see the split-package note in {@code rstweaks-no-split-packages-on-neoforge}. The documented
 * seam for a package-private or private RS class is a mixin that implements an interface of
 * ours and exposes what we need, exactly as {@code CraftingGridResultSlotTestMixin} does.
 *
 * <p><b>It lives outside {@code com.wraithhawit.rstweaks.mixin} deliberately.</b> Mixin owns
 * that package and refuses any direct reference to a class inside it from ordinary code --
 * "is in a defined mixin package ... and cannot be referenced directly", thrown at class-load
 * time, which compiles and unit-tests perfectly and only fails in a running game. The existing
 * {@code CraftingGridResultSlotAccess} sits outside for the same reason.
 *
 * <p>Prefixed method names because this interface is mixed into an RS record, and a plain
 * {@code pattern()} would collide with the record's own accessor.
 */
public interface PatternHolderAccess {
    Pattern rstweaks$pattern();

    int rstweaks$priority();
}
