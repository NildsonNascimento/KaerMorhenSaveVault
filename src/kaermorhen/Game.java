package kaermorhen;

import javax.swing.filechooser.FileSystemView;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The three games, their save formats and default save locations. */
enum Game {

    W1("w1", "THE WITCHER 1", "The Witcher 1", ".TheWitcherSave", new String[]{}, true),
    W2("w2", "THE WITCHER 2", "The Witcher 2", ".sav", new String[]{"_640x360.bmp"}, false),
    W3("w3", "THE WITCHER 3", "The Witcher 3", ".sav", new String[]{".png", ".json"}, false);

    final String id;
    final String tabTitle;
    final String displayName;
    final String saveExt;          // save file extension
    final String[] sidecars;       // suffixes appended to the save's base name (screenshot, metadata)
    final boolean embeddedPreview; // W1 stores the thumbnail inside the save file

    Game(String id, String tabTitle, String displayName, String saveExt, String[] sidecars, boolean embeddedPreview) {
        this.id = id;
        this.tabTitle = tabTitle;
        this.displayName = displayName;
        this.saveExt = saveExt;
        this.sidecars = sidecars;
        this.embeddedPreview = embeddedPreview;
    }

    String directoryLabel() { return displayName + " SaveGame Directory:"; }

    String defaultHint() {
        switch (this) {
            case W1: return "Documents\\The Witcher\\saves";
            case W2: return "Steam\\userdata\\<id>\\20920\\remote  (Steam Cloud)  or  Documents\\Witcher 2\\gamesaves";
            default: return "Documents\\The Witcher 3\\gamesaves";
        }
    }

    /** Finds the default save directory for this game, or null. */
    Path autoDetect() {
        if (this == W2) {
            Path steam = detectW2SteamCloud();
            if (steam != null) return steam;
        }
        String[] subs;
        switch (this) {
            case W1: subs = new String[]{"The Witcher" + File.separator + "saves"}; break;
            case W2: subs = new String[]{"Witcher 2" + File.separator + "gamesaves",
                                         "The Witcher 2" + File.separator + "gamesaves"}; break;
            default: subs = new String[]{"The Witcher 3" + File.separator + "gamesaves"}; break;
        }
        for (Path docs : documentFolders()) {
            for (String s : subs) {
                Path p = docs.resolve(s);
                if (Files.isDirectory(p)) return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static List<Path> documentFolders() {
        Set<Path> out = new LinkedHashSet<>();
        try {
            File d = FileSystemView.getFileSystemView().getDefaultDirectory(); // real "Documents" on Windows
            if (d != null && d.isDirectory()) out.add(d.toPath());
        } catch (Exception ignored) { }
        String home = System.getProperty("user.home");
        for (String d : new String[]{"Documents", "Documentos", "My Documents",
                "OneDrive" + File.separator + "Documents", "OneDrive" + File.separator + "Documentos"}) {
            Path p = Paths.get(home, d);
            if (Files.isDirectory(p)) out.add(p);
        }
        return new ArrayList<>(out);
    }

    /** Steam Cloud location: Steam\\userdata\\<accountId>\\20920\\remote (newest one wins). */
    private static Path detectW2SteamCloud() {
        Set<Path> roots = new LinkedHashSet<>();
        String reg = steamPathFromRegistry();
        if (reg != null) roots.add(Paths.get(reg));
        roots.add(Paths.get("C:\\Program Files (x86)\\Steam"));
        roots.add(Paths.get("C:\\Program Files\\Steam"));

        Path best = null;
        long bestTime = Long.MIN_VALUE;
        for (Path root : roots) {
            Path userdata = root.resolve("userdata");
            if (!Files.isDirectory(userdata)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(userdata)) {
                for (Path account : ds) {
                    Path remote = account.resolve("20920").resolve("remote");
                    if (!Files.isDirectory(remote)) continue;
                    Path dir = remote;
                    if (!containsSaves(remote, ".sav") && containsSaves(remote.resolve("gamesaves"), ".sav")) {
                        dir = remote.resolve("gamesaves");
                    }
                    long t = Files.getLastModifiedTime(dir).toMillis();
                    if (t > bestTime) { bestTime = t; best = dir; }
                }
            } catch (Exception ignored) { }
        }
        return best;
    }

    private static boolean containsSaves(Path dir, String ext) {
        if (!Files.isDirectory(dir)) return false;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ext)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    private static String steamPathFromRegistry() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return null;
        try {
            Process p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath")
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int i = line.indexOf("REG_SZ");
                    if (i >= 0) return line.substring(i + 6).trim().replace('/', '\\');
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
