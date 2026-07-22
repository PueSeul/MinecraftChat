package com.dudal.javachat.update;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public final class UpdateFileProvider extends ContentProvider {
    public static final String FILE_NAME = "JavaChat-update.apk";
    private static final String URI_PATH = "/" + FILE_NAME;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        requireExpectedUri(uri);
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file = requireFile(uri);
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(FILE_NAME);
            } else if (OpenableColumns.SIZE.equals(column)) {
                row.add(file.length());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Update APK is read-only");
        }
        File file = requireFile(uri);
        if (!file.isFile()) {
            throw new FileNotFoundException("Update APK is missing");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    private File requireFile(Uri uri) {
        requireExpectedUri(uri);
        if (getContext() == null) {
            throw new IllegalStateException("Provider is not attached");
        }
        try {
            File root = new File(getContext().getCacheDir(), "updates").getCanonicalFile();
            File file = new File(root, FILE_NAME).getCanonicalFile();
            if (!root.equals(file.getParentFile())) {
                throw new SecurityException("Invalid update path");
            }
            return file;
        } catch (IOException error) {
            throw new IllegalStateException("Could not resolve update path", error);
        }
    }

    private static void requireExpectedUri(Uri uri) {
        if (uri == null || !URI_PATH.equals(uri.getPath())) {
            throw new SecurityException("Unexpected update URI");
        }
    }
}
