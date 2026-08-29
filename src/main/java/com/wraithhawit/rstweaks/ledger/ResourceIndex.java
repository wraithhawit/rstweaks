package com.wraithhawit.rstweaks.ledger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dense integer ids for resources, so nothing below this line ever holds a game object.
 *
 * <p>The {@code ledger} package is deliberately free of Minecraft <em>and</em> Refined Storage
 * types. That is not tidiness: it is what lets the whole model — slots, pools, conservation —
 * run in a plain JVM in milliseconds, and it is the only reason a property test can hammer
 * thousands of random plans on every build. Only the adapters in {@code ledger.rs} know what a
 * {@code ResourceKey} is.
 *
 * <p>Ids are also the arithmetic. A column in the ledger is a resource id, and a pool is
 * addressed by the id of its representative member, so pools need no separate id space — see
 * {@link Pools}.
 *
 * <h2>Keys must have value equality</h2>
 *
 * <p>The key handed to {@link #idOf} is used in a {@link HashMap}, so a key whose class does not
 * override {@code equals} silently becomes an identity key: the same resource arriving twice
 * mints two ids, every balance splits in half, and conservation appears to hold because both
 * halves are wrong in the same direction. Minecraft's {@code ItemStack} is exactly such a class,
 * and Refined Storage builds a fresh one on every call, so this is not hypothetical — it has
 * already cost one bug elsewhere in this mod. The check below turns that into a loud failure at
 * the first offending key instead of a wrong number a week later.
 */
public final class ResourceIndex {
    /** A slot that hands nothing back; the {@code becomes} of a consumed ingredient. */
    public static final int NOTHING = -1;

    private final Map<Object, Integer> ids = new HashMap<>();
    private final List<Object> keys = new ArrayList<>();
    private final Map<Class<?>, Boolean> valueEquality = new HashMap<>();

    /** The id for this key, minting one if it is new. */
    public int idOf(final Object key) {
        if (key == null) {
            throw new IllegalArgumentException("null is not a resource");
        }
        requireValueEquality(key);
        final Integer existing = this.ids.get(key);
        if (existing != null) {
            return existing;
        }
        final int id = this.keys.size();
        this.ids.put(key, id);
        this.keys.add(key);
        return id;
    }

    /** The id for this key, or {@link #NOTHING} if it has never been seen. Mints nothing. */
    public int lookup(final Object key) {
        final Integer existing = key == null ? null : this.ids.get(key);
        return existing == null ? NOTHING : existing;
    }

    /** The key behind an id, for adapters that have to name a concrete resource again. */
    public Object key(final int id) {
        if (id < 0 || id >= this.keys.size()) {
            throw new IndexOutOfBoundsException("no such resource: " + id);
        }
        return this.keys.get(id);
    }

    /** A human-readable name for an id, for diagnostics a player is meant to read. */
    public String label(final int id) {
        return id == NOTHING ? "nothing" : String.valueOf(this.keys.get(id));
    }

    public int size() {
        return this.keys.size();
    }

    /** Balances keyed by id, labelled — the shape every failure message in this package wants. */
    public Map<String, Long> labelled(final Map<Integer, Long> byId) {
        final Map<String, Long> out = new LinkedHashMap<>();
        byId.forEach((id, amount) -> out.put(label(id), amount));
        return out;
    }

    private void requireValueEquality(final Object key) {
        final Class<?> type = key.getClass();
        final Boolean known = this.valueEquality.get(type);
        if (known != null) {
            if (!known) {
                throw new IllegalArgumentException(reject(type));
            }
            return;
        }
        boolean ok;
        try {
            ok = type.getMethod("equals", Object.class).getDeclaringClass() != Object.class
                && type.getMethod("hashCode").getDeclaringClass() != Object.class;
        } catch (final NoSuchMethodException e) {
            // Cannot happen for any loadable class, but a false pass here is the silent bug
            // this guard exists to prevent, so it fails closed.
            ok = false;
        }
        this.valueEquality.put(type, ok);
        if (!ok) {
            throw new IllegalArgumentException(reject(type));
        }
    }

    private static String reject(final Class<?> type) {
        return type.getName() + " does not override equals/hashCode, so it would be an identity"
            + " key: the same resource would mint a new id every time it arrived. Wrap it in a"
            + " value type before indexing it.";
    }
}
