package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.common.api.support.resource.ResourceContainer;
import com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternGridBlockEntity;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.storage.FluidMatrixContainers;
import com.wraithhawit.rstweaks.storage.FluidSubstitutionMark;
import com.wraithhawit.rstweaks.storage.FluidSwapStash;

import javax.annotation.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the tab you are not looking at, and remembers which one that was.
 *
 * <p>Stored on the block entity rather than the menu so that closing the Pattern Grid does not
 * discard half of what is in it. Written into the same NBT Refined Storage already saves the
 * matrix to, under our own keys — an unmodified Refined Storage reading this grid simply ignores
 * them and sees the pattern that is live, which is the one it would have seen anyway.
 *
 * @see FluidSwapStash
 */
/**
 * <h2>Priority 500, and it is load-bearing</h2>
 *
 * <p>Cable Tiers injects into {@code createProcessingPattern} at {@code RETURN} as well, and its
 * callback finishes with {@code cir.setReturnValue(pattern)} to attach its sided-input component.
 * In Mixin that <b>cancels the method</b>, and every RETURN callback appended after it is skipped.
 * At equal priority ours was appended second, so from 0.2.65 to 0.2.68 it never executed once — no
 * error, no warning, the injector applied perfectly and was simply never reached.
 *
 * <p>Mixin applies lower priority numbers first and callbacks run in application order, so 500 puts
 * ours ahead of Cable Tiers' default 1000. Running first is also the correct order on the merits:
 * we mark the stack and return normally, then Cable Tiers adds its component to the same stack and
 * returns it, so both survive. Reversed, only the last writer's work is kept.
 *
 * <p>If a fluid substitution pattern ever stops being marked again, check first whether another mod
 * has started cancelling this method ahead of us.
 */
@Mixin(value = PatternGridBlockEntity.class, priority = 500)
public abstract class PatternGridBlockEntityMixin implements FluidSwapStash {
    @Unique
    private boolean rstweaks$tabOpen;

    /**
     * The fluid tab's own matrix, built lazily and never null once asked for.
     *
     * <p>Lazy rather than an initialiser, and read only through the accessors — a {@code @Unique}
     * field initialiser is exactly what silently failed to apply in
     * {@code AbstractTaskPatternMixin} and cost seven versions of autocrafting.
     *
     * <p>Built by {@link RsTweaksContainers} rather than {@code new ResourceContainerImpl(...)},
     * because the encode path calls {@code getIngredient} on the input container and only Refined
     * Storage's own {@code ProcessingMatrixInputResourceContainer} implements it.
     */
    @Unique
    @Nullable
    private ResourceContainer rstweaks$fluidInputContainer;

    @Unique
    @Nullable
    private ResourceContainer rstweaks$fluidOutputContainer;

    @Override
    public ResourceContainer rstweaks$fluidInput() {
        ResourceContainer container = this.rstweaks$fluidInputContainer;
        if (container == null) {
            container = FluidMatrixContainers.createInput();
            // The cast is the standard mixin idiom: this class extends nothing, so BlockEntity's
            // own setChanged is not visible to the compiler even though it is there at runtime.
            container.setListener(() -> ((PatternGridBlockEntity) (Object) this).setChanged());
            this.rstweaks$fluidInputContainer = container;
        }
        return container;
    }

    @Override
    public ResourceContainer rstweaks$fluidOutput() {
        ResourceContainer container = this.rstweaks$fluidOutputContainer;
        if (container == null) {
            container = FluidMatrixContainers.createOutput();
            // The cast is the standard mixin idiom: this class extends nothing, so BlockEntity's
            // own setChanged is not visible to the compiler even though it is there at runtime.
            container.setListener(() -> ((PatternGridBlockEntity) (Object) this).setChanged());
            this.rstweaks$fluidOutputContainer = container;
        }
        return container;
    }

    @Override
    public boolean rstweaks$fluidTabOpen() {
        return this.rstweaks$tabOpen;
    }

    @Override
    public void rstweaks$setFluidTabOpen(final boolean open) {
        this.rstweaks$tabOpen = open;
    }

    @Inject(method = "saveAdditional", at = @At("RETURN"))
    private void rstweaks$saveFluidStash(final CompoundTag tag,
                                         final HolderLookup.Provider provider,
                                         final CallbackInfo ci) {
        // Absent rather than empty when there is nothing to say, so a grid that has never seen the
        // fluid tab saves exactly the bytes it saved before this existed.
        if (this.rstweaks$tabOpen) {
            tag.putBoolean(RSTWEAKS_TAB_OPEN, true);
        }
        // Refined Storage's own toTag, so the round trip carries everything a matrix holds --
        // including the allowed alternatives on a fuzzy input slot -- and cannot drift from
        // whatever RS decides a matrix contains.
        if (this.rstweaks$fluidInputContainer != null) {
            tag.put(RSTWEAKS_FLUID_INPUTS, this.rstweaks$fluidInputContainer.toTag(provider));
        }
        if (this.rstweaks$fluidOutputContainer != null) {
            tag.put(RSTWEAKS_FLUID_OUTPUTS, this.rstweaks$fluidOutputContainer.toTag(provider));
        }
    }

    @Inject(method = "loadAdditional", at = @At("RETURN"))
    private void rstweaks$loadFluidStash(final CompoundTag tag,
                                         final HolderLookup.Provider provider,
                                         final CallbackInfo ci) {
        this.rstweaks$tabOpen = tag.getBoolean(RSTWEAKS_TAB_OPEN);
        // Through the accessors, so the containers are built if this grid has fluid contents saved
        // and left unbuilt if it does not -- a grid that has never seen the fluid tab costs nothing.
        if (tag.contains(RSTWEAKS_FLUID_INPUTS)) {
            rstweaks$fluidInput().fromTag(tag.getCompound(RSTWEAKS_FLUID_INPUTS), provider);
        }
        if (tag.contains(RSTWEAKS_FLUID_OUTPUTS)) {
            rstweaks$fluidOutput().fromTag(tag.getCompound(RSTWEAKS_FLUID_OUTPUTS), provider);
        }
    }

    /**
     * Marks a pattern as a fluid substitution at the moment it is encoded.
     *
     * <p>Contents are ambiguous — see {@link ResolvedProcessingPatternMixin} — whereas the tab the
     * player was looking at when they pressed the button is not.
     *
     * <p>The tab comes from the menu through {@link FluidSubstitutionMark#encodingOnFluidTab()}, and
     * <b>not</b> from this class's own {@code rstweaks$tabOpen}. 0.2.65 and 0.2.66 read that field
     * and marked nothing: it is stash bookkeeping written only when a matrix swap actually happens,
     * not a record of which tab is on screen. The two agree often enough to look right and differ
     * exactly when it matters.
     *
     * <p>No check that the contents really form a swap. The mark records intent, and a pattern
     * encoded on the fluid tab that does not describe one simply never converts: the resolver tests
     * the contents too, and both have to agree. Refusing the mark here instead would mean a pattern
     * silently became an ordinary processing pattern because a container's capability answered
     * differently at encode time than at resolve time.
     */
    @Inject(method = "createProcessingPattern", at = @At("RETURN"))
    private void rstweaks$markFluidSubstitution(final CallbackInfoReturnable<ItemStack> cir) {
        final ItemStack pattern = cir.getReturnValue();
        // Null is Refined Storage's "nothing to encode" — an empty input or output side.
        if (pattern == null || pattern.isEmpty() || !FluidSubstitutionMark.encodingOnFluidTab()) {
            return;
        }
        FluidSubstitutionMark.mark(pattern);
        // One line per button press, and the only evidence that the mark was written at all --
        // everything downstream reads it silently. Cheap to keep, and it is what would have caught
        // this the first time.
        RSTweaks.LOGGER.info("[rstweaks] encoded a fluid substitution pattern");
    }

    @Unique
    private static final String RSTWEAKS_TAB_OPEN = "rstweaks_fluid_tab_open";

    @Unique
    private static final String RSTWEAKS_FLUID_INPUTS = "rstweaks_fluid_inputs";

    @Unique
    private static final String RSTWEAKS_FLUID_OUTPUTS = "rstweaks_fluid_outputs";
}
