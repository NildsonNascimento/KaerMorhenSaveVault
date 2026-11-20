package kaermorhen;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/**
 * Lets the user choose which part of the background image is shown.
 * The selection box keeps the window's aspect ratio: drag to move, drag the corners
 * to resize, mouse wheel to zoom in/out. A live preview shows the result.
 */
final class CropDialog extends JDialog {

    private final BufferedImage img;
    private final double aspect;          // width / height of the window
    private final Rectangle2D.Double sel; // selection in image pixels
    private double[] result;
    private final Canvas canvas = new Canvas();
    private final Preview preview = new Preview();
    private final JLabel sizeLabel = Ui.label(" ", Ui.FONT_SMALL, Ui.TEXT_DIM);

    /** @return crop fractions {x, y, w, h}, or null if cancelled. */
    static double[] show(Window owner, BufferedImage img, double[] initial, double aspect) {
        CropDialog d = new CropDialog(owner, img, initial, aspect);
        d.setVisible(true);
        return d.result;
    }

    private CropDialog(Window owner, BufferedImage img, double[] initial, double aspect) {
        super(owner, "Adjust Background - choose the visible area", ModalityType.APPLICATION_MODAL);
        this.img = img;
        this.aspect = aspect > 0 ? aspect : 16 / 9.0;
        this.sel = new Rectangle2D.Double();
        if (initial != null) {
            double cx = (initial[0] + initial[2] / 2) * img.getWidth();
            double cy = (initial[1] + initial[3] / 2) * img.getHeight();
            double w = initial[2] * img.getWidth();
            setSelection(cx, cy, w);
        } else {
            maximize();
        }

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBackground(new Color(14, 14, 16));
        root.setBorder(new EmptyBorder(14, 14, 14, 14));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(Ui.label("ADJUST BACKGROUND", Ui.pickFont(Font.BOLD, 16), Ui.RED_BRIGHT), BorderLayout.WEST);
        top.add(Ui.label("Drag the box to move  •  drag the corners to resize  •  mouse wheel to zoom",
                Ui.FONT, Ui.TEXT_DIM), BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        canvas.setPreferredSize(new Dimension(820, 520));
        root.add(canvas, BorderLayout.CENTER);

        JPanel side = new JPanel();
        side.setOpaque(false);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.add(left(Ui.label("RESULT PREVIEW", Ui.FONT_BOLD, Ui.TEXT)));
        side.add(Box.createVerticalStrut(8));
        int pw = 300, ph = (int) Math.round(pw / this.aspect);
        preview.setPreferredSize(new Dimension(pw, ph));
        preview.setMaximumSize(new Dimension(pw, ph));
        side.add(left(preview));
        side.add(Box.createVerticalStrut(8));
        side.add(left(sizeLabel));
        side.add(Box.createVerticalStrut(16));

        Ui.WButton max = new Ui.WButton("Maximize");
        max.setToolTipText("Largest possible area, centred");
        max.addActionListener(e -> { maximize(); changed(); });
        Ui.WButton center = new Ui.WButton("Center");
        center.addActionListener(e -> {
            setSelection(img.getWidth() / 2.0, img.getHeight() / 2.0, sel.width);
            changed();
        });
        Ui.WButton zoomIn = new Ui.WButton("Zoom +");
        zoomIn.addActionListener(e -> { zoom(0.9); changed(); });
        Ui.WButton zoomOut = new Ui.WButton("Zoom -");
        zoomOut.addActionListener(e -> { zoom(1 / 0.9); changed(); });
        JPanel grid = new JPanel(new GridLayout(2, 2, 8, 8));
        grid.setOpaque(false);
        grid.add(max);
        grid.add(center);
        grid.add(zoomIn);
        grid.add(zoomOut);
        grid.setMaximumSize(new Dimension(pw, 80));
        side.add(left(grid));
        side.add(Box.createVerticalGlue());
        root.add(side, BorderLayout.EAST);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        bottom.setOpaque(false);
        Ui.WButton cancel = new Ui.WButton("Cancel");
        cancel.addActionListener(e -> dispose());
        Ui.WButton apply = new Ui.WButton("Apply", true);
        apply.addActionListener(e -> {
            result = new double[]{sel.x / img.getWidth(), sel.y / img.getHeight(),
                    sel.width / img.getWidth(), sel.height / img.getHeight()};
            dispose();
        });
        bottom.add(cancel);
        bottom.add(apply);
        root.add(bottom, BorderLayout.SOUTH);

        setContentPane(root);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setMinimumSize(new Dimension(900, 520));
        setLocationRelativeTo(owner);
        changed();
    }

    private static JComponent left(JComponent c) { c.setAlignmentX(LEFT_ALIGNMENT); return c; }

    // ------------------------------------------------------------------ selection math

    private double maxWidth() { return Math.min(img.getWidth(), img.getHeight() * aspect); }
    private double minWidth() { return Math.max(24, maxWidth() * 0.05); }

    private void maximize() { setSelection(img.getWidth() / 2.0, img.getHeight() / 2.0, maxWidth()); }

    private void setSelection(double cx, double cy, double w) {
        w = Math.max(minWidth(), Math.min(maxWidth(), w));
        double h = w / aspect;
        sel.setRect(cx - w / 2, cy - h / 2, w, h);
        clampPosition();
    }

    private void clampPosition() {
        sel.x = Math.max(0, Math.min(img.getWidth() - sel.width, sel.x));
        sel.y = Math.max(0, Math.min(img.getHeight() - sel.height, sel.y));
    }

    private void zoom(double factor) { setSelection(sel.getCenterX(), sel.getCenterY(), sel.width * factor); }

    private void changed() {
        sizeLabel.setText(String.format("Area: %d x %d px  (%.0f%% of image width)",
                Math.round(sel.width), Math.round(sel.height), 100 * sel.width / img.getWidth()));
        canvas.repaint();
        preview.repaint();
    }

    // ------------------------------------------------------------------ editor canvas

    private final class Canvas extends JComponent {
        private double scale, ox, oy;
        private int dragMode = -1;           // -1 none, 0 move, 1..4 corner (TL, TR, BL, BR)
        private double grabX, grabY, startX, startY;
        private double anchorX, anchorY;

        Canvas() {
            MouseAdapter m = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    dragMode = hit(e.getX(), e.getY());
                    double ix = toImgX(e.getX()), iy = toImgY(e.getY());
                    grabX = ix; grabY = iy; startX = sel.x; startY = sel.y;
                    switch (dragMode) {
                        case 1: anchorX = sel.x + sel.width; anchorY = sel.y + sel.height; break;
                        case 2: anchorX = sel.x;             anchorY = sel.y + sel.height; break;
                        case 3: anchorX = sel.x + sel.width; anchorY = sel.y;              break;
                        case 4: anchorX = sel.x;             anchorY = sel.y;              break;
                        default: break;
                    }
                    if (dragMode == -1) {             // click outside: jump the box there
                        setSelection(ix, iy, sel.width);
                        dragMode = 0; startX = sel.x; startY = sel.y;
                        changed();
                    }
                }

                @Override public void mouseDragged(MouseEvent e) {
                    double ix = toImgX(e.getX()), iy = toImgY(e.getY());
                    if (dragMode == 0) {
                        sel.x = startX + (ix - grabX);
                        sel.y = startY + (iy - grabY);
                        clampPosition();
                    } else if (dragMode > 0) {
                        boolean leftward = dragMode == 1 || dragMode == 3;
                        boolean upward = dragMode == 1 || dragMode == 2;
                        double w = Math.max(Math.abs(ix - anchorX), Math.abs(iy - anchorY) * aspect);
                        double limW = leftward ? anchorX : img.getWidth() - anchorX;
                        double limH = upward ? anchorY : img.getHeight() - anchorY;
                        w = Math.min(w, Math.min(limW, limH * aspect));
                        w = Math.max(Math.min(minWidth(), Math.min(limW, limH * aspect)), w);
                        double h = w / aspect;
                        sel.setRect(leftward ? anchorX - w : anchorX, upward ? anchorY - h : anchorY, w, h);
                    }
                    changed();
                }

                @Override public void mouseReleased(MouseEvent e) { dragMode = -1; }

                @Override public void mouseMoved(MouseEvent e) {
                    int h = hit(e.getX(), e.getY());
                    int c;
                    switch (h) {
                        case 1: c = Cursor.NW_RESIZE_CURSOR; break;
                        case 2: c = Cursor.NE_RESIZE_CURSOR; break;
                        case 3: c = Cursor.SW_RESIZE_CURSOR; break;
                        case 4: c = Cursor.SE_RESIZE_CURSOR; break;
                        case 0: c = Cursor.MOVE_CURSOR; break;
                        default: c = Cursor.CROSSHAIR_CURSOR;
                    }
                    setCursor(Cursor.getPredefinedCursor(c));
                }

                @Override public void mouseWheelMoved(MouseWheelEvent e) {
                    zoom(e.getWheelRotation() > 0 ? 1.06 : 1 / 1.06);
                    changed();
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            addMouseWheelListener(m);
        }

        private void layoutImage() {
            int pad = 16;
            double w = getWidth() - pad * 2, h = getHeight() - pad * 2;
            scale = Math.min(w / img.getWidth(), h / img.getHeight());
            ox = (getWidth() - img.getWidth() * scale) / 2;
            oy = (getHeight() - img.getHeight() * scale) / 2;
        }

        private double toImgX(int x) { return (x - ox) / scale; }
        private double toImgY(int y) { return (y - oy) / scale; }

        private Rectangle2D.Double viewSel() {
            return new Rectangle2D.Double(ox + sel.x * scale, oy + sel.y * scale, sel.width * scale, sel.height * scale);
        }

        private int hit(int x, int y) {
            layoutImage();
            Rectangle2D.Double v = viewSel();
            double r = 12;
            double[][] corners = {{v.x, v.y}, {v.x + v.width, v.y}, {v.x, v.y + v.height}, {v.x + v.width, v.y + v.height}};
            for (int i = 0; i < 4; i++) {
                if (Math.abs(x - corners[i][0]) <= r && Math.abs(y - corners[i][1]) <= r) return i + 1;
            }
            return v.contains(x, y) ? 0 : -1;
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Ui.hq(g);
            g.setColor(new Color(6, 6, 8));
            g.fillRect(0, 0, getWidth(), getHeight());
            layoutImage();
            int iw = (int) Math.round(img.getWidth() * scale), ih = (int) Math.round(img.getHeight() * scale);
            g.drawImage(img, (int) Math.round(ox), (int) Math.round(oy), iw, ih, null);

            Rectangle2D.Double v = viewSel();
            Area shade = new Area(new Rectangle2D.Double(ox, oy, iw, ih));
            shade.subtract(new Area(v));
            g.setColor(new Color(0, 0, 0, 165));
            g.fill(shade);

            g.setColor(new Color(255, 255, 255, 60));               // rule of thirds
            for (int i = 1; i < 3; i++) {
                int x = (int) Math.round(v.x + v.width * i / 3), y = (int) Math.round(v.y + v.height * i / 3);
                g.drawLine(x, (int) v.y, x, (int) (v.y + v.height));
                g.drawLine((int) v.x, y, (int) (v.x + v.width), y);
            }
            g.setColor(Ui.RED_BRIGHT);
            g.setStroke(new BasicStroke(2f));
            g.draw(v);
            int hs = 10;
            for (double[] c : new double[][]{{v.x, v.y}, {v.x + v.width, v.y}, {v.x, v.y + v.height}, {v.x + v.width, v.y + v.height}}) {
                g.setColor(Color.WHITE);
                g.fillRect((int) c[0] - hs / 2, (int) c[1] - hs / 2, hs, hs);
                g.setColor(Ui.RED);
                g.drawRect((int) c[0] - hs / 2, (int) c[1] - hs / 2, hs, hs);
            }
            g.dispose();
        }
    }

    private final class Preview extends JComponent {
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Ui.hq(g);
            double[] c = {sel.x / img.getWidth(), sel.y / img.getHeight(), sel.width / img.getWidth(), sel.height / img.getHeight()};
            Ui.drawCover(g, img, c, getWidth(), getHeight());
            g.setColor(Ui.RED);
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            g.dispose();
        }
    }
}
