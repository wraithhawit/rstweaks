package com.wraithhawit.rstweaks.storage;

import com.refinedmods.refinedstorage.common.autocrafting.ProcessingPatternState;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.content.RSTweaksComponents;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;

/**
 * Reading and writing the fluid substitution mark, and carrying the answer to the one place that
 * cannot ask for itself.
 *
 * @see RSTweaksComponents#FLUID_SUBSTITUTION
 */
public final class FluidSubstitutionMark {
    private FluidSubstitutionMark() {
    }

    /** Marks a freshly encoded pattern as a fluid substitution. */
    public static void mark(final ItemStack pattern) {
        pattern.set(RSTweaksComponents.FLUID_SUBSTITUTION.get(), Unit.INSTANCE);
    }

    /** Whether this encoded pattern was made on the fluid substitution tab. */
    public static boolean isMarked(final ItemStack pattern) {
        return pattern.has(RSTweaksComponents.FLUID_SUBSTITUTION.get());
    }

    /**
     * Whether a pattern in this state may be converted to an internal layout.
     *
     * <p>Marked patterns always may. Unmarked ones may only while
     * {@code convertUnmarkedFluidPatterns} is on, which is what keeps every pattern encoded before
     * the mark existed working — see {@link Config#convertUnmarkedFluidPatterns}.
     */
    public static boolean mayConvert(final boolean marked) {
        return marked || Config.convertUnmarkedFluidPatterns;
    }

    /**
     * The mark of the pattern currently being resolved.
     *
     * <p>A thread local, for the same reason {@link TaskConsumption} is one: the decision is made
     * where the {@code ItemStack} is in scope — {@code PatternResolver.getProcessingPattern} — and
     * needed one frame deeper, inside {@code ResolvedProcessingPattern}'s constructor, which is
     * handed only a UUID and two lists. There is no signature between them that we control.
     *
     * <p>Set and cleared around that one call on the same thread, and every reader defaults to
     * unmarked, so a missed handover falls back to the legacy behaviour rather than corrupting a
     * pattern. Resolution runs on Refined Storage's autocrafting threads as well as the server
     * thread, which is exactly why this cannot be a plain static field.
     */
    private static final ThreadLocal<Boolean> RESOLVING = new ThreadLocal<>();

    public static void beginResolving(final boolean marked) {
        RESOLVING.set(marked);
    }

    public static void endResolving() {
        RESOLVING.remove();
    }

    /** Whether the pattern being resolved on this thread carries the mark. */
    public static boolean resolvingMarked() {
        return Boolean.TRUE.equals(RESOLVING.get());
    }

    /**
     * Which tab the player was on for the pattern being encoded on this thread.
     *
     * <p>Set around {@code PatternGridContainerMenu.createPattern}, read inside
     * {@code PatternGridBlockEntity.createProcessingPattern}, because the block entity has no idea
     * which menu asked it — or whether a player asked at all.
     *
     * <p><b>Why not the block entity's own flag.</b> 0.2.65 and 0.2.66 read
     * {@code FluidSwapStash.rstweaks$fluidTabOpen()} here and marked nothing, because that flag does
     * not mean what the name suggests. It is <em>stash bookkeeping</em> — which matrix is currently
     * loaded into the shared containers — and it is written only inside {@code rstweaks$swapMatrix},
     * which returns early when the processing slots have not been captured. The menu's
     * {@code rstweaks$fluidTab} is the tab the player is actually looking at: it is set on every
     * announcement from the client whether or not a swap follows. Two different facts that agree
     * most of the time, which is exactly why substituting one for the other went unnoticed.
     */
    private static final ThreadLocal<Boolean> ENCODING = new ThreadLocal<>();

    public static void beginEncoding(final boolean onFluidTab) {
        ENCODING.set(onFluidTab);
    }

    public static void endEncoding() {
        ENCODING.remove();
    }

    /** Whether the pattern being encoded on this thread is being made on the fluid tab. */
    public static boolean encodingOnFluidTab() {
        return Boolean.TRUE.equals(ENCODING.get());
    }

    /**
     * Client-side memory of which encoded contents belonged to a marked pattern.
     *
     * <p>The tooltip needs this and cannot ask. {@code ProcessingPatternClientTooltipComponent}'s
     * constructor is handed a {@link ProcessingPatternState} and nothing else — no stack, not even
     * the pattern id, which {@code PatternItem.ProcessingPatternTooltipComponent} carries and then
     * drops. So the answer is recorded where the stack <em>is</em> in scope, in
     * {@code PatternItem.getTooltipImage}, and looked up by contents.
     *
     * <p><b>Keyed by value, and that is a real limitation.</b> {@code ProcessingPatternState} is a
     * record, so two patterns with byte-identical contents share an entry — and a marked and an
     * unmarked pattern with identical contents is precisely the pair this whole mechanism exists to
     * tell apart. Hover both and the second overwrites the first. The consequence is bounded: a
     * tooltip drawn in the wrong style. Nothing is named wrongly, nothing converts, nothing is lost.
     *
     * <p>The real fix is our own {@code TooltipComponent} and a client factory registered for it,
     * which would carry the mark directly and delete the string-targeted mixin over Refined
     * Storage's package-private one. That belongs with the rest of the type work.
     */
    private static final int REMEMBERED = 64;

    private static final Map<ProcessingPatternState, Boolean> MARKED_CONTENTS =
        Collections.synchronizedMap(new LinkedHashMap<ProcessingPatternState, Boolean>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<ProcessingPatternState, Boolean> e) {
                return size() > REMEMBERED;
            }
        });

    /** Records, while the stack is in scope, whether these contents came from a marked pattern. */
    public static void rememberMarked(final ProcessingPatternState state, final boolean marked) {
        MARKED_CONTENTS.put(state, marked);
    }

    /**
     * Whether a pattern with these contents was marked.
     *
     * <p>Defaults to unmarked for contents never seen through {@code getTooltipImage}, which is the
     * safe direction: an unseen pattern is drawn the way Refined Storage would have drawn it.
     */
    public static boolean wasMarked(final ProcessingPatternState state) {
        return Boolean.TRUE.equals(MARKED_CONTENTS.get(state));
    }
}
