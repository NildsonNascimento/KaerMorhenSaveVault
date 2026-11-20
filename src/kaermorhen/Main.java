package kaermorhen;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/** Kaer Morhen Save Vault - save game manager for The Witcher 1, 2 and 3. */
public final class Main extends JFrame {

    static final String APP_NAME = "Kaer Morhen Save Vault";
    static final String APP_TITLE = APP_NAME + " - The Witcher Trilogy Save Manager";

    private final Settings settings = new Settings();
    private final Ui.BackgroundPanel root = new Ui.BackgroundPanel(new BorderLayout(0, 12));
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final Map<Game, GameTab> tabs = new EnumMap<>(Game.class);
    private final Map<Game, TabButton> tabButtons = new EnumMap<>(Game.class);
    private final Map<Game, BufferedImage> bgCache = new EnumMap<>(Game.class);
    private final JLabel status = Ui.label("Ready.", Ui.FONT_SMALL, Ui.TEXT_DIM);
    private final JSlider dim = new JSlider(0, 90, 45);
    private Game current;

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> new Main().setVisible(true));
    }

    private Main() {
        super(APP_TITLE);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setIconImage(makeIcon());
        setMinimumSize(new Dimension(1150, 720));
        setSize(1360, 840);
        setLocationRelativeTo(null);

        root.setBorder(new EmptyBorder(14, 14, 10, 14));
        setContentPane(root);

        cardPanel.setOpaque(false);
        for (Game g : Game.values()) {
            GameTab tab = new GameTab(g, settings, status::setText, () -> updateTabLabel(g));
            tabs.put(g, tab);
            cardPanel.add(tab, g.id);
        }

        JPanel top = new JPanel(new BorderLayout(0, 10));
        top.setOpaque(false);
        top.add(buildHeader(), BorderLayout.NORTH);
        top.add(buildTabBar(), BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);
        root.add(cardPanel, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(status, BorderLayout.WEST);
        south.add(Ui.label("Del = delete  |  F5 = refresh  |  Ctrl+A = select all  |  Ctrl+1/2/3 = switch game",
                Ui.FONT_SMALL, Ui.TEXT_DIM), BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);

        JRootPane rp = getRootPane();
        rp.registerKeyboardAction(e -> tabs.get(current).refresh(), KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        for (Game g : Game.values()) {
            rp.registerKeyboardAction(e -> select(g),
                    KeyStroke.getKeyStroke(KeyEvent.VK_1 + g.ordinal(), KeyEvent.CTRL_DOWN_MASK),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);
        }

        Game start = Game.W2;
        try { start = Game.valueOf(settings.get("lastTab", "W2")); } catch (Exception ignored) { }
        select(start);
        // load the other tabs in the background so the tab counters are filled in
        SwingUtilities.invokeLater(() -> {
            for (GameTab t : tabs.values()) if (t.game != current) t.ensureLoaded();
            tabs.get(current).refresh();
        });
    }

    // =================================================================== header & tabs

    private JComponent buildHeader() {
        Ui.GlassPanel p = new Ui.GlassPanel(new BorderLayout());

        JPanel title = new JPanel();
        title.setOpaque(false);
        title.setLayout(new BoxLayout(title, BoxLayout.Y_AXIS));
        title.add(Ui.label("KAER MORHEN SAVE VAULT", Ui.pickFont(Font.BOLD, 26), Ui.RED_BRIGHT));
        title.add(Ui.label("THE WITCHER TRILOGY  •  SAVE GAME MANAGER", Ui.pickFont(Font.BOLD, 13), Ui.TEXT));
        p.add(title, BorderLayout.WEST);

        JPanel bg = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        bg.setOpaque(false);
        bg.add(Ui.label("Background:", Ui.FONT_BOLD, Ui.TEXT));
        Ui.WButton change = new Ui.WButton("Change Image...");
        change.setToolTipText("Choose a background image for the current game tab");
        change.addActionListener(e -> chooseBackground());
        Ui.WButton adjust = new Ui.WButton("Adjust...");
        adjust.setToolTipText("Choose which part of the image is shown");
        adjust.addActionListener(e -> adjustBackground());
        Ui.WButton reset = new Ui.WButton("Default");
        reset.addActionListener(e -> {
            settings.set("bg." + current.id, null);
            settings.set("crop." + current.id, null);
            bgCache.remove(current);
            applyBackground();
        });
        bg.add(change);
        bg.add(adjust);
        bg.add(reset);
        bg.add(Box.createHorizontalStrut(8));
        bg.add(Ui.label("Darkness:", Ui.FONT_BOLD, Ui.TEXT));
        dim.setOpaque(false);
        dim.setPreferredSize(new Dimension(140, 26));
        dim.setToolTipText("How much the background image is darkened");
        dim.addChangeListener(e -> {
            root.setDim(dim.getValue());
            if (!dim.getValueIsAdjusting() && current != null) settings.set("dim." + current.id, String.valueOf(dim.getValue()));
        });
        bg.add(dim);
        p.add(bg, BorderLayout.EAST);
        return p;
    }

    private JComponent buildTabBar() {
        JPanel bar = new JPanel(new GridLayout(1, 3, 10, 0));
        bar.setOpaque(false);
        for (Game g : Game.values()) {
            TabButton b = new TabButton(g.tabTitle + "  SAVEGAMES");
            b.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { select(g); }
            });
            tabButtons.put(g, b);
            bar.add(b);
        }
        return bar;
    }

    private void select(Game g) {
        current = g;
        cards.show(cardPanel, g.id);
        for (Map.Entry<Game, TabButton> e : tabButtons.entrySet()) e.getValue().setSelected(e.getKey() == g);
        tabs.get(g).ensureLoaded();
        settings.set("lastTab", g.name());
        dim.setValue(settings.getInt("dim." + g.id, 45));
        root.setDim(dim.getValue());
        applyBackground();
    }

    private void updateTabLabel(Game g) {
        TabButton b = tabButtons.get(g);
        if (b != null) b.setCount(tabs.get(g).saveCount());
    }

    // =================================================================== background

    private BufferedImage currentBackground() {
        return bgCache.computeIfAbsent(current, g -> Ui.loadBackground(settings.get("bg." + g.id, "")));
    }

    private void applyBackground() {
        root.setImage(currentBackground(), Ui.parseCrop(settings.get("crop." + current.id, "")));
    }

    private double windowAspect() {
        return root.getHeight() > 0 ? root.getWidth() / (double) root.getHeight() : 16 / 9.0;
    }

    private void chooseBackground() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose Background Image - " + current.displayName);
        fc.setFileFilter(new FileNameExtensionFilter("Images (png, jpg, bmp, gif)", "png", "jpg", "jpeg", "bmp", "gif"));
        String last = settings.get("bg." + current.id, settings.get("lastBgDir", ""));
        if (!last.isEmpty()) {
            File f = new File(last);
            fc.setCurrentDirectory(f.isDirectory() ? f : f.getParentFile());
        }
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        BufferedImage img;
        try {
            img = ImageIO.read(f);
            if (img == null) throw new IOException("Unsupported image format");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not load image:\n" + ex.getMessage(), APP_TITLE, JOptionPane.ERROR_MESSAGE);
            return;
        }
        // right after choosing, let the user pick the visible area
        double[] crop = CropDialog.show(this, img, null, windowAspect());
        if (crop == null) return;                 // cancelled: keep the old background
        settings.set("lastBgDir", f.getParent());
        settings.set("bg." + current.id, f.getAbsolutePath());
        settings.set("crop." + current.id, Ui.cropToString(crop));
        bgCache.put(current, img);
        applyBackground();
    }

    private void adjustBackground() {
        BufferedImage img = currentBackground();
        if (img == null) return;
        double[] crop = CropDialog.show(this, img, Ui.parseCrop(settings.get("crop." + current.id, "")), windowAspect());
        if (crop == null) return;
        settings.set("crop." + current.id, Ui.cropToString(crop));
        applyBackground();
    }

    // =================================================================== misc

    private static Image makeIcon() {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Ui.hq(g);
        g.setColor(new Color(20, 18, 18));
        g.fillRoundRect(0, 0, 64, 64, 14, 14);
        g.setColor(Ui.RED);
        g.setStroke(new BasicStroke(3f));
        g.drawRoundRect(2, 2, 59, 59, 12, 12);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        g.setColor(Ui.TEXT);
        g.drawString("K", 9, 44);
        g.setColor(Ui.RED_BRIGHT);
        g.drawString("M", 29, 44);
        g.dispose();
        return img;
    }

    /** Game tab button: glass background, red underline when selected, save counter. */
    private static final class TabButton extends JComponent {
        private final String text;
        private boolean selected, hover;
        private int count = -1;

        TabButton(String text) {
            this.text = text;
            setPreferredSize(new Dimension(200, 42));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        void setSelected(boolean s) { selected = s; repaint(); }
        void setCount(int c) { count = c; repaint(); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Ui.hq(g);
            int w = getWidth(), h = getHeight();
            g.setColor(selected ? new Color(60, 10, 14, 210) : hover ? new Color(30, 30, 32, 200) : Ui.GLASS);
            g.fillRoundRect(0, 0, w - 1, h - 1, 12, 12);
            g.setColor(selected ? Ui.RED_BRIGHT : Ui.GLASS_EDGE);
            g.drawRoundRect(0, 0, w - 1, h - 1, 12, 12);
            if (selected) {
                g.setColor(Ui.RED_BRIGHT);
                g.fillRoundRect(16, h - 5, w - 32, 3, 3, 3);
            }
            g.setFont(Ui.pickFont(Font.BOLD, 14));
            FontMetrics fm = g.getFontMetrics();
            String c = count >= 0 ? "  (" + count + ")" : "";
            int tw = fm.stringWidth(text) + fm.stringWidth(c);
            int x = (w - tw) / 2, y = (h + fm.getAscent() - fm.getDescent()) / 2 - 1;
            g.setColor(selected ? Color.WHITE : hover ? Ui.TEXT : Ui.TEXT_DIM);
            g.drawString(text, x, y);
            g.setColor(selected ? Ui.RED_BRIGHT : Ui.TEXT_DIM);
            g.drawString(c, x + fm.stringWidth(text), y);
            g.dispose();
        }
    }
}
