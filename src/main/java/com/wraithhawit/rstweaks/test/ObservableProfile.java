package com.wraithhawit.rstweaks.test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decodes an Observable profile export and ranks entities and block entities by cost.
 *
 * <pre>
 *   ./gradlew observableProfile --args="https://observable.tas.sh/p/sVGCQ"
 *   ./gradlew observableProfile --args="sVGCQ --top=40"
 * </pre>
 *
 * <p>Companion to {@link SparkProfile}, and the two answer different questions. Spark says
 * which <em>code</em> owns the tick; Observable says which <em>objects</em> do, with a count
 * and a position for each. "52% of the thread is mob AI" comes from spark. "53 villagers at
 * 5.7 ms/tick, first one at -365 61 303" comes from here, and only the second one tells you
 * where to go.
 *
 * <h2>Fetching it</h2>
 *
 * <p><b>The share link is not a web page.</b> {@code observable.tas.sh/p/<id>} is a SvelteKit
 * shell whose body is a single script tag — fetching it yields 696 bytes of loader and no
 * data, and a summariser handed that will honestly report the page as empty. The export lives
 * at <b>{@code /v1/get/<id>}</b> on the same host, which is what the site's own download
 * button points at. Recovered by pulling the route bundle out of
 * {@code _app/immutable/entry/app.*.js} and grepping it for fetch targets.
 *
 * <h2>The maths, taken from the site's own aggregator</h2>
 *
 * <p>Each tracked object carries a {@code rate} in nanoseconds and a {@code ticks} count of
 * how many of the profile's ticks it was actually observed for. The site computes
 * {@code rate * ticks / profileTicks} before summing, so a thing seen for a third of the
 * profile contributes a third of its rate. Summing the raw {@code rate} instead inflates
 * anything short-lived — dropped items and mobs that died mid-profile especially — and that
 * is the one way to get numbers out of this file that look plausible and are wrong.
 *
 * <p>Observable tracks a sample rather than the whole world, so treat the totals as a
 * proportional picture and not as an absolute ms/tick budget. Cross-check the split against
 * spark: if spark says entities are 53% of the thread and this says 58%, that agrees. If they
 * disagree wildly, one of the two profiles is not of the situation you think it is.
 */
public final class ObservableProfile {

    private ObservableProfile() {
    }

    public static void main(final String[] args) throws IOException, InterruptedException {
        if (args.length == 0) {
            System.out.println("usage: <observable url | id | file> [--top=25]");
            return;
        }
        int top = 25;
        for (final String arg : args) {
            if (arg.startsWith("--top=")) {
                top = Integer.parseInt(arg.substring(6));
            }
        }

        final JsonObject root = JsonParser.parseString(load(args[0])).getAsJsonObject();
        // A file saved from the download button is already the {data, diagnostics} envelope;
        // be tolerant of someone having unwrapped it.
        final JsonObject data = root.has("data") ? root.getAsJsonObject("data") : root;
        final double profileTicks = data.get("ticks").getAsDouble();
        System.out.printf("profile ticks: %.0f%n", profileTicks);

        for (final String section : new String[] {"entities", "blocks"}) {
            if (!data.has(section) || data.get(section).isJsonNull()) {
                continue;
            }
            report(section, data.getAsJsonObject(section), profileTicks, top);
        }
    }

    private static void report(final String section, final JsonObject bySection,
        final double profileTicks, final int top) {
        final Map<String, double[]> byType = new HashMap<>();
        final Map<String, String> firstSeen = new HashMap<>();
        double sectionTotal = 0;
        int tracked = 0;

        for (final Map.Entry<String, JsonElement> dimension : bySection.entrySet()) {
            for (final JsonElement element : dimension.getValue().getAsJsonArray()) {
                final JsonObject entry = element.getAsJsonObject();
                final String type = entry.get("type").getAsString();
                final int ticks = entry.get("ticks").getAsInt();
                final double weighted = entry.get("rate").getAsDouble() * ticks / profileTicks;

                final double[] acc = byType.computeIfAbsent(type, k -> new double[2]);
                acc[0] += weighted;
                acc[1]++;
                sectionTotal += weighted;
                tracked++;

                // The first position is enough to walk to: everything expensive in a modded
                // world turns out to be a cluster, so one coordinate finds the whole lot.
                if (entry.has("position") && !firstSeen.containsKey(type)) {
                    final JsonObject at = entry.getAsJsonObject("position");
                    firstSeen.put(type, at.get("x").getAsInt() + " "
                        + at.get("y").getAsInt() + " " + at.get("z").getAsInt());
                }
            }
        }

        final List<Map.Entry<String, double[]>> ranked = new ArrayList<>(byType.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));

        System.out.printf("%n=== %s: %d tracked, %d types, %.2f ms/tick total ===%n",
            section, tracked, byType.size(), sectionTotal / 1_000_000.0);
        System.out.printf("  %8s  %7s  %6s  %6s  %s%n",
            "ms/tick", "share", "count", "each", "type");

        int shown = 0;
        for (final Map.Entry<String, double[]> row : ranked) {
            if (shown++ >= top) {
                break;
            }
            final double total = row.getValue()[0];
            final double count = row.getValue()[1];
            System.out.printf("  %8.3f  %6.2f%%  %6.0f  %6.3f  %-44s  first@ %s%n",
                total / 1_000_000.0, total * 100.0 / sectionTotal, count,
                total / count / 1_000_000.0, row.getKey(),
                firstSeen.getOrDefault(row.getKey(), "?"));
        }
        if (ranked.size() > top) {
            System.out.printf("  ... %d more types not shown (--top=)%n", ranked.size() - top);
        }
    }

    /** Accepts a share URL, a bare id, or a path to a saved export. */
    private static String load(final String source) throws IOException, InterruptedException {
        // Not Files.exists(Path.of(source)) on its own: Path.of throws InvalidPathException on
        // Windows for anything containing a colon, so a plain URL blows up before the check.
        if (!source.startsWith("http")) {
            final Path local = Path.of(source);
            if (Files.exists(local)) {
                return Files.readString(local);
            }
        }
        final String id = source.contains("/")
            ? source.substring(source.lastIndexOf('/') + 1)
            : source;
        final String url = "https://observable.tas.sh/v1/get/" + id;
        System.out.println("fetching " + url);
        try (InputStream in = URI.create(url).toURL().openStream()) {
            final byte[] payload = in.readAllBytes();
            System.out.printf(Locale.ROOT, "payload: %d bytes%n", payload.length);
            return new String(payload, StandardCharsets.UTF_8);
        }
    }
}
