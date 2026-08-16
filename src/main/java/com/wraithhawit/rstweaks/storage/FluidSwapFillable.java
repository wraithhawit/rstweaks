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
    /**
     * Menu button ids the client uses to tell the server whether the fluid substitution tab is
     * open. Which tab is showing is client state — the tab is a widget, and the pattern type
     * underneath it is PROCESSING either way — but the auto-fill runs on the server, because the
     * matrix containers are synced server-to-client and a client-side write is simply overwritten
     * on the next broadcast.
     *
     * <p>Sent as a menu button click rather than a custom payload on purpose. It is a vanilla
     * packet that needs no registration, it cannot disconnect a client that joins a server without
     * this mod, and neither Refined Storage nor any of its menus overrides
     * {@code clickMenuButton} — checked against the compiled classes — so the channel is free.
     * The values are arbitrary and only have to miss vanilla's small button ids; the packet codes
     * the id as a VarInt, so their size costs nothing.
     */
    int RSTWEAKS_FLUID_TAB_ON = 0x727301;

    /** @see #RSTWEAKS_FLUID_TAB_ON */
    int RSTWEAKS_FLUID_TAB_OFF = 0x727300;

    void rstweaks$autoFillFluidSubstitution();

    /**
     * Records whether the player has the fluid substitution tab open.
     *
     * <p>Without this the server fills in the other half of a swap whenever a bucket lands in an
     * empty matrix — including in Refined Storage's own Processing tab, where someone starting a
     * machine recipe with a bucket found the outputs written for them and the finished pattern
     * named a fluid substitution.
     */
    void rstweaks$setFluidTab(boolean open);

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