package kaermorhen;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Reads the embedded thumbnail (.tga) and location name (savenfo.txt) from a
 * The Witcher 1 ".TheWitcherSave" file.
 *
 * Format ("RGMH" archive): the last 8 bytes hold [u32 indexOffset][u32 entryCount].
 * Each index entry is [u32 nameLength][name][u32 dataLength][u32 dataOffset].
 */
final class W1SaveReader {

    static final class Info {
        BufferedImage thumbnail;
        String location;
    }

    private W1SaveReader() { }

    static Info read(Path file) throws IOException {
        Info info = new Info();
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long len = raf.length();
            if (len < 16) throw new IOException("File too small");
            byte[] sig = new byte[4];
            raf.readFully(sig);
            if (!"RGMH".equals(new String(sig, StandardCharsets.US_ASCII))) throw new IOException("Not a Witcher 1 save");

            raf.seek(len - 8);
            byte[] tail = new byte[8];
            raf.readFully(tail);
            ByteBuffer t = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
            long indexOffset = t.getInt() & 0xFFFFFFFFL;
            int count = t.getInt();
            long indexSize = len - 8 - indexOffset;
            if (indexOffset <= 0 || indexOffset >= len || count <= 0 || count > 200_000 || indexSize > 32 << 20) {
                throw new IOException("Unknown save index");
            }

            byte[] idx = new byte[(int) indexSize];
            raf.seek(indexOffset);
            raf.readFully(idx);
            ByteBuffer b = ByteBuffer.wrap(idx).order(ByteOrder.LITTLE_ENDIAN);

            long tgaOff = -1, tgaLen = 0, nfoOff = -1, nfoLen = 0;
            for (int i = 0; i < count && b.remaining() >= 12; i++) {
                int n = b.getInt();
                if (n < 0 || n > b.remaining() - 8) break;
                byte[] nb = new byte[n];
                b.get(nb);
                long dataLen = b.getInt() & 0xFFFFFFFFL;
                long dataOff = b.getInt() & 0xFFFFFFFFL;
                String name = new String(nb, StandardCharsets.ISO_8859_1).toLowerCase();
                if (tgaOff < 0 && name.endsWith(".tga")) { tgaOff = dataOff; tgaLen = dataLen; }
                else if (nfoOff < 0 && name.endsWith("savenfo.txt")) { nfoOff = dataOff; nfoLen = dataLen; }
            }

            if (tgaOff >= 0 && tgaLen > 18 && tgaOff + tgaLen <= len && tgaLen < 64 << 20) {
                byte[] data = new byte[(int) tgaLen];
                raf.seek(tgaOff);
                raf.readFully(data);
                info.thumbnail = trimPadding(decodeTga(data));
            }
            if (nfoOff >= 0 && nfoLen > 0 && nfoLen < 4096 && nfoOff + nfoLen <= len) {
                byte[] data = new byte[(int) nfoLen];
                raf.seek(nfoOff);
                raf.readFully(data);
                info.location = new String(data, StandardCharsets.ISO_8859_1).replace("\0", "").trim();
            }
        }
        return info;
    }

    /** Minimal TGA decoder: true-colour 24/32-bit, uncompressed (2) or RLE (10). */
    static BufferedImage decodeTga(byte[] d) throws IOException {
        int idLen = d[0] & 0xFF;
        int cmapType = d[1] & 0xFF;
        int type = d[2] & 0xFF;
        int cmapLen = (d[5] & 0xFF) | (d[6] & 0xFF) << 8;
        int cmapBits = d[7] & 0xFF;
        int w = (d[12] & 0xFF) | (d[13] & 0xFF) << 8;
        int h = (d[14] & 0xFF) | (d[15] & 0xFF) << 8;
        int bpp = d[16] & 0xFF;
        int desc = d[17] & 0xFF;
        if ((type != 2 && type != 10) || (bpp != 24 && bpp != 32) || w <= 0 || h <= 0) {
            throw new IOException("Unsupported TGA (type " + type + ", " + bpp + " bpp)");
        }
        int pos = 18 + idLen + (cmapType == 1 ? cmapLen * ((cmapBits + 7) / 8) : 0);
        int px = bpp / 8;
        int[] argb = new int[w * h];
        int i = 0;
        if (type == 2) {
            while (i < argb.length && pos + px <= d.length) { argb[i++] = pixel(d, pos, px); pos += px; }
        } else {
            while (i < argb.length && pos < d.length) {
                int hdr = d[pos++] & 0xFF;
                int n = (hdr & 0x7F) + 1;
                if ((hdr & 0x80) != 0) {
                    if (pos + px > d.length) break;
                    int c = pixel(d, pos, px);
                    pos += px;
                    for (int k = 0; k < n && i < argb.length; k++) argb[i++] = c;
                } else {
                    for (int k = 0; k < n && i < argb.length && pos + px <= d.length; k++) { argb[i++] = pixel(d, pos, px); pos += px; }
                }
            }
        }
        boolean topDown = (desc & 0x20) != 0;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            int srcRow = topDown ? y : h - 1 - y;
            img.setRGB(0, y, w, 1, argb, srcRow * w, w);
        }
        return img;
    }

    private static int pixel(byte[] d, int p, int px) {
        int bl = d[p] & 0xFF, g = d[p + 1] & 0xFF, r = d[p + 2] & 0xFF;
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    /** The game stores a 64x36 picture inside a 64x64 canvas; cut the flat grey rows. */
    static BufferedImage trimPadding(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int bottom = h;
        while (bottom > 1 && uniformRow(img, bottom - 1, w)) bottom--;
        int top = 0;
        while (top < bottom - 1 && uniformRow(img, top, w)) top++;
        if (top == 0 && bottom == h) return img;
        return img.getSubimage(0, top, w, bottom - top);
    }

    private static boolean uniformRow(BufferedImage img, int y, int w) {
        int c = img.getRGB(0, y);
        for (int x = 1; x < w; x++) if (img.getRGB(x, y) != c) return false;
        return true;
    }
}
