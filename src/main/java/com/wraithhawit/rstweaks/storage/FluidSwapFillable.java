package com.wraithhawit.rstweaks.storage;

/**
 * Implemented by the Pattern Grid menu so the per-tick hook can reach it.
 *
 * <p>{@code broadcastChanges()} is declared on {@code AbstractResourceContainerMenu}, while the
 * processing output container is declared on {@code PatternGridContainerMenu} — and a mixin can
 * only shadow members of its own target. So the tick lands on one class, the state lives on the
 * other, and this interface joins them.
 */
public interface FluidSwapFillable {
    void rstweaks$autoFillFluidSubstitution();

    /**
     * Selects a tab immediately on the client and asks the server to make the same change through
     * the synced Refined Storage menu property.
     *
     * <p>The immediate client-side change is important. Waiting for the server to rebind its slots
     * made the old tab's contents remain visible for a tick, and because the client had only one
     * matrix that update also overwrote its only remembered copy.
     */
    void rstweaks$selectFluidTab(boolean open);

    /**
     * Records whether the player has the fluid substitution tab open.
     *
     * <p>Without this the server fills in the other half of a swap whenever a bucket lands in an
     * empty matrix — including in Refined Storage's own Processing tab, where someone starting a
     * machine recipe with a bucket found the outputs written for them and the finished pattern
     * named a fluid substitution.
     */
    void rstweaks$setFluidTab(boolean open);

    /** The tab this menu is currently bound to, independently on client and server. */
    boolean rstweaks$isFluidTab();

    /**
     * Selects the tab represented by an encoded pattern placed in the Pattern Grid.
     *
     * <p>This does not send a request: the same slot change is already handled on the server and
     * its property is the authority. It only makes both menu instances bind the matching matrix.
     */
    void rstweaks$patternLoaded(boolean fluidSubstitution);

    /**
     * Which tab the server says this grid was left on.
     *
     * <p>The client used to infer this from the matrix it was sent, which is Refined Storage's
     * Processing matrix regardless of the real tab — so a grid left on the fluid tab always
     * reopened on Processing. Synced as a Refined Storage menu property, so it is authoritative and
     * works even for an empty fluid matrix, which contents-inspection could never identify.
     */
    boolean rstweaks$serverSaysFluidTab();

    /**
     * Rearranges the processing matrix into the fluid substitution layout, or puts it back.
     *
     * <p>Lives on the menu because that is where the slots are, even though only the client ever
     * calls it — slot coordinates are display state and the server never reads them.
     *
     * @return {@code true} if the layout could be applied
     */
    boolean rstweaks$applyFluidLayout(boolean on);

    /**
     * Whether the laid-out input slot still reports itself active.
     *
     * <p>A processing matrix slot is active only while the grid is in processing mode, so this is
     * a public stand-in for "the player has not switched to another pattern type" — which is
     * otherwise only observable through package-private members.
     */
    boolean rstweaks$fluidLayoutVisible();

    /**
     * Whether the processing matrix currently holds a fluid substitution pattern.
     *
     * <p>Used to pick the tab when a grid is opened. The selection itself is not stored anywhere —
     * it is read back off the pattern, which Refined Storage already persists.
     */
    boolean rstweaks$holdsFluidSwap();
}
