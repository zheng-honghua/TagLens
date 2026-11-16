package com.taglens.scanner;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ResultShare extends ContentProvider {
    private static File directory(Context context) {
        return new File(context.getCacheDir(), "shared-results");
    }

    static Uri prepare(Context context, String json, String prefix) throws IOException {
        File folder = directory(context);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Unable to create share cache");
        File[] files = folder.listFiles();
        long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        if (files != null) {
            for (File file : files) {
                if (file.lastModified() < cutoff) file.delete();
            }
        }
        String name = prefix + "-" + UUID.randomUUID() + ".json";
        File file = new File(folder, name);
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            file.delete();
            throw error;
        }
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".results")
            .appendPath(name).build();
    }

    private File resolve(Uri uri) {
        String name = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 1 || name == null
                || !name.matches("(calibration|apriltag)-[0-9a-f-]{36}\\.json")) {
            throw new IllegalArgumentException("Invalid result URI");
        }
        return new File(directory(getContext()), name);
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { resolve(uri); return "application/octet-stream"; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only attachment");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        String[] columns = projection == null
            ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        if (file.isFile()) {
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
                if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            cursor.addRow(row);
        }
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
