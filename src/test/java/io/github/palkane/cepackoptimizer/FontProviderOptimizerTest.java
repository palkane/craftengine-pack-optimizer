package io.github.palkane.cepackoptimizer;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public final class FontProviderOptimizerTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static JsonObject bitmap(String chars, int height) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "bitmap");
        p.addProperty("file", "test:icons.png");
        p.addProperty("height", height);
        p.addProperty("ascent", 0);
        JsonArray rows = new JsonArray();
        rows.add(chars);
        p.add("chars", rows);
        return p;
    }

    private static JsonArray array(JsonElement... values) {
        JsonArray result = new JsonArray();
        for (JsonElement value : values) result.add(value);
        return result;
    }

    // Independent glyph oracle: ordered candidate definitions, including filters and UV cells.
    private static Map<Integer, List<String>> glyphs(JsonArray providers) {
        Map<Integer, List<String>> result = new TreeMap<>();
        for (JsonElement value : providers) {
            JsonObject p = value.getAsJsonObject();
            if (!p.has("chars")) continue;
            JsonObject settings = p.deepCopy();
            settings.remove("chars");
            JsonArray rows = p.getAsJsonArray("chars");
            Map<Integer, String> local = new HashMap<>();
            for (int y = 0; y < rows.size(); y++) {
                int[] row = rows.get(y).getAsString().codePoints().toArray();
                for (int x = 0; x < row.length; x++)
                    if (row[x] != 0)
                        local.put(row[x], settings + "/" + rows.size() + "/" + row.length + "/" + x + "/" + y);
            }
            local.forEach((code, descriptor) -> result.computeIfAbsent(code, k -> new ArrayList<>()).add(descriptor));
        }
        return result;
    }

    private static JsonArray verify(JsonArray input, int expected) {
        var original = glyphs(input);
        JsonArray merged = FontProviderOptimizer.merge(input);
        check(merged.size() == expected, "wrong provider count: " + merged.size() + " expected " + expected);
        check(glyphs(merged).equals(original), "glyph UV/metrics/filter/priority changed");
        check(FontProviderOptimizer.merge(merged).equals(merged), "merge is not idempotent");
        return merged;
    }

    public static void main(String[] args) throws Exception {
        verify(array(bitmap("a\0", 10), bitmap("\0b", 10)), 1);
        verify(array(bitmap("a\0", 10), bitmap("\0b", 11)), 2);
        verify(array(bitmap("a\0", 10), bitmap("b\0", 10)), 2);
        verify(array(bitmap("a\0", 10), bitmap("a\0", 10), bitmap("\0b", 10)), 3);
        verify(array(bitmap("aa", 10), bitmap("\0b", 10)), 2);
        verify(array(bitmap("a\0", 10), bitmap("\0\0b", 10)), 2);
        verify(array(bitmap("\uD83D\uDE00\0", 10), bitmap("\0\uD83D\uDE01", 10)), 1);

        JsonObject filtered = bitmap("\0b", 10);
        filtered.add("filter", JsonParser.parseString("{\"uniform\":true}"));
        verify(array(bitmap("a\0", 10), filtered), 2);
        JsonObject sameFilter = bitmap("a\0", 10);
        sameFilter.add("filter", filtered.get("filter").deepCopy());
        verify(array(sameFilter, filtered), 1);
        JsonObject unknownSetting = bitmap("\0b", 10);
        unknownSetting.addProperty("future-field", 42);
        verify(array(bitmap("a\0", 10), unknownSetting), 2);

        JsonObject reference = JsonParser.parseString("{\"type\":\"reference\",\"id\":\"test:fallback\"}").getAsJsonObject();
        JsonArray barriers = verify(array(bitmap("a\0", 10), reference, bitmap("\0b", 10)), 3);
        check(barriers.get(1).equals(reference), "reference provider changed");
        JsonObject empty = bitmap("\0\0", 10);
        verify(array(bitmap("a\0", 10), empty, bitmap("\0b", 10)), 3);

        // A duplicate codepoint separated by another bitmap must retain fallback priority.
        verify(array(bitmap("a\0\0", 10), bitmap("\0b\0", 10), bitmap("\0\0a", 12)), 3);
        verify(array(bitmap("a\0\0", 10), bitmap("\0b\0", 10), bitmap("\0\0c", 12)), 2);

        Path root = Files.createTempDirectory("ce-font-merge-");
        try {
            Path font = root.resolve("assets/dialogmenu/font/sprites.json");
            Path untouched = root.resolve("assets/other/font/sprites.json");
            Files.createDirectories(font.getParent());
            Files.createDirectories(untouched.getParent());
            JsonObject doc = new JsonObject();
            doc.addProperty("custom-root", "preserve");
            doc.add("providers", array(bitmap("a\0", 10), bitmap("\0b", 10)));
            Files.writeString(font, doc.toString());
            Files.writeString(untouched, doc.toString());
            Path image = root.resolve("assets/dialogmenu/textures/icons.png");
            Files.createDirectories(image.getParent());
            Files.write(image, new byte[] {1, 2, 3});
            var result = FontProviderOptimizer.optimize(root);
            check(result.providersBefore() == 2 && result.providersAfter() == 1, "target font not merged");
            check(Files.readString(untouched).equals(doc.toString()), "unrelated font changed");
            check(Arrays.equals(Files.readAllBytes(image), new byte[] {1, 2, 3}), "texture changed");
            check(JsonParser.parseString(Files.readString(font)).getAsJsonObject()
                    .get("custom-root").getAsString().equals("preserve"), "root metadata lost");
            byte[] once = Files.readAllBytes(font);
            check(FontProviderOptimizer.optimize(root).fontsChanged() == 0, "second run rewrote font");
            check(Arrays.equals(once, Files.readAllBytes(font)), "unchanged font was rewritten");

            Files.writeString(font, doc.toString());
            Path overlay = root.resolve("overlay/assets/dialogmenu/font/sprites.json");
            Files.createDirectories(overlay.getParent());
            Files.writeString(overlay, "malformed");
            boolean rejected = false;
            try { FontProviderOptimizer.optimize(root); }
            catch (RuntimeException e) { rejected = true; }
            check(rejected, "malformed overlay did not abort");
            check(Files.readString(font).equals(doc.toString()), "partial writes before parsing all targets");

            Files.writeString(overlay, doc.toString());
            check(FontProviderOptimizer.optimize(root).fontsChanged() == 2, "overlay font not independently merged");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Font provider checks passed: " + checks);
    }
}
