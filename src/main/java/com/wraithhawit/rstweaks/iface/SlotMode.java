package com.wraithhawit.rstweaks.iface;

/**
 * What one filter slot participates in.
 *
 * <p>The two side buttons are master switches for the whole configuration; this is per entry. Until
 * it existed, every listed resource was pinned at its amount whenever both switches were on, and
 * "file away all my cobblestone, and keep me stocked with sixty-four torches" needed two grids to
 * say. Now it is one screen.
 *
 * <p>{@link #BOTH} is the default so that a configuration written before this existed, or by
 * somebody who never touches the markers, behaves exactly as it did: the master switches decide,
 * and every slot follows them.
 *
 * <p>The insert bit is <b>inert in {@code BLOCK} filter mode</b>, and that is not an oversight. In
 * BLOCK the list is the set of things you keep and everything else is filed away, so "insert this
 * listed resource" is a contradiction rather than a setting. The screen greys the marker and says
 * so rather than letting you set something that does nothing.
 */
public enum SlotMode {
    /** Neither direction. The entry still occupies a slot and still means something in BLOCK mode. */
    OFF(0, false, false),
    /** File the surplus away, never top up. */
    INSERT(1, true, false),
    /** Top up to the amount, never file away. */
    EXPORT(2, false, true),
    /** Both, which together pin the resource at its amount. */
    BOTH(3, true, true);

    private static final SlotMode[] BY_ID = {OFF, INSERT, EXPORT, BOTH};

    private final int id;
    private final boolean insert;
    private final boolean export;

    SlotMode(final int id, final boolean insert, final boolean export) {
        this.id = id;
        this.insert = insert;
        this.export = export;
    }

    public int getId() {
        return id;
    }

    public boolean insert() {
        return insert;
    }

    public boolean export() {
        return export;
    }

    /**
     * The next mode in the cycle, which is the order the markers step through when clicked:
     * both → insert → export → off → both.
     */
    public SlotMode next() {
        return switch (this) {
            case BOTH -> INSERT;
            case INSERT -> EXPORT;
            case EXPORT -> OFF;
            case OFF -> BOTH;
        };
    }

    /**
     * Out-of-range ids come back as {@link #BOTH} rather than throwing. The id arrives from a
     * packet and from item data that a different build may have written; the safe answer is the
     * default, not a crash on a stack somebody is holding.
     */
    public static SlotMode byId(final int id) {
        return id >= 0 && id < BY_ID.length ? BY_ID[id] : BOTH;
    }
}
