package com.wraithhawit.rstweaks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Inserts a blank line between entries in the generated config file.
 *
 * <p>NightConfig writes every comment line as {@code "#" + line}, so a blank line in a
 * {@code .comment(...)} block comes out as a bare {@code #}. With comments as long as
 * ours the whole file becomes an unbroken wall of {@code #} in which one option runs
 * straight into the next, and there is no way to get a real blank line out of the
 * writer — the only ones it emits are before {@code [section]} headers.
 *
 * <p>So we add them afterwards. This runs on config load, rewrites the file only if it
 * would actually change, and touches nothing but line breaks: TOML ignores blank lines,
 * and no key, value or comment text is altered. After the first pass it is a no-op, so
 * it does not fight the file watcher.
 *
 * <p>The bare {@code #} lines <em>inside</em> a comment block are deliberately left
 * alone. They are what keeps a paragraph attached to the key it documents; if they were
 * blank too, a break within a comment would look exactly like a break between entries.
 *
 * <p>Formatting is cosmetic, so every failure here is swallowed. Losing the blank lines
 * is not a reason to stop the game from loading.
 */
final class ConfigFormatter {
    private ConfigFormatter() {
    }

    static void addBlankLinesBetweenEntries(Path path) {
        try {
            if (path == null || !Files.isRegularFile(path)) {
                return;
            }
            List<String> in = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<String> out = spaced(in);
            if (!out.equals(in)) {
                // Files.write on a UTF_8 charset never emits a byte-order mark, which
                // matters: NightConfig rejects a config that starts with one and
                // silently regenerates the file from defaults.
                Files.write(path, out, StandardCharsets.UTF_8);
                RSTweaks.LOGGER.debug("[rstweaks] spaced out {}", path.getFileName());
            }
        } catch (IOException | RuntimeException e) {
            RSTweaks.LOGGER.warn("[rstweaks] could not reformat config (harmless)", e);
        }
    }

    private static List<String> spaced(List<String> in) {
        List<String> out = new ArrayList<>(in.size() + 32);
        // Depth of unclosed brackets on value lines, so a multi-line array is treated as
        // one entry rather than several. Our own spec has no list values, but the config
        // file is not ours to make assumptions about.
        int depth = 0;
        boolean previousLineEndedAnEntry = false;

        for (String line : in) {
            String trimmed = line.trim();
            boolean blank = trimmed.isEmpty();
            boolean comment = trimmed.startsWith("#");

            boolean startsNewEntry = depth == 0
                && !blank
                && previousLineEndedAnEntry
                && !lastIsBlank(out);
            if (startsNewEntry) {
                out.add("");
            }
            out.add(line);

            if (blank || comment) {
                // A blank line already separates whatever follows; a comment line begins
                // or continues a block that has not reached its key yet.
                previousLineEndedAnEntry = false;
                continue;
            }
            depth = Math.max(0, depth + count(trimmed, '[') - count(trimmed, ']'));
            previousLineEndedAnEntry = depth == 0;
        }
        return out;
    }

    private static boolean lastIsBlank(List<String> out) {
        return out.isEmpty() || out.get(out.size() - 1).trim().isEmpty();
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }
}
