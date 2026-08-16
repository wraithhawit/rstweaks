package com.wraithhawit.rstweaks.content;

import com.mojang.serialization.Codec;
import com.wraithhawit.rstweaks.RSTweaks;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Unit;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The one thing this mod puts on an item: a mark saying a pattern is a fluid substitution.
 *
 * <p>Everything else rstweaks does is a mixin over Refined Storage's own data. This is not, and the
 * reason is the difference between <em>inspecting</em> a pattern and <em>declaring</em> one.
 *
 * <p>Until now a fluid substitution was recognised by looking at an encoded processing pattern and
 * deciding it looked like a container swap — {@code FluidSwap.detect} run over the ingredients and
 * outputs, at resolve time, at tooltip time, at naming time. That has a failure mode which is not
 * theoretical: a genuine machine recipe that happens to take a full container and give back the
 * empty one plus its fluid is indistinguishable from a swap, and converting it means Refined Storage
 * settles it as bookkeeping and produces the output <b>without the machine ever running</b>.
 *
 * <p>With a mark, the pattern says what it is because the player encoded it on the fluid tab. The
 * contents are still parsed — {@code internalLayout} needs the container, the fluid and the amount,
 * and those can only come from reading it — so this does not replace {@link
 * com.wraithhawit.rstweaks.storage.FluidSwap}. It <em>authorises</em> it. Read the mark as "you may
 * convert this if it really is a swap", not as "this is a swap".
 *
 * <p><b>Deliberately carries no data.</b> Storing the swap itself — container, fluid, amount — would
 * be a second copy of something derivable, and copies go stale: a pack that changes a tank's
 * capacity would leave every pattern encoded before it lying about how much it holds. Derived every
 * time, the answer is always the truth as the pack currently defines it.
 *
 * <p>An unmodified Refined Storage reading a marked pattern ignores the component entirely and sees
 * the processing pattern it has always been, so nothing about this makes a world one-way.
 */
public final class RSTweaksComponents {
    private RSTweaksComponents() {
    }

    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, RSTweaks.MODID);

    /**
     * Present or absent; there is no false value.
     *
     * <p>{@code persistent} so it survives the world save, {@code networkSynchronized} so the client
     * can name and draw the pattern without asking the server. Patterns never stack — every one
     * carries a unique id in its {@code PatternState} — so adding a component cannot change how they
     * pile up in a chest.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Unit>>
        FLUID_SUBSTITUTION = COMPONENTS.register(
            "fluid_substitution",
            () -> DataComponentType.<Unit>builder()
                .persistent(Codec.unit(Unit.INSTANCE))
                .networkSynchronized(StreamCodec.unit(Unit.INSTANCE))
                .build());

    public static void register(final IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
    }
}
