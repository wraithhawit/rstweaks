package com.wraithhawit.rstweaks;

import java.lang.reflect.Field;

import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.ItemCapability;

/**
 * Recognises a stack that holds a bulk resource rather than being one.
 *
 * <p>A grid click means something different when you are holding a tank: the stack on the
 * cursor is a <em>destination</em>, not cargo. {@code AbstractGridScreenMixin} needs that
 * distinction before it changes what a click does, so that an ordinary item on the cursor
 * keeps Refined Storage's stock behaviour untouched.
 *
 * <p>Fluids are a straight capability lookup. Chemicals are reached without a Mekanism
 * dependency, which matters because this mod must build and run in packs that have neither
 * Mekanism nor the integration installed - see the note on {@link #chemicalCapability()}.
 */
public final class GridContainers {
    /**
     * Refined Storage's own Mekanism integration, which is the thing that puts chemicals in
     * a grid in the first place. If it is absent there are no chemical rows to click.
     */
    private static final String CHEMICAL_UTIL =
        "com.refinedmods.refinedstorage.mekanism.ChemicalUtil";

    private static volatile boolean chemicalLookupDone;
    private static volatile ItemCapability<?, Void> chemicalCapability;

    private GridContainers() {
    }

    /**
     * Whether this stack can hold fluid or chemical, empty or not.
     *
     * <p>Deliberately not "holds something right now": an empty tank on the cursor is still a
     * tank, and left-clicking a fluid row with one is exactly the case that should fill it.
     */
    public static boolean isBulkContainer(final ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getCapability(Capabilities.FluidHandler.ITEM) != null) {
            return true;
        }
        final ItemCapability<?, Void> chemical = chemicalCapability();
        return chemical != null && stack.getCapability(chemical) != null;
    }

    /**
     * Mekanism's item chemical-handler capability, or null when nothing provides one.
     *
     * <p>Read by reflection from a field Refined Storage's integration already exposes:
     *
     * <pre>{@code   public static final ItemCapability<IChemicalHandler, Void> ITEM_CAPABILITY; }</pre>
     *
     * <p>The alternative is a compile dependency on Mekanism to name {@code IChemicalHandler},
     * which {@code ItemCapability.createVoid} needs as its type token. That would buy nothing:
     * we never call a method on the handler, only ask whether the stack has one, and the
     * capability object itself is a plain NeoForge type we can already name. Reflection here
     * costs one field read on the first grid click of a session.
     *
     * <p>Every failure is the same answer - no chemical support - so the catch is deliberately
     * {@link Throwable}. {@code ClassNotFoundException} means the integration is not installed;
     * {@code NoClassDefFoundError} means it is installed against a Mekanism that is not, which
     * is the pack's problem and not a reason to break clicking on fluids.
     */
    @SuppressWarnings("unchecked")
    private static ItemCapability<?, Void> chemicalCapability() {
        if (!chemicalLookupDone) {
            synchronized (GridContainers.class) {
                if (!chemicalLookupDone) {
                    try {
                        final Field field = Class.forName(CHEMICAL_UTIL).getField("ITEM_CAPABILITY");
                        chemicalCapability = (ItemCapability<?, Void>) field.get(null);
                    } catch (final Throwable noChemicalIntegration) {
                        chemicalCapability = null;
                    }
                    chemicalLookupDone = true;
                }
            }
        }
        return chemicalCapability;
    }
}
