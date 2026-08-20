package com.wraithhawit.rstweaks;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.neoforge.support.resource.VariantUtil;

import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.ItemCapability;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

import org.jetbrains.annotations.Nullable;

/**
 * Recognises a stack that holds a bulk resource rather than being one, and measures how many
 * grid operations it would take to fill or empty it.
 *
 * <p>A grid click means something different when you are holding a tank: the stack on the
 * cursor is a <em>destination</em>, not cargo. {@code AbstractGridScreenMixin} needs that
 * distinction before it changes what a click does, so an ordinary item on the cursor keeps
 * Refined Storage's stock behaviour untouched.
 *
 * <p>It also needs a count, because <b>no single operation empties a tank</b>. Refined Storage
 * asks the container's own handler for {@code Long.MAX_VALUE} and takes what it is given, and
 * a Mekanism tank gives one tier transfer rate per operation - 64 B from an Ultimate that
 * holds 256 B. The count is measured rather than assumed: every figure below comes from a
 * simulated transfer against the container in front of us, so a tier table, a config that
 * changes one, or a mod we have never seen all answer correctly without being known about.
 *
 * <p>Fluids are a straight capability lookup. Chemicals are reached without a Mekanism
 * dependency - see {@link #chemicalCapability()}.
 */
public final class GridContainers {
    /**
     * Ceiling on the operations one click may produce, so a container that misreports its
     * rate cannot turn a click into a packet flood.
     *
     * <p>Four covers an Ultimate fluid tank and two covers a creative one, so this is pure
     * headroom for a ratio we have not seen. Hitting it is not an error: the transfer moves
     * as much as the cap allows and clicking again continues it.
     */
    private static final int MAX_OPERATIONS = 64;

    /** One bucket, the unit a SINGLE_RESOURCE grid transfer moves. */
    private static final long BUCKET = 1000L;

    /**
     * Refined Storage's own Mekanism integration, which is the thing that puts chemicals in
     * a grid in the first place. If it is absent there are no chemical rows to click.
     */
    private static final String CHEMICAL_UTIL =
        "com.refinedmods.refinedstorage.mekanism.ChemicalUtil";

    private static volatile boolean chemicalLookupDone;
    @Nullable
    private static volatile ItemCapability<?, Void> chemicalCapability;
    @Nullable
    private static volatile Chemicals chemicals;

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
     * Whether this container is the kind of thing that holds this resource at all.
     *
     * <p>A <b>kind</b> test, not an acceptance test: fluid container and fluid row, or chemical
     * container and chemical row. It does not ask whether the fluid would fit, is the right
     * one, or is accepted right now.
     *
     * <p>That is deliberate, and it is what {@code GridResource.canExtract} should have been
     * asked instead of what it is:
     *
     * <pre>{@code   ResourceAmount toFill = new ResourceAmount(resource, repository.getAmount(resource));
     *   return Platform.INSTANCE.fillContainer(carriedStack, toFill).map(r -> r.amount() > 0L)...  }</pre>
     *
     * <p>It offers the container the <em>entire network total</em>, and {@code fillContainer}
     * reaches {@code VariantUtil.toFluidStack}, which narrows a long to an int - warning above
     * {@code Integer.MAX_VALUE} and then doing it anyway. A network holding more than about
     * 2.147 billion mB of something therefore hands the tank a negative {@code FluidStack},
     * gets zero back, and concludes the tank cannot take that fluid. Refined Storage's own
     * tooltip path asks the same question with one bucket and gets it right; only
     * {@code canExtract} asks "can you take all of it". Reported in game on 0.2.105 against
     * ~2.1 MB of XP fluid from a Just Dire Things experience holder.
     *
     * <p>Deciding by kind sidesteps the amount entirely. The cost of being too permissive is a
     * click that moves nothing; the cost of being too strict was storing the player's tank in
     * the network. Those are not comparable, so this errs the cheap way.
     */
    public static boolean canHold(final ItemStack stack, @Nullable final PlatformResourceKey resource) {
        if (resource == null || stack.isEmpty()) {
            return false;
        }
        if (resource instanceof FluidResource) {
            return stack.getCapability(Capabilities.FluidHandler.ITEM) != null;
        }
        final Chemicals api = chemicals();
        return api != null && api.isChemicalResource(resource) && chemicalHandler(stack) != null;
    }

    /**
     * How many grid operations it takes to empty this container into the network.
     *
     * <p>Contents divided by what one operation can remove, both measured: the divisor is a
     * simulated drain of everything, which is precisely the amount a real
     * {@code ENTIRE_RESOURCE} insert will move, because that is the same call with
     * {@code EXECUTE}.
     *
     * <p>Never returns less than one. A click the player made should do something, or at
     * least fail the way an ordinary click fails, rather than being silently dropped because
     * a container declined to describe itself.
     */
    public static int operationsToEmpty(final ItemStack stack) {
        final IFluidHandlerItem fluid = stack.getCapability(Capabilities.FluidHandler.ITEM);
        if (fluid != null) {
            long stored = 0;
            for (int tank = 0; tank < fluid.getTanks(); tank++) {
                stored += fluid.getFluidInTank(tank).getAmount();
            }
            return operations(stored, fluid.drain(Integer.MAX_VALUE, FluidAction.SIMULATE).getAmount());
        }
        final Chemicals api = chemicals();
        final Object handler = chemicalHandler(stack);
        if (api != null && handler != null) {
            return api.operationsToEmpty(handler);
        }
        return 1;
    }

    /**
     * How many grid operations it takes to fill this container with this resource.
     *
     * <p>Free space divided by what one operation can add. The resource is needed because a
     * fill has to be simulated with something: an empty tank cannot be asked what it would
     * give back, only what it would accept, and what it would accept depends on what is
     * offered. A null resource, or one this container has no opinion about, means no measure
     * is available and one operation is the answer.
     */
    public static int operationsToFill(
        final ItemStack stack,
        @Nullable final PlatformResourceKey resource
    ) {
        final IFluidHandlerItem fluid = stack.getCapability(Capabilities.FluidHandler.ITEM);
        if (fluid != null) {
            if (!(resource instanceof FluidResource fluidResource)) {
                return 1;
            }
            long space = 0;
            for (int tank = 0; tank < fluid.getTanks(); tank++) {
                space += fluid.getTankCapacity(tank) - fluid.getFluidInTank(tank).getAmount();
            }
            final FluidStack offered = VariantUtil.toFluidStack(fluidResource, Integer.MAX_VALUE);
            return operations(space, fluid.fill(offered, FluidAction.SIMULATE));
        }
        final Chemicals api = chemicals();
        final Object handler = chemicalHandler(stack);
        if (api != null && handler != null) {
            return api.operationsToFill(handler, resource);
        }
        return 1;
    }

    /**
     * How many bucket-sized transfers this container has room for.
     *
     * <p>The fallback for a resource the network holds more than {@code Integer.MAX_VALUE} of,
     * where a whole-amount transfer cannot survive Refined Storage's narrowing to a
     * {@code FluidStack} and only bucket-sized ones get through. Slower per click - one bucket
     * a packet, and the cap will stop it short of a large tank - but it moves something, which
     * the alternative did not.
     */
    public static int bucketsToFill(final ItemStack stack) {
        final IFluidHandlerItem fluid = stack.getCapability(Capabilities.FluidHandler.ITEM);
        if (fluid == null) {
            return 1;
        }
        long space = 0;
        for (int tank = 0; tank < fluid.getTanks(); tank++) {
            space += fluid.getTankCapacity(tank) - fluid.getFluidInTank(tank).getAmount();
        }
        return operations(space, BUCKET);
    }

    /**
     * Operations to move {@code total} at {@code perOperation} each, clamped to something a
     * click may reasonably produce.
     *
     * <p>A non-positive rate means the container would not commit to a number - full, empty,
     * wrong resource, or simply not answering. One operation then lets Refined Storage's own
     * handling produce whatever it produces, which is the behaviour without this feature.
     */
    private static int operations(final long total, final long perOperation) {
        if (total <= 0 || perOperation <= 0) {
            return 1;
        }
        final long needed = (total + perOperation - 1) / perOperation;
        return (int) Math.max(1, Math.min(MAX_OPERATIONS, needed));
    }

    @Nullable
    private static Object chemicalHandler(final ItemStack stack) {
        final ItemCapability<?, Void> capability = chemicalCapability();
        return capability == null ? null : stack.getCapability(capability);
    }

    /**
     * Mekanism's item chemical-handler capability, or null when nothing provides one.
     *
     * <p>Read by reflection from a field Refined Storage's integration already exposes:
     *
     * <pre>{@code   public static final ItemCapability<IChemicalHandler, Void> ITEM_CAPABILITY; }</pre>
     *
     * <p>The alternative is a compile dependency on Mekanism to name {@code IChemicalHandler},
     * which {@code ItemCapability.createVoid} needs as its type token. The capability object
     * itself is a plain NeoForge type we can already name, so borrowing the one that exists
     * costs a field read on the first grid click of a session and nothing after.
     *
     * <p>Every failure is the same answer - no chemical support - so the catch is deliberately
     * {@link Throwable}. {@code ClassNotFoundException} means the integration is not installed;
     * {@code NoClassDefFoundError} means it is installed against a Mekanism that is not, which
     * is the pack's problem and not a reason to break clicking on fluids.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    private static ItemCapability<?, Void> chemicalCapability() {
        if (!chemicalLookupDone) {
            synchronized (GridContainers.class) {
                if (!chemicalLookupDone) {
                    try {
                        final Field field = Class.forName(CHEMICAL_UTIL).getField("ITEM_CAPABILITY");
                        chemicalCapability = (ItemCapability<?, Void>) field.get(null);
                        chemicals = Chemicals.resolve();
                    } catch (final Throwable noChemicalIntegration) {
                        chemicalCapability = null;
                        chemicals = null;
                    }
                    chemicalLookupDone = true;
                }
            }
        }
        return chemicalCapability;
    }

    @Nullable
    private static Chemicals chemicals() {
        chemicalCapability();
        return chemicals;
    }

    /**
     * The handful of Mekanism methods needed to measure a chemical container, resolved once.
     *
     * <p>This is the same arithmetic as the fluid path above; only the API differs. It is
     * reflective for the reason given on {@link #chemicalCapability()} - the mod must run in
     * packs with no Mekanism - and the handles are resolved together so that a partial API
     * change disables the measurement rather than half-performing it.
     */
    private record Chemicals(
        Method getChemicalTanks,
        Method getChemicalInTank,
        Method getChemicalTankCapacity,
        Method extractChemical,
        Method insertChemical,
        Method getAmount,
        Method chemicalOfResource,
        Constructor<?> chemicalStack,
        Object simulate
    ) {
        @Nullable
        static Chemicals resolve() {
            try {
                final Class<?> handler = Class.forName("mekanism.api.chemical.IChemicalHandler");
                final Class<?> stack = Class.forName("mekanism.api.chemical.ChemicalStack");
                final Class<?> chemical = Class.forName("mekanism.api.chemical.Chemical");
                final Class<?> action = Class.forName("mekanism.api.Action");
                final Class<?> resource =
                    Class.forName("com.refinedmods.refinedstorage.mekanism.ChemicalResource");
                return new Chemicals(
                    handler.getMethod("getChemicalTanks"),
                    handler.getMethod("getChemicalInTank", int.class),
                    handler.getMethod("getChemicalTankCapacity", int.class),
                    handler.getMethod("extractChemical", long.class, action),
                    handler.getMethod("insertChemical", stack, action),
                    stack.getMethod("getAmount"),
                    resource.getMethod("chemical"),
                    stack.getConstructor(chemical, long.class),
                    Enum.valueOf(action.asSubclass(Enum.class), "SIMULATE")
                );
            } catch (final Throwable unusableApi) {
                return null;
            }
        }

        int operationsToEmpty(final Object handler) {
            try {
                long stored = 0;
                final int tanks = (int) getChemicalTanks.invoke(handler);
                for (int tank = 0; tank < tanks; tank++) {
                    stored += amountOf(getChemicalInTank.invoke(handler, tank));
                }
                final Object drained = extractChemical.invoke(handler, Long.MAX_VALUE, simulate);
                return operations(stored, amountOf(drained));
            } catch (final Throwable refused) {
                return 1;
            }
        }

        int operationsToFill(final Object handler, @Nullable final PlatformResourceKey resource) {
            if (resource == null) {
                return 1;
            }
            try {
                if (!chemicalOfResource.getDeclaringClass().isInstance(resource)) {
                    return 1;
                }
                long space = 0;
                final int tanks = (int) getChemicalTanks.invoke(handler);
                for (int tank = 0; tank < tanks; tank++) {
                    space += (long) getChemicalTankCapacity.invoke(handler, tank)
                        - amountOf(getChemicalInTank.invoke(handler, tank));
                }
                final Object offered = chemicalStack.newInstance(
                    chemicalOfResource.invoke(resource),
                    Long.MAX_VALUE
                );
                // insertChemical returns the REMAINDER, so what fits is offered minus it.
                final Object remainder = insertChemical.invoke(handler, offered, simulate);
                return operations(space, Long.MAX_VALUE - amountOf(remainder));
            } catch (final Throwable refused) {
                return 1;
            }
        }

        boolean isChemicalResource(final PlatformResourceKey resource) {
            return chemicalOfResource.getDeclaringClass().isInstance(resource);
        }

        private long amountOf(@Nullable final Object chemicalStack) throws Exception {
            return chemicalStack == null ? 0L : (long) getAmount.invoke(chemicalStack);
        }
    }
}
