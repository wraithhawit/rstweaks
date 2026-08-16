package com.wraithhawit.rstweaks.storage;

/**
 * Geometry for the fluid substitution arrangement — one input, a two-way arrow, two outputs —
 * and the flag saying whether the Pattern Grid is currently showing it.
 *
 * <p>All coordinates are in <em>slot space</em>: relative to the screen's {@code leftPos} and
 * {@code topPos}, which is what {@code Slot.x} and {@code Slot.y} are measured in. That keeps this
 * class free of any client-only type, so the menu mixin can use it on a dedicated server without
 * dragging rendering classes onto the server classpath.
 *
 * <p>The numbers are derived from Refined Storage's own processing layout rather than invented.
 * The processing inset is 130x54 starting at slot x 12, so an 18-wide input, a 20-wide arrow with
 * 8px gaps and two 18-wide outputs come to 90, leaving a 20px margin on each side.
 */
public final class FluidSwapLayout {
    private FluidSwapLayout() {
    }

    /**
     * Whether the grid is showing the fluid substitution arrangement.
     *
     * <p>Client-side display state on a client-side screen, so a plain static is honest here —
     * only one Pattern Grid screen can be open at a time.
     */
    public static volatile boolean active;

    /** Left edge of Refined Storage's processing inset, in slot space. */
    public static final int INSET_X = 12;
    /** Width of the processing inset. */
    public static final int INSET_WIDTH = 130;
    /** Height of the processing inset. */
    public static final int INSET_HEIGHT = 54;

    /** A slot cell including its one-pixel border. */
    public static final int CELL = 18;

    /**
     * Whether the grid is showing the filling direction — two inputs, one output — rather than
     * the emptying direction.
     *
     * <p>The pattern has a direction even though the arrow points both ways: emptying a container
     * is one thing in and two out, and filling one is two in and one out. Which side gets the pair
     * follows what the player put in, so the arrangement is never lying about the pattern.
     */
    public static volatile boolean filling;

    /**
     * The four cell positions. Each arrangement uses three of them, and both span 32 to 122, so
     * the composition stays centred in the inset whichever way round it is.
     */
    public static final int LEFT_ONE_CELL_X = 32;
    public static final int LEFT_TWO_CELL_X = 50;
    public static final int RIGHT_ONE_CELL_X = 86;
    public static final int RIGHT_TWO_CELL_X = 104;

    /** Cell x for the input slots, in order, for the current direction. */
    public static int[] inputCells() {
        return filling
            ? new int[] {LEFT_ONE_CELL_X, LEFT_TWO_CELL_X}
            : new int[] {LEFT_ONE_CELL_X};
    }

    /** Cell x for the output slots, in order, for the current direction. */
    public static int[] outputCells() {
        return filling
            ? new int[] {RIGHT_TWO_CELL_X}
            : new int[] {RIGHT_ONE_CELL_X, RIGHT_TWO_CELL_X};
    }

    /** Centre of the gap between the two groups. */
    public static int arrowCentreX() {
        final int leftEdge = (filling ? LEFT_TWO_CELL_X : LEFT_ONE_CELL_X) + CELL;
        final int rightEdge = filling ? RIGHT_TWO_CELL_X : RIGHT_ONE_CELL_X;
        return (leftEdge + rightEdge) / 2;
    }

    /**
     * Cell y, as an offset from the first processing row. The inset is 54 tall and starts one pixel
     * above that row, so an 18-tall cell centres 17 pixels below it.
     */
    public static final int CELL_Y_OFFSET = 17;

    /**
     * Where unused slots are parked. {@code ProcessingMatrixResourceSlot.isActive()} is
     * {@code y >= startY && y < endY} with {@code endY} 54 below the first row, so a slot sitting
     * exactly there is inactive: not drawn, not clickable, not part of quick-move. Hiding the other
     * 160 slots is therefore Refined Storage's own mechanism rather than anything we invented.
     */
    public static final int PARKED_Y_OFFSET = 54;

    /** Slot content sits one pixel inside its cell. */
    public static int contentX(final int cellX) {
        return cellX + 1;
    }
}
