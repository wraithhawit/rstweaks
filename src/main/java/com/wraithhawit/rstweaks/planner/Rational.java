package com.wraithhawit.rstweaks.planner;

/**
 * Exact rational arithmetic over {@code long} numerator/denominator.
 *
 * <p>The simplex tableau is pivoted repeatedly, and floating point drift across those
 * pivots makes feasibility tests unreliable — a basic variable that should be exactly
 * zero drifts to 1e-13 and the solver either cycles or reports an infeasible problem
 * as solved. Since a wrong crafting plan is worse than no plan, everything here is
 * exact and overflows throw rather than wrapping.
 *
 * <p>Always kept in lowest terms with a non-negative denominator, so {@link #equals}
 * and {@link #signum} are cheap and canonical.
 */
public final class Rational implements Comparable<Rational> {
    public static final Rational ZERO = new Rational(0L, 1L);
    public static final Rational ONE = new Rational(1L, 1L);

    private final long num;
    private final long den;

    private Rational(final long num, final long den) {
        this.num = num;
        this.den = den;
    }

    public static Rational of(final long value) {
        return new Rational(value, 1L);
    }

    public static Rational of(final long numerator, final long denominator) {
        if (denominator == 0L) {
            throw new ArithmeticException("Rational with zero denominator");
        }
        long n = numerator;
        long d = denominator;
        if (d < 0L) {
            if (n == Long.MIN_VALUE || d == Long.MIN_VALUE) {
                throw new ArithmeticException("Rational overflow on sign normalisation");
            }
            n = -n;
            d = -d;
        }
        final long g = gcd(Math.abs(n), d);
        return g <= 1L ? new Rational(n, d) : new Rational(n / g, d / g);
    }

    private static long gcd(long a, long b) {
        while (b != 0L) {
            final long t = a % b;
            a = b;
            b = t;
        }
        return a == 0L ? 1L : a;
    }

    public Rational add(final Rational other) {
        // Cross-multiplying first and reducing after keeps intermediate values as
        // small as possible, which matters because these overflow checks are the
        // difference between a wrong plan and a rejected one.
        final long g = gcd(this.den, other.den);
        final long lcm = Math.multiplyExact(this.den / g, other.den);
        final long a = Math.multiplyExact(this.num, lcm / this.den);
        final long b = Math.multiplyExact(other.num, lcm / other.den);
        return of(Math.addExact(a, b), lcm);
    }

    public Rational subtract(final Rational other) {
        return this.add(other.negate());
    }

    public Rational multiply(final Rational other) {
        // Cross-reduce before multiplying to avoid needless overflow.
        final long g1 = gcd(Math.abs(this.num), other.den);
        final long g2 = gcd(Math.abs(other.num), this.den);
        return of(
            Math.multiplyExact(this.num / g1, other.num / g2),
            Math.multiplyExact(this.den / g2, other.den / g1)
        );
    }

    public Rational divide(final Rational other) {
        if (other.num == 0L) {
            throw new ArithmeticException("Rational division by zero");
        }
        return this.multiply(other.reciprocal());
    }

    public Rational negate() {
        if (this.num == Long.MIN_VALUE) {
            throw new ArithmeticException("Rational overflow on negate");
        }
        return new Rational(-this.num, this.den);
    }

    public Rational reciprocal() {
        return of(this.den, this.num);
    }

    public int signum() {
        return Long.signum(this.num);
    }

    public boolean isZero() {
        return this.num == 0L;
    }

    /** True when this is an exact integer. */
    public boolean isIntegral() {
        return this.den == 1L;
    }

    /** Largest integer {@code <= this}, correct for negatives. */
    public long floor() {
        if (this.den == 1L) {
            return this.num;
        }
        final long q = this.num / this.den;
        return this.num < 0L ? q - 1L : q;
    }

    /** Smallest integer {@code >= this}, correct for negatives. */
    public long ceil() {
        if (this.den == 1L) {
            return this.num;
        }
        final long q = this.num / this.den;
        return this.num > 0L ? q + 1L : q;
    }

    /** Distance to the nearest integer, used to pick the most fractional variable. */
    public Rational fractionalDistance() {
        final Rational floor = of(this.floor());
        final Rational frac = this.subtract(floor);
        final Rational half = of(1L, 2L);
        return frac.compareTo(half) <= 0 ? frac : ONE.subtract(frac);
    }

    public double toDouble() {
        return (double) this.num / (double) this.den;
    }

    @Override
    public int compareTo(final Rational other) {
        final long left = Math.multiplyExact(this.num, other.den);
        final long right = Math.multiplyExact(other.num, this.den);
        return Long.compare(left, right);
    }

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof Rational other && this.num == other.num && this.den == other.den;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(this.num) * 31 + Long.hashCode(this.den);
    }

    @Override
    public String toString() {
        return this.den == 1L ? Long.toString(this.num) : this.num + "/" + this.den;
    }
}
