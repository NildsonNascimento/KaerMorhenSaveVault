package kaermorhen;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** One game's page: directory bar, save list, preview and size statistics. */
final class GameTab extends JPanel {

    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd  HH:mm:ss");

    final Game game;
    private final Settings settings;
    private final Consumer<String> status;
    private final Runnable countChanged;

    private final SaveTableModel model = new SaveTableModel();
    private final JTable table = new JTable(model);
    private final JTextField dirField = new JTextField();
    private final PreviewView preview = new PreviewView();

    private final JLabel totalValue = Ui.label("0 B", Ui.pickFont(Font.BOLD, 22), Ui.TEXT);
    private final JLabel totalSub   = Ui.label("0 save files", Ui.FONT_SMALL, Ui.TEXT_DIM);
    private final JLabel selValue   = Ui.label("0 B", Ui.pickFont(Font.BOLD, 22), Ui.RED_BRIGHT);
    private final JLabel selSub     = Ui.label("0 selected", Ui.FONT_SMALL, Ui.TEXT_DIM);

    private final JLabel infoName = Ui.label("-", Ui.FONT_BOLD, Ui.TEXT);
    private final JLabel infoLoc  = Ui.label(" ", Ui.FONT, Ui.TEXT_DIM);
    private final JLabel infoDate = Ui.label(" ", Ui.FONT, Ui.TEXT_DIM);
    private final JLabel infoSize = Ui.label(" ", Ui.FONT, Ui.TEXT_DIM);
    private final JLabel infoShot = Ui.label(" ", Ui.FONT, Ui.TEXT_DIM);

    private final Map<Path, Object[]> previewCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Path, Object[]> e) { return size() > 80; }
    };
    private SwingWorker<Object[], Void> previewWorker;
    private boolean loaded;

    GameTab(Game game, Settings settings, Consumer<String> status, Runnable countChanged) {
        super(new BorderLayout(0, 12));
        this.game = game;
        this.settings = settings;
        this.status = status;
        this.countChanged = countChanged;
        setOpaque(false);

        add(buildDirectoryBar(), BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(12, 0));
        center.setOpaque(false);
        center.add(buildTablePanel(), BorderLayout.CENTER);
        center.add(buildPreviewPanel(), BorderLayout.EAST);
        add(center, BorderLayout.CENTER);

        table.registerKeyboardAction(e -> deleteSelected(), KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
                JComponent.WHEN_FOCUSED);

        String dir = settings.get("saveDir." + game.id, "");
        if (dir.isEmpty() || !Files.isDirectory(Paths.get(dir))) {
            Path auto = game.autoDetect();
            dir = auto != null ? auto.toString() : "";
        }
        dirField.setText(dir);
    }

    int saveCount() { return model.rows.size(); }

    /** Loads the list the first time the tab is shown. */
    void ensureLoaded() { if (!loaded) refresh(); }

    // =================================================================== UI

    private JComponent buildDirectoryBar() {
        Ui.GlassPanel p = new Ui.GlassPanel(new BorderLayout(10, 0));
        p.add(Ui.label(game.directoryLabel(), Ui.FONT_BOLD, Ui.TEXT), BorderLayout.WEST);

        dirField.setFont(Ui.FONT);
        dirField.setBackground(Ui.FIELD_BG);
        dirField.setForeground(Ui.TEXT);
        dirField.setCaretColor(Ui.TEXT);
        dirField.setToolTipText("Default: " + game.defaultHint());
        dirField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(90, 30, 32)), new EmptyBorder(6, 8, 6, 8)));
        dirField.addActionListener(e -> refresh());
        p.add(dirField, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        Ui.WButton browse = new Ui.WButton("Select Directory...");
        browse.addActionListener(e -> chooseDirectory());
        Ui.WButton detect = new Ui.WButton("Auto-Detect");
        detect.addActionListener(e -> {
            Path auto = game.autoDetect();
            if (auto == null) {
                msg("Could not find the " + game.displayName + " save folder automatically.\n"
                        + "Default location: " + game.defaultHint(), JOptionPane.WARNING_MESSAGE);
            } else {
                dirField.setText(auto.toString());
                refresh();
            }
        });
        Ui.WButton open = new Ui.WButton("Open Folder");
        open.addActionListener(e -> openInExplorer(currentDir()));
        buttons.add(browse);
        buttons.add(detect);
        buttons.add(open);
        p.add(buttons, BorderLayout.EAST);
        return p;
    }

    private JComponent buildTablePanel() {
        Ui.GlassPanel p = new Ui.GlassPanel(new BorderLayout(0, 10));

        table.setOpaque(false);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setRowHeight(30);
        table.setFont(Ui.FONT);
        table.setForeground(Ui.TEXT);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        TableRowSorter<SaveTableModel> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.DESCENDING)));

        CellRenderer r = new CellRenderer();
        table.setDefaultRenderer(Object.class, r);
        table.setDefaultRenderer(Long.class, r);
        table.getColumnModel().getColumn(0).setPreferredWidth(game == Game.W1 ? 380 : 330);
        table.getColumnModel().getColumn(1).setPreferredWidth(180);
        table.getColumnModel().getColumn(2).setPreferredWidth(100);

        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setDefaultRenderer(new HeaderRenderer());
        header.setPreferredSize(new Dimension(10, 32));

        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) updateSelectionInfo(); });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    SaveEntry s = leadEntry();
                    if (s != null) openInExplorer(s.saveFile.getParent());
                }
            }
        });

        JScrollPane sp = new JScrollPane(table);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 25)));
        sp.getVerticalScrollBar().setUI(new Ui.DarkScrollBarUI());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(12, 0));
        sp.getVerticalScrollBar().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.getHorizontalScrollBar().setUI(new Ui.DarkScrollBarUI());
        JPanel corner = new JPanel();
        corner.setBackground(new Color(20, 12, 12));
        sp.setCorner(ScrollPaneConstants.UPPER_RIGHT_CORNER, corner);
        p.add(sp, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        Ui.WButton selectAll = new Ui.WButton("Select All");
        selectAll.addActionListener(e -> { table.selectAll(); table.requestFocusInWindow(); });
        Ui.WButton refresh = new Ui.WButton("Refresh");
        refresh.addActionListener(e -> refresh());
        Ui.WButton backup = new Ui.WButton("Backup Saves");
        backup.addActionListener(e -> backupSaves());
        Ui.WButton delete = new Ui.WButton("Delete Selected", true);
        delete.addActionListener(e -> deleteSelected());
        buttons.add(selectAll);
        buttons.add(refresh);
        buttons.add(backup);
        buttons.add(Box.createHorizontalStrut(20));
        buttons.add(delete);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildPreviewPanel() {
        Ui.GlassPanel p = new Ui.GlassPanel(new BorderLayout(0, 12));
        p.setPreferredSize(new Dimension(560, 10));
        p.add(Ui.label("SAVE PREVIEW", Ui.pickFont(Font.BOLD, 14), Ui.RED_BRIGHT), BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        preview.setAlignmentX(LEFT_ALIGNMENT);
        body.add(preview);
        body.add(Box.createVerticalStrut(12));

        boolean w1 = game == Game.W1;
        JPanel info = new JPanel(new GridLayout(w1 ? 5 : 4, 1, 0, 3));
        info.setOpaque(false);
        info.setAlignmentX(LEFT_ALIGNMENT);
        info.add(infoName);
        if (w1) info.add(infoLoc);
        info.add(infoDate);
        info.add(infoSize);
        info.add(infoShot);
        info.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
        body.add(info);
        body.add(Box.createVerticalGlue());
        p.add(body, BorderLayout.CENTER);

        JPanel stats = new JPanel(new GridLayout(1, 2, 10, 0));
        stats.setOpaque(false);
        stats.add(statCard("TOTAL ALL SAVE FILES SIZE", totalValue, totalSub));
        stats.add(statCard("SELECTED SAVE FILES SIZE", selValue, selSub));
        p.add(stats, BorderLayout.SOUTH);
        return p;
    }

    private JComponent statCard(String title, JLabel value, JLabel sub) {
        JPanel c = new JPanel() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Ui.hq(g);
                g.setColor(new Color(0, 0, 0, 130));
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g.setColor(new Color(130, 30, 32, 160));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g.setColor(Ui.RED);
                g.fillRect(0, 10, 3, getHeight() - 20);
                g.dispose();
                super.paintComponent(g0);
            }
        };
        c.setOpaque(false);
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        c.setBorder(new EmptyBorder(10, 14, 10, 10));
        c.add(Ui.label(title, Ui.pickFont(Font.BOLD, 11), Ui.TEXT_DIM));
        c.add(Box.createVerticalStrut(4));
        c.add(value);
        c.add(sub);
        return c;
    }

    // =================================================================== logic

    private Path currentDir() {
        String t = dirField.getText().trim();
        return t.isEmpty() ? null : Paths.get(t);
    }

    void refresh() {
        loaded = true;
        Path dir = currentDir();
        Set<Path> keep = new HashSet<>();
        for (SaveEntry s : selectedEntries()) keep.add(s.saveFile);

        List<SaveEntry> list;
        try {
            list = SaveEntry.scan(dir, game);
        } catch (IOException ex) {
            list = new ArrayList<>();
            msg("Could not read the directory:\n" + ex.getMessage(), JOptionPane.ERROR_MESSAGE);
        }
        model.setData(list);
        if (dir != null && Files.isDirectory(dir)) settings.set("saveDir." + game.id, dir.toString());

        for (int i = 0; i < model.rows.size(); i++) {
            if (keep.contains(model.rows.get(i).saveFile)) {
                int v = table.convertRowIndexToView(i);
                table.addRowSelectionInterval(v, v);
            }
        }
        if (table.getSelectedRowCount() == 0 && table.getRowCount() > 0) table.setRowSelectionInterval(0, 0);

        long total = 0;
        for (SaveEntry s : list) total += s.totalSize;
        totalValue.setText(SaveEntry.formatSize(total));
        totalSub.setText(list.size() + (list.size() == 1 ? " save file" : " save files"));

        if (dir == null) status.accept(game.displayName + ": no directory selected.");
        else if (!Files.isDirectory(dir)) status.accept(game.displayName + ": directory not found - " + dir);
        else status.accept(game.displayName + ": loaded " + list.size() + " saves from " + dir);
        updateSelectionInfo();
        countChanged.run();
    }

    private List<SaveEntry> selectedEntries() {
        List<SaveEntry> out = new ArrayList<>();
        for (int v : table.getSelectedRows()) out.add(model.rows.get(table.convertRowIndexToModel(v)));
        return out;
    }

    private SaveEntry leadEntry() {
        int lead = table.getSelectionModel().getLeadSelectionIndex();
        if (lead < 0 || lead >= table.getRowCount() || !table.isRowSelected(lead)) lead = table.getSelectedRow();
        return lead < 0 ? null : model.rows.get(table.convertRowIndexToModel(lead));
    }

    private void updateSelectionInfo() {
        List<SaveEntry> sel = selectedEntries();
        long size = 0;
        for (SaveEntry s : sel) size += s.totalSize;
        selValue.setText(SaveEntry.formatSize(size));
        selSub.setText(sel.size() + " of " + model.rows.size() + " selected");

        SaveEntry s = leadEntry();
        if (s == null) {
            infoName.setText("No save selected");
            infoLoc.setText(" ");
            infoDate.setText(" ");
            infoSize.setText(" ");
            infoShot.setText(" ");
            preview.setImage(null, "No save selected");
            return;
        }
        infoName.setText(s.name);
        infoLoc.setText(game == Game.W1 ? "Location:  ..." : " ");
        infoDate.setText("Date:  " + DATE_FMT.format(new Date(s.modified)));
        String files = s.extras.isEmpty() ? "" : "   (" + (s.extras.size() + 1) + " files, save " + SaveEntry.formatSize(s.saveSize) + ")";
        infoSize.setText("Size:  " + SaveEntry.formatSize(s.totalSize) + files);
        if (game.embeddedPreview) infoShot.setText("Screenshot:  embedded in the save file");
        else infoShot.setText("Screenshot:  " + (s.screenshot != null ? s.screenshot.getFileName() : "none"));
        loadPreview(s);
    }

    /** Result array: {BufferedImage image, String location}. */
    private void loadPreview(SaveEntry s) {
        if (previewWorker != null) previewWorker.cancel(true);
        if (!s.hasPreview()) { preview.setImage(null, "No preview image for this save"); return; }
        Path key = game.embeddedPreview ? s.saveFile : s.screenshot;
        Object[] cached = previewCache.get(key);
        if (cached != null) { showPreview(cached); return; }

        preview.setImage(null, "Loading...");
        previewWorker = new SwingWorker<>() {
            @Override protected Object[] doInBackground() throws Exception {
                if (game.embeddedPreview) {
                    W1SaveReader.Info info = W1SaveReader.read(s.saveFile);
                    return new Object[]{info.thumbnail, info.location};
                }
                return new Object[]{ImageIO.read(s.screenshot.toFile()), null};
            }
            @Override protected void done() {
                if (isCancelled()) return;
                try {
                    Object[] r = get();
                    previewCache.put(key, r);
                    showPreview(r);
                } catch (Exception ex) {
                    preview.setImage(null, "Could not load preview");
                    if (game == Game.W1) infoLoc.setText("Location:  unknown");
                }
            }
        };
        previewWorker.execute();
    }

    private void showPreview(Object[] r) {
        BufferedImage img = (BufferedImage) r[0];
        preview.setImage(img, img == null ? "No preview image for this save" : null);
        if (game == Game.W1) {
            String loc = (String) r[1];
            infoLoc.setText("Location:  " + (loc == null || loc.isEmpty() ? "unknown" : loc));
            if (img != null) infoShot.setText("Screenshot:  embedded thumbnail (" + img.getWidth() + "x" + img.getHeight() + ")");
        }
    }

    private void chooseDirectory() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select " + game.displayName + " SaveGame Directory");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        Path cur = currentDir();
        if (cur != null && Files.isDirectory(cur)) fc.setCurrentDirectory(cur.toFile());
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            dirField.setText(fc.getSelectedFile().getAbsolutePath());
            refresh();
        }
    }

    void deleteSelected() {
        List<SaveEntry> sel = selectedEntries();
        if (sel.isEmpty()) { msg("No save files selected.", JOptionPane.INFORMATION_MESSAGE); return; }

        boolean trash = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        long size = 0;
        for (SaveEntry s : sel) size += s.totalSize;
        String text = "Delete " + sel.size() + (sel.size() == 1 ? " save" : " saves")
                + " from " + game.displayName + " (" + SaveEntry.formatSize(size) + ")?\n\n"
                + (trash ? "The files (and their screenshots) will be moved to the Recycle Bin."
                         : "WARNING: the files will be permanently deleted.");
        if (JOptionPane.showConfirmDialog(this, text, "Delete Selected",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;

        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (SaveEntry s : sel) {
            boolean good = true;
            for (Path f : s.files()) {
                try {
                    if (trash) { if (!Desktop.getDesktop().moveToTrash(f.toFile())) Files.deleteIfExists(f); }
                    else Files.deleteIfExists(f);
                } catch (Exception ex) {
                    good = false;
                    failed.add(f.getFileName() + ": " + ex.getMessage());
                }
                previewCache.remove(f);
            }
            if (good) ok++;
        }
        table.clearSelection();
        refresh();
        status.accept(game.displayName + ": deleted " + ok + " save(s).");
        if (!failed.isEmpty()) msg("Some files could not be deleted:\n" + String.join("\n", failed), JOptionPane.ERROR_MESSAGE);
    }

    private void backupSaves() {
        if (model.rows.isEmpty()) { msg("There are no saves to back up.", JOptionPane.INFORMATION_MESSAGE); return; }
        List<SaveEntry> sel = selectedEntries();
        List<SaveEntry> toBackup;
        if (!sel.isEmpty() && sel.size() < model.rows.size()) {
            Object[] opts = {"Selected Saves (" + sel.size() + ")", "All Saves (" + model.rows.size() + ")", "Cancel"};
            int r = JOptionPane.showOptionDialog(this, "Which " + game.displayName + " saves do you want to back up?",
                    "Backup Saves", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
            if (r == 0) toBackup = sel;
            else if (r == 1) toBackup = new ArrayList<>(model.rows);
            else return;
        } else {
            toBackup = new ArrayList<>(model.rows);
        }

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose Backup Destination Folder");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        Path def = Paths.get(settings.get("backupDir", Settings.APP_DIR.resolve("Backups").toString()));
        try { Files.createDirectories(def); } catch (IOException ignored) { }
        fc.setCurrentDirectory(def.toFile());
        fc.setSelectedFile(def.toFile());
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path destDir = fc.getSelectedFile().toPath();
        settings.set("backupDir", destDir.toString());

        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        String prefix = game == Game.W1 ? "Witcher1" : game == Game.W2 ? "Witcher2" : "Witcher3";
        Path zip = destDir.resolve(prefix + "_Saves_Backup_" + stamp + ".zip");
        List<SaveEntry> items = toBackup;

        Window w = SwingUtilities.getWindowAncestor(this);
        if (w != null) w.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        status.accept(game.displayName + ": backing up " + items.size() + " saves...");
        new SwingWorker<Long, Void>() {
            @Override protected Long doInBackground() throws Exception {
                Files.createDirectories(destDir);
                long bytes = 0;
                try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(os)) {
                    for (SaveEntry s : items) {
                        for (Path f : s.files()) {
                            ZipEntry ze = new ZipEntry(f.getFileName().toString());
                            ze.setTime(Files.getLastModifiedTime(f).toMillis());
                            zos.putNextEntry(ze);
                            bytes += Files.copy(f, zos);
                            zos.closeEntry();
                        }
                    }
                }
                return bytes;
            }
            @Override protected void done() {
                if (w != null) w.setCursor(Cursor.getDefaultCursor());
                try {
                    long bytes = get();
                    status.accept(game.displayName + ": backup created - " + zip);
                    Object[] opts = {"Open Folder", "OK"};
                    int r = JOptionPane.showOptionDialog(GameTab.this,
                            "Backup complete!\n\n" + items.size() + " saves  (" + SaveEntry.formatSize(bytes) + ")\n" + zip,
                            "Backup Saves", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, opts, opts[1]);
                    if (r == 0) openInExplorer(destDir);
                } catch (Exception ex) {
                    status.accept(game.displayName + ": backup failed.");
                    msg("Backup failed:\n" + ex.getMessage(), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void openInExplorer(Path p) {
        if (p == null || !Files.isDirectory(p)) { msg("Folder not found.", JOptionPane.WARNING_MESSAGE); return; }
        try { Desktop.getDesktop().open(p.toFile()); }
        catch (Exception ex) { msg("Could not open folder:\n" + ex.getMessage(), JOptionPane.ERROR_MESSAGE); }
    }

    private void msg(String text, int type) {
        JOptionPane.showMessageDialog(this, text, Main.APP_TITLE, type);
    }

    // =================================================================== table

    private static final class SaveTableModel extends AbstractTableModel {
        private final String[] cols = {"File Name", "Date", "Size"};
        List<SaveEntry> rows = new ArrayList<>();

        void setData(List<SaveEntry> list) { rows = list; fireTableDataChanged(); }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? String.class : Long.class; }

        @Override public Object getValueAt(int r, int c) {
            SaveEntry s = rows.get(r);
            switch (c) {
                case 0: return s.name;
                case 1: return s.modified;
                default: return s.totalSize;
            }
        }
    }

    private static final class CellRenderer extends DefaultTableCellRenderer {
        private Color fill;

        CellRenderer() { setOpaque(false); }

        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            String text;
            if (col == 1 && v instanceof Long) text = DATE_FMT.format(new Date((Long) v));
            else if (col == 2 && v instanceof Long) text = SaveEntry.formatSize((Long) v);
            else text = String.valueOf(v);
            super.getTableCellRendererComponent(t, text, sel, false, row, col);
            setOpaque(false);
            setFont(col == 0 ? Ui.FONT_BOLD : Ui.FONT);
            setForeground(sel ? Color.WHITE : (col == 0 ? Ui.TEXT : Ui.TEXT_DIM));
            setHorizontalAlignment(col == 2 ? RIGHT : LEFT);
            setBorder(new EmptyBorder(0, 12, 0, 14));
            setToolTipText(col == 0 ? text : null);
            fill = sel ? new Color(170, 22, 28, 150) : (row % 2 == 0 ? new Color(255, 255, 255, 10) : null);
            return this;
        }

        @Override protected void paintComponent(Graphics g) {
            if (fill != null) {
                g.setColor(fill);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            super.paintComponent(g);
        }
    }

    private static final class HeaderRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, v, false, false, row, col);
            String arrow = "";
            RowSorter<?> rs = t.getRowSorter();
            if (rs != null && !rs.getSortKeys().isEmpty()) {
                RowSorter.SortKey k = rs.getSortKeys().get(0);
                if (k.getColumn() == t.convertColumnIndexToModel(col)) {
                    arrow = k.getSortOrder() == SortOrder.ASCENDING ? "  ▲" : "  ▼";
                }
            }
            setText(String.valueOf(v).toUpperCase() + arrow);
            setFont(Ui.pickFont(Font.BOLD, 12));
            setForeground(Ui.TEXT);
            setBackground(new Color(28, 12, 12));
            setOpaque(true);
            setHorizontalAlignment(col == 2 ? RIGHT : LEFT);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, Ui.RED), new EmptyBorder(0, 12, 0, 14)));
            return this;
        }
    }

    // =================================================================== preview

    /** Medium-size (16:9) screenshot preview. Small thumbnails are upscaled smoothly. */
    private static final class PreviewView extends JComponent {
        private BufferedImage src, scaled;
        private String message = "No save selected";

        PreviewView() {
            Dimension d = new Dimension(528, 297);
            setPreferredSize(d);
            setMinimumSize(new Dimension(320, 180));
            setMaximumSize(d);
        }

        void setImage(BufferedImage img, String msg) {
            src = img;
            scaled = null;
            message = msg;
            repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Ui.hq(g);
            int w = getWidth(), h = getHeight();
            g.setColor(new Color(0, 0, 0, 160));
            g.fillRect(0, 0, w, h);
            if (src != null) {
                if (scaled == null || (scaled.getWidth() != w - 8 && scaled.getHeight() != h - 8)) {
                    scaled = Ui.fit(src, w - 8, h - 8);
                }
                g.drawImage(scaled, (w - scaled.getWidth()) / 2, (h - scaled.getHeight()) / 2, null);
            } else if (message != null) {
                g.setFont(Ui.FONT);
                g.setColor(Ui.TEXT_DIM);
                FontMetrics fm = g.getFontMetrics();
                g.drawString(message, (w - fm.stringWidth(message)) / 2, h / 2 + fm.getAscent() / 2 - 2);
            }
            g.setColor(Ui.RED);
            g.setStroke(new BasicStroke(2f));
            g.drawRect(1, 1, w - 2, h - 2);
            g.dispose();
        }
    }
}
