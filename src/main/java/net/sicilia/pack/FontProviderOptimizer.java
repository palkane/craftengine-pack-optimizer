package net.sicilia.pack;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Combines sparse bitmap grids without changing textures or glyph identities. */
public final class FontProviderOptimizer {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String FONT = "assets/dialogmenu/font/sprites.json";

    public record Result(int fontsChecked, int fontsChanged, int providersBefore, int providersAfter) {
        public String summary() {
            return "font-only merge: fonts=" + fontsChanged + "/" + fontsChecked
                    + ", providers " + providersBefore + " -> " + providersAfter;
        }
    }

    private record Grid(JsonObject settings, int[][] cells, Set<Integer> codes) {}
    private record Pending(Path path, String json) {}
    private record Group(JsonObject provider, Grid grid) {}

    public static Result optimize(Path root) throws IOException {
        List<Path> fonts;
        try (var stream = Files.walk(root)) {
            fonts = stream.filter(Files::isRegularFile).filter(path -> {
                String name = root.relativize(path).toString().replace('\\', '/');
                return name.equals(FONT) || name.endsWith("/" + FONT);
            }).sorted().toList();
        }
        int changed = 0, before = 0, after = 0;
        List<Pending> writes = new ArrayList<>();
        for (Path path : fonts) {
            JsonObject document = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            JsonArray providers = document.getAsJsonArray("providers");
            if (providers == null) throw new IOException("Font has no providers: " + path);
            JsonArray merged = merge(providers);
            before += providers.size();
            after += merged.size();
            if (merged.size() != providers.size()) {
                document.add("providers", merged);
                writes.add(new Pending(path, JSON.toJson(document)));
                changed++;
            }
        }
        // Parse and plan every target before touching CE's temporary tree.
        for (Pending write : writes) Files.writeString(write.path(), write.json());
        return new Result(fonts.size(), changed, before, after);
    }

    static JsonArray merge(JsonArray providers) {
        List<Grid> grids = new ArrayList<>();
        Map<Integer, Integer> occurrences = new HashMap<>();
        for (JsonElement provider : providers) {
            Grid grid = grid(provider);
            grids.add(grid);
            if (grid != null) for (int code : grid.codes()) occurrences.merge(code, 1, Integer::sum);
        }
        JsonArray result = new JsonArray();
        List<Group> groups = new ArrayList<>();
        for (int index = 0; index < providers.size(); index++) {
            JsonElement provider = providers.get(index);
            Grid grid = grids.get(index);
            if (grid == null || grid.codes().isEmpty()
                    || grid.codes().stream().anyMatch(code -> occurrences.get(code) != 1)) {
                // Unknown providers and repeated codepoints are precedence barriers.
                groups.clear();
                result.add(provider.deepCopy());
                continue;
            }
            Group target = null;
            for (Group group : groups) {
                if (compatible(group.grid(), grid)) {
                    target = group;
                    break;
                }
            }
            if (target == null) {
                JsonObject copy = provider.getAsJsonObject().deepCopy();
                result.add(copy);
                groups.add(new Group(copy, grid));
            } else {
                int[][] cells = target.grid().cells();
                for (int row = 0; row < cells.length; row++)
                    for (int column = 0; column < cells[row].length; column++)
                        if (grid.cells()[row][column] != 0) cells[row][column] = grid.cells()[row][column];
                JsonArray chars = new JsonArray();
                for (int[] row : cells) chars.add(new String(row, 0, row.length));
                target.provider().add("chars", chars);
            }
        }
        return result;
    }

    private static boolean compatible(Grid a, Grid b) {
        if (!a.settings().equals(b.settings()) || a.cells().length != b.cells().length) return false;
        for (int row = 0; row < a.cells().length; row++) {
            if (a.cells()[row].length != b.cells()[row].length) return false;
            for (int column = 0; column < a.cells()[row].length; column++)
                if (a.cells()[row][column] != 0 && b.cells()[row][column] != 0) return false;
        }
        return true;
    }

    private static Grid grid(JsonElement value) {
        if (!value.isJsonObject()) return null;
        JsonObject provider = value.getAsJsonObject();
        if (!provider.has("type") || !provider.get("type").isJsonPrimitive()
                || !Set.of("bitmap", "minecraft:bitmap").contains(provider.get("type").getAsString())
                || !provider.has("file") || !provider.has("ascent")
                || !provider.has("chars") || !provider.get("chars").isJsonArray()) return null;
        JsonArray chars = provider.getAsJsonArray("chars");
        if (chars.isEmpty()) return null;
        int[][] cells = new int[chars.size()][];
        Set<Integer> codes = new HashSet<>();
        for (int row = 0; row < cells.length; row++) {
            JsonElement line = chars.get(row);
            if (!line.isJsonPrimitive() || !line.getAsJsonPrimitive().isString()) return null;
            cells[row] = line.getAsString().codePoints().toArray();
            if (cells[row].length == 0 || cells[row].length != cells[0].length) return null;
            for (int code : cells[row]) if (code != 0 && !codes.add(code)) return null;
        }
        JsonObject settings = provider.deepCopy();
        settings.remove("chars");
        return new Grid(settings, cells, codes);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Usage: <extracted-copy> <report.json>");
        Result result = optimize(Path.of(args[0]));
        Files.writeString(Path.of(args[1]), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        System.out.println(result.summary());
    }
}
