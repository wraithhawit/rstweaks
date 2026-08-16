package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.Platform;
import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.api.support.resource.ResourceContainer;
import com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternGridBlockEntity;
import com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternGridContainerMenu;
import com.refinedmods.refinedstorage.common.support.containermenu.ResourceSlot;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.common.support.containermenu.ClientProperty;
import com.refinedmods.refinedstorage.common.support.containermenu.Property;
import com.refinedmods.refinedstorage.common.support.containermenu.PropertyType;
import com.refinedmods.refinedstorage.common.support.containermenu.ServerProperty;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.storage.FluidSubstitutionMark;
import com.wraithhawit.rstweaks.storage.FluidSwap;
import com.wraithhawit.rstweaks.storage.FluidSwapFillable;
import com.wraithhawit.rstweaks.storage.FluidSwapLayout;
import com.wraithhawit.rstweaks.storage.FluidSwapStash;
import com.wraithhawit.rstweaks.storage.ProcessingInputContainer;
import com.wraithhawit.rstweaks.storage.SlotContainerAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fills in the rest of a fluid substitution pattern once you put one thing in.
 *
 * <p>Both directions, because both are one click away with a bucket in hand — Refined Storage's
 * resource slots take the <em>item</em> on left click and the <em>fluid</em> on right click:
 *
 * <pre>
 *   lava bucket in   ->  outputs become  empty bucket + 1000mB lava      (emptying)
 *   1000mB lava in   ->  inputs gain an empty bucket, output a lava bucket (filling)
 * </pre>
 *
 * <p>Amounts come from the container's own fluid handler rather than being typed, so they cannot be
 * off by a millibucket — which the resolver would quietly refuse to recognise.
 *
 * <p>Triggered by the input <em>changing</em> rather than by the tab being clicked, so it works
 * however the resource arrived: by hand, or dragged from EMI, JEI or REI. Those are just other ways
 * of filling a slot, and none of them need integration code.
 *
 * <p>Deliberately conservative: it only ever acts when every output is empty, so it can complete a
 * pattern you have not started but can never overwrite one you are partway through.
 */
@Mixin(PatternGridContainerMenu.class)
public abstract class PatternGridContainerMenuMixin implements FluidSwapFillable {
    /**
     * Declared on the menu itself and typed as public API, unlike {@code processingInput} whose
     * type is package-private — which is why the input side is found through
     * {@link ProcessingInputContainer} instead.
     */
    @Shadow
    @Final
    private ResourceContainer processingOutput;

    /**
     * What every processing slot held last tick, so the one the player just touched can be picked
     * out. Sized to the matrix on first use; a length mismatch means we have no usable history.
     */
    @Unique
    private PlatformResourceKey[] rstweaks$seenResources = new PlatformResourceKey[0];

    @Unique
    private long[] rstweaks$seenAmounts = new long[0];

    /** Every processing slot, inputs first, captured the first time the layout is applied. */
    @Unique
    @Nullable
    private List<ResourceSlot> rstweaks$processingSlots;

    @Unique
    private int rstweaks$inputCount;

    @Unique
    private int[] rstweaks$originalX = new int[0];

    @Unique
    private int[] rstweaks$originalY = new int[0];

    /**
     * Whether the player has the fluid substitution tab open, as reported by the client. False
     * until told otherwise, so a grid nobody has opened the tab on never has a pattern written for
     * it — including on a client that does not have this mod at all.
     */
    @Unique
    private boolean rstweaks$fluidTab;

    /** Whether {@link #rstweaks$loadTabState()} has already read the grid's remembered tab. */
    @Unique
    private boolean rstweaks$tabStateLoaded;

    /**
     * The grid this menu was opened on, or {@code null} on the client, where the menu is built from
     * a {@code PatternGridData} record instead. Declared on {@code PatternGridContainerMenu} itself,
     * so shadowing it is legal.
     */
    @Shadow
    @Nullable
    private PatternGridBlockEntity patternGrid;

    @Unique
    private boolean rstweaks$laidOut;

    @Unique
    private boolean rstweaks$laidOutFilling;

    /**
     * Rearranges the 9x9-plus-9x9 processing matrix into one input, an arrow and two outputs.
     *
     * <p>No new slots are made and none are removed — the three we want are moved into place and
     * the other 159 are parked on the line where {@code ProcessingMatrixResourceSlot.isActive()}
     * stops being true. That is Refined Storage's own visibility rule, the same one its scrollbar
     * relies on, so a parked slot is invisible, unclickable and skipped by quick-move without us
     * having to intercept any of those paths.
     *
     * <p>The positions are only display state; the containers behind the slots are untouched, so
     * a pattern encoded here is byte-for-byte a processing pattern and stays readable by an
     * unmodified Refined Storage.
     */
    @Override
    public boolean rstweaks$applyFluidLayout(final boolean on) {
        if (this.rstweaks$processingSlots == null && !rstweaks$captureProcessingSlots()) {
            return false;
        }
        final List<ResourceSlot> slots = Objects.requireNonNull(this.rstweaks$processingSlots);
        if (!on) {
            for (int i = 0; i < slots.size(); i++) {
                rstweaks$move(slots.get(i), this.rstweaks$originalX[i], this.rstweaks$originalY[i]);
            }
            this.rstweaks$laidOut = false;
            return true;
        }
        // Which way round the pattern goes is decided by what is in it, so the arrangement follows
        // the player rather than the player having to pick a direction first.
        FluidSwapLayout.filling = rstweaks$inputHoldsFluid();
        if (this.rstweaks$laidOut && this.rstweaks$laidOutFilling == FluidSwapLayout.filling) {
            return true;
        }
        final int firstRow = rstweaks$firstRowY();
        for (int i = 0; i < slots.size(); i++) {
            rstweaks$move(slots.get(i), this.rstweaks$originalX[i],
                firstRow + FluidSwapLayout.PARKED_Y_OFFSET);
        }
        final int contentY = firstRow + FluidSwapLayout.CELL_Y_OFFSET + 1;
        final int[] inputCells = FluidSwapLayout.inputCells();
        for (int i = 0; i < inputCells.length; i++) {
            rstweaks$place(slots, i, inputCells[i], contentY);
        }
        final int[] outputCells = FluidSwapLayout.outputCells();
        for (int i = 0; i < outputCells.length; i++) {
            rstweaks$place(slots, this.rstweaks$inputCount + i, outputCells[i], contentY);
        }
        this.rstweaks$laidOut = true;
        this.rstweaks$laidOutFilling = FluidSwapLayout.filling;
        return true;
    }

    /** A fluid on the input side means the pattern is filling a container rather than emptying one. */
    @Unique
    private boolean rstweaks$inputHoldsFluid() {
        for (final ResourceSlot slot : rstweaks$inputSlots()) {
            if (!slot.isEmpty() && slot.getResource() instanceof FluidResource) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean rstweaks$fluidLayoutVisible() {
        final List<ResourceSlot> slots = this.rstweaks$processingSlots;
        return slots != null && !slots.isEmpty() && slots.getFirst().isActive();
    }

    @Override
    public boolean rstweaks$holdsFluidSwap() {
        final List<ResourceSlot> inputs = rstweaks$inputSlots();
        // isActive() on a processing matrix slot is only true in PROCESSING mode, so this also
        // answers "is the grid even on the right pattern type" without naming the package-private
        // enum. A crafting pattern that happens to involve a bucket must not select our tab.
        if (inputs.isEmpty() || !inputs.getFirst().isActive()) {
            return false;
        }
        final List<Ingredient> ingredients = new ArrayList<>(2);
        for (final ResourceSlot slot : inputs) {
            if (!slot.isEmpty() && slot.getResource() != null) {
                ingredients.add(new Ingredient(slot.getAmount(), List.of(slot.getResource())));
            }
        }
        final List<ResourceAmount> outputs = new ArrayList<>(2);
        for (final ResourceSlot slot : rstweaks$outputSlots()) {
            if (!slot.isEmpty() && slot.getResource() != null) {
                outputs.add(new ResourceAmount(slot.getResource(), slot.getAmount()));
            }
        }
        return FluidSwap.detect(ingredients, outputs) != null;
    }

    @Unique
    private void rstweaks$place(final List<ResourceSlot> slots,
                              final int index,
                              final int cellX,
                              final int contentY) {
        if (index < slots.size()) {
            rstweaks$move(slots.get(index), FluidSwapLayout.contentX(cellX), contentY);
        }
    }

    /**
     * {@code Slot.y} goes through Refined Storage's own platform helper, which is a plain field
     * write on NeoForge and the call its scrollbar already makes. There is no matching helper for
     * {@code x}, so that one is assigned directly — the field is public and mutable in the same
     * patched Minecraft both mods compile against.
     */
    @Unique
    private void rstweaks$move(final ResourceSlot slot, final int x, final int y) {
        slot.x = x;
        Platform.INSTANCE.setSlotY(slot, y);
    }

    /** The unscrolled y of the first processing row, taken from the captured originals. */
    @Unique
    private int rstweaks$firstRowY() {
        int lowest = Integer.MAX_VALUE;
        for (final int y : this.rstweaks$originalY) {
            lowest = Math.min(lowest, y);
        }
        return lowest;
    }

    /**
     * @return {@code false} if the grid is not showing a processing matrix, in which case there is
     *     nothing to rearrange and the caller should leave the screen alone.
     */
    @Unique
    private boolean rstweaks$captureProcessingSlots() {
        final List<ResourceSlot> inputs = rstweaks$inputSlots();
        final List<ResourceSlot> outputs = new ArrayList<>(inputs.size());
        for (final Slot slot : ((AbstractContainerMenu) (Object) this).slots) {
            if (slot instanceof ResourceSlot resourceSlot
                && ((SlotContainerAccess) resourceSlot).rstweaks$container() == this.processingOutput) {
                outputs.add(resourceSlot);
            }
        }
        if (inputs.isEmpty() || outputs.size() < 2) {
            return false;
        }
        final List<ResourceSlot> all = new ArrayList<>(inputs.size() + outputs.size());
        all.addAll(inputs);
        all.addAll(outputs);
        this.rstweaks$inputCount = inputs.size();
        this.rstweaks$originalX = new int[all.size()];
        this.rstweaks$originalY = new int[all.size()];
        for (int i = 0; i < all.size(); i++) {
            this.rstweaks$originalX[i] = all.get(i).x;
            this.rstweaks$originalY[i] = all.get(i).y;
        }
        this.rstweaks$processingSlots = all;
        return true;
    }

    /**
     * Tells the block entity which tab this pattern is being encoded on.
     *
     * <p>The menu is the only thing that knows. {@code PatternGridBlockEntity.createPattern} is
     * reached from here, from a hopper-like automation, and from nothing at all on a dedicated
     * server tick — it has no menu and no player, so it cannot ask. Passing the answer down for the
     * duration of the call is the narrowest way to say it.
     *
     * <p>{@code rstweaks$loadTabState} first, so a grid reopened on the fluid tab and never
     * switched still answers correctly: the menu's flag is lazy and might not have been read yet.
     */
    /**
     * Clears the tab you are actually looking at.
     *
     * <p>{@code PatternGridBlockEntity.clear()} switches on the pattern type and empties Refined
     * Storage's processing matrix, which for the fluid tab is now the wrong container entirely — so
     * Clear appeared to do nothing while quietly wiping the Processing tab instead.
     *
     * <p>That is worse than an inconvenience: auto-fill refuses to overwrite a matrix holding
     * something that is not a valid swap, and only 3 of the 162 slots are visible in this layout.
     * Anything left in the other 159 blocks auto-fill with no way for the player to see it or reach
     * it. Clear is the way out, so it has to clear the right thing.
     */
    @Inject(method = "clear", at = @At("HEAD"), cancellable = true)
    private void rstweaks$clearFluidMatrix(final CallbackInfo ci) {
        rstweaks$loadTabState();
        if (!this.rstweaks$fluidTab || !(this.patternGrid instanceof FluidSwapStash stash)) {
            return;
        }
        stash.rstweaks$fluidInput().clear();
        stash.rstweaks$fluidOutput().clear();
        this.patternGrid.setChanged();
        final List<ResourceSlot> all = new ArrayList<>(rstweaks$inputSlots());
        all.addAll(rstweaks$outputSlots());
        rstweaks$remember(all);
        ci.cancel();
    }

    @Inject(method = "createPattern", at = @At("HEAD"))
    private void rstweaks$beginEncoding(final CallbackInfo ci) {
        rstweaks$loadTabState();
        FluidSubstitutionMark.beginEncoding(this.rstweaks$fluidTab);
        rstweaks$lendFluidMatrix();
    }

    @Inject(method = "createPattern", at = @At("RETURN"))
    private void rstweaks$endEncoding(final CallbackInfo ci) {
        rstweaks$returnBorrowedMatrix();
        FluidSubstitutionMark.endEncoding();
    }

    /**
     * Lends the fluid tab's matrix to Refined Storage for the length of one encode.
     *
     * <p>{@code PatternGridBlockEntity.createProcessingPattern} reads the block entity's own
     * {@code processingInput} and {@code processingOutput} fields, so an encode from the fluid tab
     * would otherwise write whatever the <em>Processing</em> tab is holding. Those fields cannot be
     * redirected — the input one is typed {@code ProcessingMatrixInputResourceContainer}, which is
     * package-private, so no handler could declare a matching return type — and after the module
     * resolution failure in 0.2.71 there is no way to name that type from anywhere.
     *
     * <p>Done here rather than on the block entity because this is the only place both containers
     * are reachable as the public {@code ResourceContainer}: Refined Storage's own through the slot
     * binding captured in {@code rstweaks$loadTabState}, and the fluid pair through the block
     * entity's accessors.
     *
     * <p>The borrowed state cannot be observed. It exists inside one method call on the server
     * thread with no tick boundary in it, and {@code broadcastChanges} runs once a tick; this
     * menu's own slots are pointed at the fluid containers throughout, so the player never sees
     * Refined Storage's copy either way.
     */
    @Unique
    private void rstweaks$lendFluidMatrix() {
        if (!this.rstweaks$fluidTab
            || !(this.patternGrid instanceof FluidSwapStash stash)
            || this.patternGrid.getLevel() == null
            || this.rstweaks$originalInput == null) {
            return;
        }
        final HolderLookup.Provider provider = this.patternGrid.getLevel().registryAccess();
        this.rstweaks$borrowedInput = this.rstweaks$originalInput.toTag(provider);
        this.rstweaks$borrowedOutput = this.processingOutput.toTag(provider);
        this.rstweaks$originalInput.fromTag(stash.rstweaks$fluidInput().toTag(provider), provider);
        this.processingOutput.fromTag(stash.rstweaks$fluidOutput().toTag(provider), provider);
    }

    /**
     * Puts Refined Storage's own matrix back.
     *
     * <p>Injected at {@code RETURN} of the same method that lent it, so an early return still
     * restores. If this were ever skipped the Processing tab would be left holding the fluid tab's
     * pattern permanently, which is why the borrow is bounded by one call rather than a flag.
     */
    @Unique
    private void rstweaks$returnBorrowedMatrix() {
        if (this.rstweaks$borrowedInput == null
            || this.rstweaks$originalInput == null
            || this.patternGrid == null
            || this.patternGrid.getLevel() == null) {
            return;
        }
        final HolderLookup.Provider provider = this.patternGrid.getLevel().registryAccess();
        this.rstweaks$originalInput.fromTag(this.rstweaks$borrowedInput, provider);
        if (this.rstweaks$borrowedOutput != null) {
            this.processingOutput.fromTag(this.rstweaks$borrowedOutput, provider);
        }
        this.rstweaks$borrowedInput = null;
        this.rstweaks$borrowedOutput = null;
    }

    @Unique
    @Nullable
    private CompoundTag rstweaks$borrowedInput;

    @Unique
    @Nullable
    private CompoundTag rstweaks$borrowedOutput;

    /**
     * Tells the client which tab this grid was left on.
     *
     * <p>Without it the client had to guess, and guessed by looking at the matrix it was sent —
     * which is Refined Storage's Processing matrix, whatever tab the grid is really on. So
     * reopening a grid left on the fluid tab always looked like Processing, and worse, the screen
     * then <em>told the server</em> Processing, overwriting the very thing the block entity had
     * remembered. One synced bit removes the guess entirely, including for an empty fluid matrix,
     * which no amount of inspecting contents could ever have identified.
     *
     * <p>Registered at {@code RETURN} of <b>both</b> constructors so client and server agree on the
     * data slot index — Refined Storage registers its own properties inside those constructors, and
     * appending after them on both sides is what keeps the two in step. A property registered on
     * one side only would silently shift every index after it.
     */
    @Unique
    private static final PropertyType<Boolean> RSTWEAKS_FLUID_TAB_PROPERTY = new PropertyType<>(
        ResourceLocation.fromNamespaceAndPath(RSTweaks.MODID, "fluid_tab"),
        open -> open ? 1 : 0,
        raw -> raw == 1);

    @Unique
    @Nullable
    private Property<Boolean> rstweaks$fluidTabProperty;

    @Inject(
        method = "<init>(ILnet/minecraft/world/entity/player/Inventory;"
            + "Lcom/refinedmods/refinedstorage/common/autocrafting/patterngrid/PatternGridData;)V",
        at = @At("RETURN")
    )
    private void rstweaks$registerClientTabProperty(final CallbackInfo ci) {
        final Property<Boolean> property =
            new ClientProperty<>(RSTWEAKS_FLUID_TAB_PROPERTY, false);
        this.rstweaks$fluidTabProperty = property;
        ((AbstractBaseContainerMenuInvoker) this).rstweaks$registerProperty(property);
    }

    @Inject(
        method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lcom/refinedmods/"
            + "refinedstorage/common/autocrafting/patterngrid/PatternGridBlockEntity;)V",
        at = @At("RETURN")
    )
    private void rstweaks$registerServerTabProperty(final CallbackInfo ci) {
        final Property<Boolean> property = new ServerProperty<>(
            RSTWEAKS_FLUID_TAB_PROPERTY,
            () -> {
                rstweaks$loadTabState();
                return this.rstweaks$fluidTab;
            },
            open -> { });
        this.rstweaks$fluidTabProperty = property;
        ((AbstractBaseContainerMenuInvoker) this).rstweaks$registerProperty(property);
    }

    @Override
    public boolean rstweaks$serverSaysFluidTab() {
        final Property<Boolean> property = this.rstweaks$fluidTabProperty;
        return property != null && Boolean.TRUE.equals(property.getValue());
    }

    @Override
    public void rstweaks$setFluidTab(final boolean open) {
        rstweaks$loadTabState();
        if (open == this.rstweaks$fluidTab) {
            // Already showing what is being asked for. Swapping anyway would hand the player the
            // other tab's pattern -- which is exactly what reopening a grid left on the fluid tab
            // does, since the client announces the tab it has just selected.
            return;
        }
        this.rstweaks$fluidTab = open;
        rstweaks$swapMatrix();
    }

    /**
     * Reads which tab this grid was left on, once per menu.
     *
     * <p>Lazily rather than in the constructor: the menu has two of those, only one of which has a
     * block entity, and a lazy read cannot be defeated by injecting into the wrong one.
     */
    @Unique
    private void rstweaks$loadTabState() {
        if (this.rstweaks$tabStateLoaded) {
            return;
        }
        this.rstweaks$tabStateLoaded = true;
        if (!(this.patternGrid instanceof FluidSwapStash stash)) {
            return;
        }
        // Refined Storage's own containers, read while the slots still point at them. After the
        // bind below they no longer do, and the block entity's accessor for the input side is
        // package-private, so this is the last chance to learn it.
        final List<ResourceSlot> inputs = rstweaks$inputSlots();
        if (!inputs.isEmpty()) {
            this.rstweaks$originalInput =
                ((SlotContainerAccess) inputs.getFirst()).rstweaks$container();
        }
        // Primes rstweaks$outputSlotCache while identity against processingOutput still holds. After
        // the bind below it never does again, and without the cache switching back to Processing
        // would find no output slots and quietly refuse to rebind.
        rstweaks$outputSlots();
        this.rstweaks$fluidTab = stash.rstweaks$fluidTabOpen();
        if (this.rstweaks$fluidTab && !Config.fluidSubstitutionPatterns) {
            // The feature was switched off while a grid was left on the fluid tab. There is no tab
            // left to press, so send it back to Processing rather than stranding it somewhere the
            // player cannot reach.
            this.rstweaks$fluidTab = false;
        }
        // Bind on open, not only on change. A grid reopened on the fluid tab never fires a tab
        // change, and without this its slots would stay pointed at the Processing matrix while the
        // menu believed it was showing the fluid one.
        rstweaks$swapMatrix();
    }

    /**
     * Points this menu's processing slots at the tab's own matrix.
     *
     * <p>Replaces the copy-in/copy-out stash this used to be. Both matrices now exist at once on the
     * block entity, so changing tab is a matter of which one <em>this</em> menu is looking at —
     * nothing is written, nothing is parked, and the pattern on the tab you left is still sitting
     * where you left it rather than serialised into a tag.
     *
     * <p>Three things fall out of that, all of which the stash had to work for or could not do:
     *
     * <ul>
     *   <li><b>Two players, two tabs.</b> Slots belong to a menu and containers belong to the block
     *       entity, so one player switching tab no longer moves the other's matrix. The stash held
     *       exactly one hidden pattern per grid and could not represent this at all.</li>
     *   <li><b>Nothing to sync.</b> {@code ResourceSlot.broadcastChanges} diffs each slot's
     *       container against what the client was last told, so the new tab's contents are sent on
     *       the next tick by the machinery that was already running.</li>
     *   <li><b>Nothing to serialise.</b> Both containers persist themselves through Refined
     *       Storage's own {@code toTag}/{@code fromTag} on the block entity, so a fuzzy input slot's
     *       allowed alternatives survive a world reload without this method knowing they exist.</li>
     * </ul>
     *
     * <p>Server-side only, by construction: the client's menu is built from a
     * {@code PatternGridData} record and has no block entity, so the {@code instanceof} below fails
     * there and the client simply receives the result.
     */
    @Unique
    private void rstweaks$swapMatrix() {
        final List<ResourceSlot> inputs = rstweaks$inputSlots();
        if (inputs.isEmpty() || !(this.patternGrid instanceof FluidSwapStash stash)) {
            return;
        }
        final List<ResourceSlot> outputs = rstweaks$outputSlots();
        if (outputs.isEmpty()) {
            return;
        }
        final ResourceContainer input = this.rstweaks$fluidTab
            ? stash.rstweaks$fluidInput()
            : rstweaks$processingInput();
        final ResourceContainer output = this.rstweaks$fluidTab
            ? stash.rstweaks$fluidOutput()
            : this.processingOutput;

        for (final ResourceSlot slot : inputs) {
            ((SlotContainerAccess) slot).rstweaks$rebind(input);
        }
        for (final ResourceSlot slot : outputs) {
            ((SlotContainerAccess) slot).rstweaks$rebind(output);
        }
        stash.rstweaks$setFluidTabOpen(this.rstweaks$fluidTab);

        // Rebinding changes every slot at once. Left unrecorded, the next tick reads it as the
        // player having filled a slot by hand, and the auto-fill would rebuild the pattern they
        // just switched away from.
        final List<ResourceSlot> all = new ArrayList<>(inputs);
        all.addAll(outputs);
        rstweaks$remember(all);
    }

    /**
     * Refined Storage's own processing input container, captured the first time it is seen.
     *
     * <p>Needed because after the first rebind the slots no longer point at it, and the block
     * entity's accessor for it is package-private. Captured rather than looked up, since at capture
     * time the slots are still bound to exactly the container this is asking for.
     */
    @Unique
    private ResourceContainer rstweaks$processingInput() {
        if (this.rstweaks$originalInput == null) {
            this.rstweaks$originalInput =
                ((SlotContainerAccess) rstweaks$inputSlots().getFirst()).rstweaks$container();
        }
        return this.rstweaks$originalInput;
    }

    @Unique
    @Nullable
    private ResourceContainer rstweaks$originalInput;

    @Unique
    private static final String RSTWEAKS_STASH_OUTPUTS = "outputs";

    @Override
    public void rstweaks$autoFillFluidSubstitution() {
        // Before the config check, so a grid left on the fluid tab is rescued even when the
        // feature has since been turned off.
        rstweaks$loadTabState();
        if (!Config.fluidSubstitutionPatterns) {
            return;
        }
        final List<ResourceSlot> inputs = rstweaks$inputSlots();
        final List<ResourceSlot> outputs = rstweaks$outputSlots();
        final List<ResourceSlot> all = new ArrayList<>(inputs.size() + outputs.size());
        all.addAll(inputs);
        all.addAll(outputs);

        // The trigger is the slot that just gained something, not the state of the matrix as a
        // whole. An earlier version fired only when exactly one slot in the matrix was filled,
        // which meant it stopped helping the moment a pattern existed -- so dropping a lava bucket
        // into a finished water pattern left the two mixed together, and the direction flip could
        // then park the leftover output somewhere the player could not see or clear it.
        final int touched = rstweaks$soleChange(all);
        // Evaluated against the snapshot, so it describes the pattern as it was before this
        // change -- which is the thing we would be destroying.
        final boolean replaceable = touched >= 0 && rstweaks$previousWasReplaceable(inputs.size());
        rstweaks$remember(all);
        // The tab is checked after the snapshot, never before it. Skipping the snapshot while the
        // player works in the Processing tab would leave a stale record of the matrix, and the
        // first thing they did after switching tabs would be measured against a pattern that is no
        // longer there.
        if (touched < 0 || !replaceable || !this.rstweaks$fluidTab) {
            return;
        }
        final ResourceSlot slot = all.get(touched);
        // Resource and amount are read separately rather than as a ResourceAmount. That record
        // validates in its constructor and throws on a zero amount, and building one per tick from
        // whatever is currently in a slot put a throw on the path of every menu tick — which is
        // what stopped the item direction working in 0.2.30. Nothing here needs the record.
        final PlatformResourceKey resource = slot.getResource();
        final long amount = slot.getAmount();
        if (resource == null || amount <= 0L) {
            return;
        }

        if (resource instanceof ItemResource container) {
            rstweaks$fillFromContainer(container, inputs);
        } else if (resource instanceof FluidResource fluid) {
            rstweaks$fillFromFluid(fluid, amount, inputs);
        } else {
            return;
        }
        // Everything we just wrote would otherwise look like the player's next move.
        rstweaks$remember(all);
    }

    /**
     * The single slot that gained content since the last tick, or {@code -1} if none did or more
     * than one did.
     *
     * <p>Emptying a slot deliberately does not count. Clearing the matrix is how a player starts
     * over, and rebuilding a pattern underneath them as they do it would make it impossible.
     *
     * <p>Returns {@code -1} on the first call as well: with no previous state, every filled slot
     * looks new, so a menu opened on a finished pattern would otherwise rebuild it on sight.
     */
    @Unique
    private int rstweaks$soleChange(final List<ResourceSlot> all) {
        if (this.rstweaks$seenResources.length != all.size()) {
            return -1;
        }
        int found = -1;
        for (int i = 0; i < all.size(); i++) {
            final ResourceSlot slot = all.get(i);
            final PlatformResourceKey resource = slot.getResource();
            if (resource == null
                || (Objects.equals(resource, this.rstweaks$seenResources[i])
                    && slot.getAmount() == this.rstweaks$seenAmounts[i])) {
                continue;
            }
            if (found != -1) {
                return -1;
            }
            found = i;
        }
        return found;
    }

    /**
     * Whether the pattern that was here before this change is one we may overwrite.
     *
     * <p>This is what keeps auto-fill out of the way of ordinary processing patterns. It cannot
     * ask which tab is open — the fill runs server-side, because the containers are synced
     * server-to-client and a client-side write would simply be overwritten, and which tab the
     * player is looking at never leaves the client. So it asks a better question instead: was
     * there anything here worth keeping?
     *
     * <p>Two answers say yes. An empty matrix is someone starting out. A matrix that already holds
     * a valid swap is someone retargeting this pattern from one fluid to another, which is exactly
     * the case that was broken. Anything else is a machine recipe part-way through being built by
     * hand, and dropping a bucket into that should not throw the rest away.
     */
    @Unique
    private boolean rstweaks$previousWasReplaceable(final int inputCount) {
        final List<Ingredient> ingredients = new ArrayList<>(2);
        final List<ResourceAmount> outputs = new ArrayList<>(2);
        boolean anything = false;
        for (int i = 0; i < this.rstweaks$seenResources.length; i++) {
            final PlatformResourceKey resource = this.rstweaks$seenResources[i];
            final long amount = this.rstweaks$seenAmounts[i];
            if (resource == null || amount <= 0L) {
                continue;
            }
            anything = true;
            if (i < inputCount) {
                ingredients.add(new Ingredient(amount, List.of(resource)));
            } else {
                outputs.add(new ResourceAmount(resource, amount));
            }
        }
        return !anything || FluidSwap.detect(ingredients, outputs) != null;
    }

    @Unique
    private void rstweaks$remember(final List<ResourceSlot> all) {
        if (this.rstweaks$seenResources.length != all.size()) {
            this.rstweaks$seenResources = new PlatformResourceKey[all.size()];
            this.rstweaks$seenAmounts = new long[all.size()];
        }
        for (int i = 0; i < all.size(); i++) {
            this.rstweaks$seenResources[i] = all.get(i).getResource();
            this.rstweaks$seenAmounts[i] = all.get(i).getAmount();
        }
    }

    /**
     * Empties both sides before rebuilding.
     *
     * <p>The single resource the player inserted may be sitting anywhere, including an output slot.
     * Wiping first and writing the canonical arrangement afterwards means the result is the same
     * whichever slot they used, rather than depending on where the stray one happened to land.
     */
    @Unique
    private void rstweaks$reset(final List<ResourceSlot> inputs) {
        if (!inputs.isEmpty()) {
            ((SlotContainerAccess) inputs.getFirst()).rstweaks$container().clear();
        }
        rstweaks$liveOutput().clear();
    }

    /** Emptying: the container is in hand, so both outputs are known. */
    @Unique
    private void rstweaks$fillFromContainer(final ItemResource container,
                                          final List<ResourceSlot> inputs) {
        final FluidSwap.Contents contents = FluidSwap.contents(container);
        if (contents == null || inputs.isEmpty()) {
            return;
        }
        rstweaks$reset(inputs);
        inputs.getFirst().change(new ResourceAmount(container, 1L));
        final ResourceContainer output = rstweaks$liveOutput();
        output.set(0, new ResourceAmount(contents.emptied(), 1L));
        output.set(1, new ResourceAmount(contents.fluid(), contents.amount()));
    }

    /**
     * Filling: only the fluid is known, so the container has to be inferred. The fluid's own bucket
     * is the answer for anything that has one, and the guess is then checked by emptying that bucket
     * and requiring it to give back exactly this fluid at exactly this amount — so a fluid whose
     * bucket holds a different quantity, or none at all, is left alone rather than half-filled.
     */
    @Unique
    private void rstweaks$fillFromFluid(final FluidResource fluid,
                                      final long amount,
                                      final List<ResourceSlot> inputs) {
        final var bucketItem = fluid.fluid().getBucket();
        if (bucketItem == null || bucketItem == Items.AIR) {
            return;
        }
        final ItemResource filledBucket = ItemResource.ofItemStack(new ItemStack(bucketItem));
        final FluidSwap.Contents contents = FluidSwap.contents(filledBucket);
        if (contents == null
            || !contents.fluid().equals(fluid)
            || contents.amount() != amount) {
            return;
        }
        if (inputs.size() < 2) {
            return;
        }
        rstweaks$reset(inputs);
        inputs.get(0).change(new ResourceAmount(fluid, amount));
        inputs.get(1).change(new ResourceAmount(contents.emptied(), 1L));
        rstweaks$liveOutput().set(0, new ResourceAmount(filledBucket, 1L));
    }

    @Unique
    private List<ResourceSlot> rstweaks$inputSlots() {
        final List<ResourceSlot> found = new ArrayList<>(9);
        for (final Slot slot : ((AbstractContainerMenu) (Object) this).slots) {
            if (slot instanceof ResourceSlot resourceSlot
                && ((SlotContainerAccess) resourceSlot).rstweaks$container()
                    instanceof ProcessingInputContainer) {
                found.add(resourceSlot);
            }
        }
        return found;
    }

    @Unique
    /**
     * <p>Identity against {@code processingOutput} only holds until the fluid tab rebinds these
     * slots to its own container, so once the capture has run the cached list is the answer. The
     * input side needs no such care: it tests {@code instanceof ProcessingInputContainer}, and the
     * fluid tab's input container is the same class, so it matches either way.
     */
    private List<ResourceSlot> rstweaks$outputSlots() {
        if (this.rstweaks$outputSlotCache != null) {
            return this.rstweaks$outputSlotCache;
        }
        if (this.rstweaks$processingSlots != null) {
            return this.rstweaks$processingSlots.subList(
                this.rstweaks$inputCount, this.rstweaks$processingSlots.size());
        }
        final List<ResourceSlot> found = new ArrayList<>(9);
        for (final Slot slot : ((AbstractContainerMenu) (Object) this).slots) {
            if (slot instanceof ResourceSlot resourceSlot
                && ((SlotContainerAccess) resourceSlot).rstweaks$container() == this.processingOutput) {
                found.add(resourceSlot);
            }
        }
        if (!found.isEmpty()) {
            this.rstweaks$outputSlotCache = found;
        }
        return found;
    }

    /**
     * Which output slots these are, remembered from before the first rebind.
     *
     * <p>Identity against {@code processingOutput} is the only way to find them, and the fluid tab
     * breaks it the moment it points them somewhere else. The client never rebinds — its menu has
     * no block entity — so this matters only on the server, where it must be primed in
     * {@link #rstweaks$loadTabState()} before anything moves.
     */
    @Unique
    @Nullable
    private List<ResourceSlot> rstweaks$outputSlotCache;

    /**
     * The output container these slots are actually pointed at right now.
     *
     * <p>Auto-fill used {@code processingOutput} directly, which was the same thing until the fluid
     * tab got a container of its own. Asking the slot is right by construction: whatever it is bound
     * to is what the player is looking at, whichever tab that is and whoever else has the grid open.
     */
    @Unique
    private ResourceContainer rstweaks$liveOutput() {
        final List<ResourceSlot> outputs = rstweaks$outputSlots();
        return outputs.isEmpty()
            ? this.processingOutput
            : ((SlotContainerAccess) outputs.getFirst()).rstweaks$container();
    }

}
