package kaermorhen;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;

/** Theme colours and custom-painted components (Witcher 2 dark / blood-red style). */
final class Ui {

    static final Color RED        = new Color(178, 24, 30);
    static final Color RED_BRIGHT = new Color(220, 45, 50);
    static final Color TEXT       = new Color(226, 222, 214);
    static final Color TEXT_DIM   = new Color(150, 146, 140);
    static final Color GLASS      = new Color(8, 10, 12, 170);
    static final Color GLASS_EDGE = new Color(255, 255, 255, 28);
    static final Color FIELD_BG   = new Color(18, 20, 22);

    static final Font FONT       = pickFont(Font.PLAIN, 13);
    static final Font FONT_BOLD  = pickFont(Font.BOLD, 13);
    static final Font FONT_SMALL = pickFont(Font.PLAIN, 11);

    private Ui() { }

    private static String family;

    static Font pickFont(int style, int size) {
        if (family == null) {
            family = Font.SANS_SERIF;
            String[] names = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
            outer:
            for (String want : new String[]{"Segoe UI", "Noto Sans", "DejaVu Sans"}) {
                for (String n : names) if (n.equalsIgnoreCase(want)) { family = n; break outer; }
            }
        }
        return new Font(family, style, size);
    }

    static JLabel label(String text, Font f, Color c) {
        JLabel l = new JLabel(text);
        l.setFont(f);
        l.setForeground(c);
        return l;
    }

    static void hq(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    /** High-quality resize keeping aspect ratio to fit inside w x h (progressive steps for big upscales). */
    static BufferedImage fit(BufferedImage src, int w, int h) {
        if (src == null || w <= 0 || h <= 0) return null;
        double s = Math.min(w / (double) src.getWidth(), h / (double) src.getHeight());
        int nw = Math.max(1, (int) Math.round(src.getWidth() * s));
        int nh = Math.max(1, (int) Math.round(src.getHeight() * s));
        BufferedImage cur = src;
        while (cur.getWidth() * 2 < nw) {               // small thumbnails: double size in steps
            cur = resize(cur, cur.getWidth() * 2, cur.getHeight() * 2);
        }
        return resize(cur, nw, nh);
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        hq(g);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    // ------------------------------------------------------------------ background

    /**
     * Root panel: paints the background image with a dim overlay.
     * An optional crop (fractions x, y, w, h of the image) selects which part is shown;
     * that part is then scaled to cover the whole window.
     */
    static final class BackgroundPanel extends JPanel {
        private BufferedImage image;
        private double[] crop;
        private BufferedImage cache;
        private int dimPercent = 45;

        BackgroundPanel(LayoutManager lm) { super(lm); setBackground(Color.BLACK); }

        void setImage(BufferedImage img, double[] crop) { image = img; this.crop = crop; cache = null; repaint(); }
        void setDim(int percent) { dimPercent = percent; repaint(); }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            int w = getWidth(), h = getHeight();
            if (image != null && w > 0 && h > 0) {
                if (cache == null || cache.getWidth() != w || cache.getHeight() != h) {
                    cache = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    Graphics2D cg = cache.createGraphics();
                    hq(cg);
                    drawCover(cg, image, crop, w, h);
                    cg.dispose();
                }
                g.drawImage(cache, 0, 0, null);
            }
            g.setColor(new Color(0, 0, 0, Math.min(255, dimPercent * 255 / 100)));
            g.fillRect(0, 0, w, h);
            g.dispose();
        }
    }

    /** Draws the (cropped) image so it covers w x h, centred. */
    static void drawCover(Graphics2D g, BufferedImage img, double[] crop, int w, int h) {
        double sx = 0, sy = 0, sw = img.getWidth(), sh = img.getHeight();
        if (crop != null && crop.length == 4) {
            sx = crop[0] * img.getWidth();
            sy = crop[1] * img.getHeight();
            sw = Math.max(1, crop[2] * img.getWidth());
            sh = Math.max(1, crop[3] * img.getHeight());
        }
        double s = Math.max(w / sw, h / sh);
        double dw = sw * s, dh = sh * s;
        int dx = (int) Math.round((w - dw) / 2), dy = (int) Math.round((h - dh) / 2);
        g.drawImage(img, dx, dy, dx + (int) Math.round(dw), dy + (int) Math.round(dh),
                (int) Math.round(sx), (int) Math.round(sy), (int) Math.round(sx + sw), (int) Math.round(sy + sh), null);
    }

    static double[] parseCrop(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            String[] p = s.split(",");
            double[] c = new double[4];
            for (int i = 0; i < 4; i++) c[i] = Double.parseDouble(p[i].trim());
            if (c[2] <= 0 || c[3] <= 0) return null;
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    static String cropToString(double[] c) {
        if (c == null) return null;
        return String.format(java.util.Locale.US, "%.6f,%.6f,%.6f,%.6f", c[0], c[1], c[2], c[3]);
    }

    static BufferedImage loadBackground(String customPath) {
        try {
            if (customPath != null && !customPath.isEmpty()) {
                File f = new File(customPath);
                if (f.isFile()) {
                    BufferedImage img = ImageIO.read(f);
                    if (img != null) return img;
                }
            }
            try (InputStream in = Ui.class.getResourceAsStream("/kaermorhen/res/background.png")) {
                if (in != null) return ImageIO.read(in);
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ------------------------------------------------------------------ glass panel

    /** Translucent rounded panel that lets the background show through. */
    static class GlassPanel extends JPanel {
        GlassPanel(LayoutManager lm) {
            super(lm);
            setOpaque(false);
            setBorder(new EmptyBorder(12, 14, 12, 14));
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            hq(g);
            g.setColor(GLASS);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g.setColor(GLASS_EDGE);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g.dispose();
            super.paintComponent(g0);
        }
    }

    // ------------------------------------------------------------------ button

    static final class WButton extends JButton {
        private boolean hover, danger;

        WButton(String text) { this(text, false); }

        WButton(String text, boolean danger) {
            super(text);
            this.danger = danger;
            setFont(FONT_BOLD);
            setForeground(TEXT);
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(8, 18, 8, 18));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            hq(g);
            int w = getWidth(), h = getHeight();
            boolean pressed = getModel().isPressed();
            Color top, bottom;
            if (!isEnabled())      { top = new Color(40, 40, 40, 200); bottom = new Color(25, 25, 25, 200); }
            else if (danger)       { top = hover ? new Color(170, 25, 30) : new Color(120, 16, 20);
                                     bottom = hover ? new Color(110, 10, 14) : new Color(70, 8, 10); }
            else                   { top = hover ? new Color(62, 58, 56) : new Color(42, 40, 40);
                                     bottom = hover ? new Color(36, 32, 32) : new Color(22, 20, 20); }
            if (pressed) { Color t = top; top = bottom; bottom = t; }
            g.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
            g.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);
            g.setColor(hover && isEnabled() ? RED_BRIGHT : new Color(120, 30, 32));
            g.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
            g.dispose();
            setForeground(isEnabled() ? TEXT : TEXT_DIM);
            super.paintComponent(g0);
        }
    }

    // ------------------------------------------------------------------ scrollbar

    static final class DarkScrollBarUI extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() {
            thumbColor = new Color(110, 28, 30);
            trackColor = new Color(0, 0, 0, 90);
        }
        @Override protected JButton createDecreaseButton(int o) { return zero(); }
        @Override protected JButton createIncreaseButton(int o) { return zero(); }
        private static JButton zero() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(trackColor);
            g.fillRect(r.x, r.y, r.width, r.height);
        }
        @Override protected void paintThumb(Graphics g0, JComponent c, Rectangle r) {
            if (r.isEmpty()) return;
            Graphics2D g = (Graphics2D) g0.create();
            hq(g);
            g.setColor(isThumbRollover() ? RED : thumbColor);
            g.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 8, 8);
            g.dispose();
        }
    }
}
