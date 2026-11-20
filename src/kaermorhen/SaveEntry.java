package kaermorhen;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One save game: the save file plus its sidecar files.
 *   W1: "000749 - Kaer Morhen Valley-204.TheWitcherSave" (thumbnail embedded)
 *   W2: "ManualSave_0012.sav" + "ManualSave_0012_640x360.bmp"
 *   W3: "QuickSave_0_0001.sav" + ".png" (+ ".json" on newer versions)
 */
final class SaveEntry {

    final Game game;
    final Path saveFile;
    final List<Path> extras;   // sidecar files that belong to this save
    final Path screenshot;     // external screenshot, may be null
    final String name;
    final long modified;
    final long saveSize;
    final long totalSize;

    private SaveEntry(Game game, Path saveFile, List<Path> extras, Path screenshot,
                      long modified, long saveSize, long totalSize) {
        this.game = game;
        this.saveFile = saveFile;
        this.extras = extras;
        this.screenshot = screenshot;
        this.name = saveFile.getFileName().toString();
        this.modified = modified;
        this.saveSize = saveSize;
        this.totalSize = totalSize;
    }

    List<Path> files() {
        List<Path> list = new ArrayList<>();
        list.add(saveFile);
        list.addAll(extras);
        return list;
    }

    boolean hasPreview() { return screenshot != null || game.embeddedPreview; }

    static List<SaveEntry> scan(Path dir, Game game) throws IOException {
        List<SaveEntry> result = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return result;

        Map<String, Path> files = new HashMap<>();
        List<Path> saves = new ArrayList<>();
        String ext = game.saveExt.toLowerCase(Locale.ROOT);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                files.put(n, p);
                if (n.endsWith(ext)) saves.add(p);
            }
        }

        for (Path sav : saves) {
            String fn = sav.getFileName().toString();
            String base = fn.substring(0, fn.length() - game.saveExt.length()).toLowerCase(Locale.ROOT);
            List<Path> extras = new ArrayList<>();
            for (String suffix : game.sidecars) {
                Path p = files.get(base + suffix.toLowerCase(Locale.ROOT));
                if (p != null) extras.add(p);
            }
            if (game == Game.W2 && extras.isEmpty()) {       // other screenshot resolutions
                for (Map.Entry<String, Path> e : files.entrySet()) {
                    if (e.getKey().startsWith(base + "_") && isImage(e.getKey())) { extras.add(e.getValue()); break; }
                }
            }
            Path shot = null;
            for (Path p : extras) if (isImage(p.getFileName().toString().toLowerCase(Locale.ROOT))) { shot = p; break; }

            long size = Files.size(sav);
            long total = size;
            for (Path p : extras) {
                try { total += Files.size(p); } catch (IOException ignored) { }
            }
            result.add(new SaveEntry(game, sav, extras, shot, Files.getLastModifiedTime(sav).toMillis(), size, total));
        }
        result.sort((a, b) -> Long.compare(b.modified, a.modified));
        return result;
    }

    private static boolean isImage(String lower) {
        return lower.endsWith(".bmp") || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
