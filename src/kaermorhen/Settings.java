package kaermorhen;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** Portable settings, stored next to the .jar as KaerMorhenSaveVault.properties. */
final class Settings {

    static final Path APP_DIR = findAppDir();
    private static final Path FILE = APP_DIR.resolve("KaerMorhenSaveVault.properties");
    private final Properties props = new Properties();

    Settings() {
        if (Files.isRegularFile(FILE)) {
            try (InputStream in = Files.newInputStream(FILE)) { props.load(in); } catch (Exception ignored) { }
        }
    }

    String get(String key, String def) { return props.getProperty(key, def); }

    int getInt(String key, int def) {
        try { return Integer.parseInt(props.getProperty(key)); } catch (Exception e) { return def; }
    }

    void set(String key, String value) {
        if (value == null) props.remove(key); else props.setProperty(key, value);
        save();
    }

    private void save() {
        try (OutputStream out = Files.newOutputStream(FILE)) {
            props.store(out, "Kaer Morhen Save Vault settings");
        } catch (Exception ignored) { }
    }

    private static Path findAppDir() {
        try {
            URI uri = Settings.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path p = Paths.get(uri);

            if (Files.isRegularFile(p)) p = p.getParent();     // running from .jar
            else if (p.getFileName() != null && p.getFileName().toString().equals("build")) p = p.getParent();
            return p;
        } catch (Exception e) {
            return Paths.get(System.getProperty("user.dir"));
        }
    }
}
