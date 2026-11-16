package com.taglens.scanner;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ExifInterface;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

final class PhotoScanner {
    interface Input { InputStream open() throws IOException; }

    static final class Output {
        final ScanResult result;
        final Bitmap preview;
        Output(ScanResult result, Bitmap preview) {
            this.result = result;
            this.preview = preview;
        }
    }

    private static final int TILE = 1024;
    private static final int STRIDE = 768;

    static Output scan(Input input, NativeEngine engine, int family, String name, int threads, boolean board,
                       BooleanSupplier canceled, IntConsumer progress) throws IOException {
        long start = System.nanoTime();
        final int orientation = orientation(input);
        try (InputStream stream = input.open()) {
            BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(stream, false);
            if (decoder == null) throw new IOException("无法打开图片，请使用 JPG、PNG 或 WebP");
            return scanRegions(decoder, engine, family, name, threads, board, canceled, progress, start, orientation);
        }
    }

    private static Output scanRegions(BitmapRegionDecoder decoder, NativeEngine engine, int family,
                                      String name, int threads, boolean board, BooleanSupplier canceled,
                                      IntConsumer progress, long start, int orientation) throws IOException {
        Bitmap preview = null;
        try {
            int width = decoder.getWidth(), height = decoder.getHeight();
            int longest = Math.max(width, height);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            while (longest / Math.max(1, options.inSampleSize) > 2400) {
                options.inSampleSize = Math.max(1, options.inSampleSize) * 2;
            }
            Map<Integer, List<Candidate>> candidates = new HashMap<>();
            int tileWidth = Math.min(width, TILE), tileHeight = Math.min(height, TILE);
            int cols = intervals(width - tileWidth), rows = intervals(height - tileHeight);
            boolean tiled = cols > 0 || rows > 0;

            check(canceled);
            Bitmap overview = decoder.decodeRegion(new Rect(0, 0, width, height), options);
            if (overview == null) throw new IOException("图片解码失败");
            try {
                if (tiled) {
                    List<ScanResult.Tag> tags = ScanResult.decode(engine.scanPhoto(overview, family, threads, board));
                    tags.addAll(ScanResult.decode(engine.scanPhotoAlternate(overview, family, threads, board)));
                    Matrix scale = new Matrix();
                    scale.setScale(width / (float)overview.getWidth(), height / (float)overview.getHeight());
                    for (ScanResult.Tag tag : tags) {
                        tag.transform(scale);
                        merge(candidates, tag, false);
                    }
                }
                preview = orientBitmap(overview, orientation);
            } finally {
                if (preview != overview) overview.recycle();
            }
            options.inSampleSize = 1;
            int total = (cols + 1) * (rows + 1), done = 0;
            for (int row = 0; row <= rows; row++) {
                int y = offset(height - tileHeight, row, rows);
                for (int col = 0; col <= cols; col++) {
                    check(canceled);
                    int x = offset(width - tileWidth, col, cols);
                    Bitmap tile = decoder.decodeRegion(new Rect(x, y, x + tileWidth, y + tileHeight), options);
                    if (tile == null) throw new IOException("图片分块解码失败");
                    try {
                        List<ScanResult.Tag> tags = ScanResult.decode(engine.scanPhoto(tile, family, threads, board));
                        tags.addAll(ScanResult.decode(engine.scanPhotoAlternate(tile, family, threads, board)));
                        for (ScanResult.Tag tag : tags) {
                            tag.offset(x, y);
                            merge(candidates, tag, true);
                        }
                    } finally { tile.recycle(); }
                    progress.accept(++done * 100 / total);
                }
            }
            check(canceled);
            Matrix transform = normalizedOrientation(orientation, width, height);
            boolean swapped = orientation >= 5 && orientation <= 8;
            List<ScanResult.Tag> tags = new ArrayList<>();
            for (List<Candidate> instances : candidates.values()) {
                for (Candidate candidate : instances) {
                    candidate.tag.transform(transform);
                    tags.add(candidate.tag);
                }
            }
            ScanResult result = new ScanResult(swapped ? height : width, swapped ? width : height,
                name, tags, start, true);
            Output output = new Output(result, preview);
            preview = null;
            return output;
        } finally {
            decoder.recycle();
            if (preview != null) preview.recycle();
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) throw new CancellationException();
    }

    private static int intervals(int remaining) { return (remaining + STRIDE - 1) / STRIDE; }
    private static int offset(int remaining, int index, int intervals) {
        return intervals == 0 ? 0 : Math.round(remaining * (index / (float)intervals));
    }

    private static int orientation(Input input) {
        try (InputStream stream = input.open()) {
            return new ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
        } catch (IOException | RuntimeException ignored) { return 1; }
    }

    static Matrix normalizedOrientation(int orientation, int width, int height) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case 2: matrix.setScale(-1, 1); break;
            case 3: matrix.setRotate(180); break;
            case 4: matrix.setScale(1, -1); break;
            case 5: matrix.setValues(new float[]{0,1,0,1,0,0,0,0,1}); break;
            case 6: matrix.setRotate(90); break;
            case 7: matrix.setValues(new float[]{0,-1,0,-1,0,0,0,0,1}); break;
            case 8: matrix.setRotate(270); break;
            default: break;
        }
        RectF bounds = new RectF(0, 0, width, height);
        matrix.mapRect(bounds);
        matrix.postTranslate(-bounds.left, -bounds.top);
        return matrix;
    }

    private static Bitmap orientBitmap(Bitmap bitmap, int orientation) {
        if (orientation <= 1 || orientation > 8) return bitmap;
        Matrix matrix = normalizedOrientation(orientation, bitmap.getWidth(), bitmap.getHeight());
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private static final class Candidate {
        final ScanResult.Tag tag;
        final RectF bounds;
        final boolean detail;
        Candidate(ScanResult.Tag tag, boolean detail) {
            this.tag = tag;
            this.detail = detail;
            this.bounds = tag.bounds();
        }
    }

    private static void merge(Map<Integer, List<Candidate>> groups, ScanResult.Tag tag, boolean detail) {
        List<Candidate> matches = groups.computeIfAbsent(tag.id, key -> new ArrayList<>());
        Candidate candidate = new Candidate(tag, detail);
        for (int i = 0; i < matches.size(); i++) {
            Candidate prior = matches.get(i);
            RectF a = candidate.bounds, b = prior.bounds;
            float intersection = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left))
                * Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
            float union = a.width() * a.height() + b.width() * b.height() - intersection;
            if (union <= 0 || intersection / union <= .5f) continue;
            if (tag.hamming < prior.tag.hamming || (tag.hamming == prior.tag.hamming
                && ((detail && !prior.detail) || (detail == prior.detail && tag.margin > prior.tag.margin)))) {
                matches.set(i, candidate);
            }
            return;
        }
        matches.add(candidate);
    }
}
