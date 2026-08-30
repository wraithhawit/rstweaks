package com.wraithhawit.rstweaks.storage;

/**
 * A resource list that counts its own mutations, so a cached answer about its contents can be
 * invalidated exactly rather than statistically.
 *
 * <p>Implemented by {@code MutableResourceListImplMixin}. Everything that reads it must cope with a
 * list that does <em>not</em> implement it — a different list class, or the mixin failing to apply —
 * by falling back to recomputing. That keeps correctness a property of the code rather than of the
 * mixin having landed.
 */
public interface VersionedResourceList {
    /**
     * Increments on every {@code add}, {@code remove} and {@code clear}.
     *
     * <p>Equal versions mean the list has not changed, which is the only direction that matters: a
     * cached decision is reused only while the number is identical, so a missed increment would be
     * a correctness bug and a spurious one costs nothing but a recomputation.
     */
    long rstweaks$version();
}
