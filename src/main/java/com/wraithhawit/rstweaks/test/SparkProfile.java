package com.wraithhawit.rstweaks.test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a spark profile from the command line, so a lag report can be answered without opening a
 * browser or asking the reporter to read numbers back.
 *
 * <pre>{@code   ./gradlew sparkProfile --args="https://spark.lucko.me/abc123"
 *   ./gradlew sparkProfile --args="abc123 --thread=Server --top=40 --grep=refinedstorage" }</pre>
 *
 * <p>A spark share link is a protobuf payload on bytebin, not a web page, so it can be fetched and
 * decoded directly. The schema is not published as a Java artifact and had to be read off the
 * bytes, which is the entire reason this file exists: it took a long afternoon once and should
 * never take one again.
 *
 * <h2>The schema, as observed on spark's 1.10-era sampler payloads</h2>
 *
 * <pre>
 *   SamplerData    { metadata = 1, threads = 2 }
 *   ThreadNode     { name = 1 (string), children = 3, times = 8 }
 *   StackTraceNode { children = 2, class_name = 3, method_name = 4,
 *                    parent_line = 5, line = 6, method_desc = 7, times = 8 }
 * </pre>
 *
 * <p><b>{@code times} is {@code repeated double [packed]}</b> — one entry per sampling window, so
 * a node's time is their sum. It is emphatically not a {@code map<int32,double>}; assuming that
 * made every frame read as zero, which looks exactly like a working parser on an idle server.
 *
 * <h2>What this deliberately does not report</h2>
 *
 * <p><b>Self time.</b> Spark stores the call tree by reference rather than purely by nesting, so an
 * inline walk cannot reliably subtract a node's children from it. Every number here is therefore
 * <em>inclusive</em>: a frame's time includes everything it called. That is enough to answer "what
 * owns the tick" — {@code tickBlockEntities} at 73% and one mod's ticker at 39% is the finding —
 * but it means times do not sum to 100% and a parent always outranks its children. Do not add
 * these up. Resolving the references would be the way to get self time, if it is ever worth it.
 */
public final class SparkProfile {
    private static byte[] data;
    private int pos;

    private static final Map<String, Double> FRAMES = new HashMap<>();
    private static final Map<String, Double> THREADS = new HashMap<>();
    private static String currentThread = "?";
    private static String wantedThread = "Server thread";
    private static int failures;

    private SparkProfile(final int pos) {
        this.pos = pos;
    }

    private static final class Desync extends RuntimeException {
        Desync(final String why) {
            super(why);
        }
    }

    public static void main(final String[] args) throws IOException, InterruptedException {
        if (args.length == 0) {
            System.out.println("usage: <spark url | bytebin code | file> "
                + "[--thread=Server] [--top=30] [--grep=text]");
            return;
        }
        int top = 30;
        String grep = null;
        for (final String arg : args) {
            if (arg.startsWith("--thread=")) {
                wantedThread = arg.substring(9);
            } else if (arg.startsWith("--top=")) {
                top = Integer.parseInt(arg.substring(6));
            } else if (arg.startsWith("--grep=")) {
                grep = arg.substring(7).toLowerCase(Locale.ROOT);
            }
        }

        data = load(args[0]);
        System.out.println("payload: " + data.length + " bytes");
        final SparkProfile reader = new SparkProfile(0);
        try {
            reader.readSamplerData(data.length);
        } catch (final RuntimeException e) {
            System.out.println("parse stopped at byte " + reader.pos + ": " + e);
        }
        if (failures > 0) {
            System.out.println("subtrees skipped: " + failures);
        }

        System.out.println();
        System.out.println("threads sampled: " + THREADS.size()
            + " (showing frames for those starting with \"" + wantedThread + "\")");

        final List<Map.Entry<String, Double>> frames = new ArrayList<>(FRAMES.entrySet());
        frames.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        if (frames.isEmpty()) {
            System.out.println("no frames matched that thread -- try --thread= with a prefix "
                + "of the thread you want.");
            return;
        }

        // The busiest frame on a thread is the thread's own entry point, so it doubles as the
        // denominator: every percentage below is a share of that thread's samples.
        final double total = frames.getFirst().getValue();
        System.out.println();
        System.out.printf("=== top %d frames by INCLUSIVE time (%% of thread) ===%n", top);
        int shown = 0;
        for (final Map.Entry<String, Double> frame : frames) {
            if (grep != null && !frame.getKey().toLowerCase(Locale.ROOT).contains(grep)) {
                continue;
            }
            if (shown++ >= top) {
                break;
            }
            System.out.printf("  %6.2f%%  %12.0f  %s%n",
                frame.getValue() * 100.0 / total, frame.getValue(), frame.getKey());
        }
    }

    /** Accepts a share link, the bare code from one, or a path to an already-downloaded payload. */
    private static byte[] load(final String source) throws IOException, InterruptedException {
        // Guarded: on Windows a URL is not a legal path at all, and Path.of throws before
        // isRegularFile ever gets a chance to say no.
        try {
            final Path local = Path.of(source);
            if (Files.isRegularFile(local)) {
                return Files.readAllBytes(local);
            }
        } catch (final java.nio.file.InvalidPathException notAFile) {
            // Fall through to treating it as a link.
        }
        final String code = source.contains("/")
            ? source.substring(source.lastIndexOf('/') + 1)
            : source;
        final Path cache = Path.of(System.getProperty("java.io.tmpdir"), "spark-" + code + ".bin");
        if (Files.isRegularFile(cache)) {
            System.out.println("using cached " + cache);
            return Files.readAllBytes(cache);
        }
        final URI uri = URI.create("https://bytebin.lucko.me/" + code);
        System.out.println("fetching " + uri);
        try (InputStream in = uri.toURL().openStream()) {
            final byte[] bytes = in.readAllBytes();
            Files.write(cache, bytes);
            return bytes;
        }
    }

    private void readSamplerData(final int end) {
        while (this.pos < end) {
            final long key = varint();
            final int field = (int) (key >>> 3);
            final int wire = (int) (key & 7);
            if (field == 2 && wire == 2) {
                final int len = (int) varint();
                final int stop = this.pos + len;
                try {
                    readThread(stop);
                } catch (final RuntimeException e) {
                    failures++;
                }
                this.pos = stop;
            } else {
                skip(wire);
            }
        }
    }

    private void readThread(final int end) {
        String name = "?";
        double time = 0;
        while (this.pos < end) {
            final long key = varint();
            final int field = (int) (key >>> 3);
            final int wire = (int) (key & 7);
            if (field == 1 && wire == 2) {
                name = str();
                currentThread = name;
            } else if (field == 3 && wire == 2) {
                final int len = (int) varint();
                final int stop = this.pos + len;
                try {
                    readNode(stop);
                } catch (final RuntimeException e) {
                    failures++;
                }
                this.pos = stop;
            } else if (field == 8 && wire == 2) {
                time += packedDoubles();
            } else {
                skip(wire);
            }
        }
        THREADS.merge(name, time, Double::sum);
    }

    private double readNode(final int end) {
        double time = 0;
        String cls = "";
        String method = "";
        while (this.pos < end) {
            final long key = varint();
            final int field = (int) (key >>> 3);
            final int wire = (int) (key & 7);
            if (field == 2 && wire == 2) {
                final int len = (int) varint();
                final int stop = this.pos + len;
                try {
                    readNode(stop);
                } catch (final RuntimeException e) {
                    failures++;
                }
                this.pos = stop;
            } else if (field == 3 && wire == 2) {
                cls = str();
            } else if (field == 4 && wire == 2) {
                method = str();
            } else if (field == 1 && wire == 1) {
                time += Double.longBitsToDouble(fixed64());
            } else if (field == 8 && wire == 2) {
                time += packedDoubles();
            } else {
                skip(wire);
            }
        }
        final String name = cls.isEmpty() ? method : cls + "." + method;
        if (!name.isEmpty() && currentThread.startsWith(wantedThread)) {
            FRAMES.merge(name, time, Double::sum);
        }
        return time;
    }

    /** One sample window per double; a node's time is their sum. */
    private double packedDoubles() {
        final int len = (int) varint();
        final int stop = this.pos + len;
        double total = 0;
        while (this.pos + 8 <= stop) {
            total += Double.longBitsToDouble(fixed64());
        }
        this.pos = stop;
        return total;
    }

    private long varint() {
        long result = 0;
        int shift = 0;
        while (true) {
            final byte next = data[this.pos++];
            result |= (long) (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 63) {
                throw new Desync("varint too long");
            }
        }
    }

    private long fixed64() {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value |= (long) (data[this.pos + i] & 0xFF) << (8 * i);
        }
        this.pos += 8;
        return value;
    }

    private String str() {
        final int len = (int) varint();
        if (len < 0 || this.pos + len > data.length) {
            throw new Desync("bad string length " + len);
        }
        final String s = new String(data, this.pos, len, StandardCharsets.UTF_8);
        this.pos += len;
        return s;
    }

    private void skip(final int wire) {
        switch (wire) {
            case 0 -> varint();
            case 1 -> this.pos += 8;
            // Not `this.pos += (int) varint()`. Java evaluates the left operand first, so that
            // adds the length to the position from *before* the length varint was read, and every
            // skip lands short by a byte or three. It parses happily for a while and then desyncs
            // somewhere unrelated, which is a miserable thing to debug.
            case 2 -> {
                final int len = (int) varint();
                this.pos += len;
            }
            case 5 -> this.pos += 4;
            default -> throw new Desync("wire type " + wire);
        }
    }
}
