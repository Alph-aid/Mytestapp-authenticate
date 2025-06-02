// PathUtil.java
package com.example.mytestapp; // Make sure this matches your package name

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
// ... (Paste the COMPLETE Java code for PathUtil here) ...
// The Java version I provided in previous interactions should be used.
// It includes methods like:
// - getPath(Context context, Uri uri)
// - getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs)
// - copyFileToInternalStorage(Context context, Uri uri, String newFileNamePrefix)
// - isExternalStorageDocument(Uri uri)
// - isDownloadsDocument(Uri uri)
// - isMediaDocument(Uri uri)
// - isGooglePhotosUri(Uri uri)
// - isGoogleDriveUri(Uri uri)
// - isOneDriveUri(Uri uri)

public class PathUtil {
    private static final String TAG = "PathUtil";

    // --- PASTE THE FULL JAVA CODE FOR PathUtil HERE ---
    // For example, one of the methods:
    public static String getPath(final Context context, final Uri uri) {
        if (uri == null) {
            Log.e(TAG, "URI is null");
            return null;
        }
        Log.d(TAG, "Getting path for URI: " + uri + " | Scheme: " + uri.getScheme() + " | Authority: " + uri.getAuthority());

        // DocumentProvider
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && DocumentsContract.isDocumentUri(context, uri)) {
            Log.d(TAG, "URI is DocumentURI");
            if (isExternalStorageDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                if ("primary".equalsIgnoreCase(type)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // For API 29+, direct path access to primary external storage is restricted.
                        // Copying to cache is a reliable way.
                        return copyFileToInternalStorage(context, uri, "temp_doc_");
                    } else {
                        String path = Environment.getExternalStorageDirectory() + "/" + split[1];
                        if (new File(path).exists()) return path;
                        // Fallback to copy if direct path doesn't exist or isn't accessible
                        return copyFileToInternalStorage(context, uri, "temp_primary_");
                    }
                } else {
                     Log.d(TAG, "ExternalStorageDocument with type: " + type);
                     // For other types like SD cards, copy to cache.
                     return copyFileToInternalStorage(context, uri, "temp_ext_storage_");
                }
            }
            // DownloadsProvider
            else if (isDownloadsDocument(uri)) {
                Log.d(TAG, "URI is DownloadsDocument");
                final String id = DocumentsContract.getDocumentId(uri);
                if (!TextUtils.isEmpty(id)) {
                    if (id.startsWith("raw:")) {
                        return id.substring(id.indexOf("raw:") + 4);
                    }
                     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && id.startsWith("msf:")) {
                         Log.d(TAG, "DownloadsDocument with msf prefix, attempting copy.");
                         return copyFileToInternalStorage(context, uri, "temp_msf_download_");
                    }
                    try {
                        final Uri contentUri = ContentUris.withAppendedId(
                                Uri.parse("content://downloads/public_downloads"), Long.parseLong(id));
                        String path = getDataColumn(context, contentUri, null, null);
                        if (path != null) return path;
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "DownloadsProvider: Could not parse id: " + id, e);
                    }
                    // Fallback for downloads if direct path fails
                    return copyFileToInternalStorage(context, uri, "temp_download_");
                }
            }
            // MediaProvider
            else if (isMediaDocument(uri)) {
                Log.d(TAG, "URI is MediaDocument");
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && "document".equals(type)) {
                    contentUri = MediaStore.Files.getContentUri(MediaStore.getVolumeName(uri));
                }


                final String selection = "_id=?";
                final String[] selectionArgs = new String[]{split[1]};

                String path = getDataColumn(context, contentUri, selection, selectionArgs);
                if (path != null) return path;
                // Fallback for media if direct path fails
                return copyFileToInternalStorage(context, uri, "temp_media_");
            }
        }
        // MediaStore (and general)
        else if ("content".equalsIgnoreCase(uri.getScheme())) {
            Log.d(TAG, "URI is content scheme");
            // Return the remote address
            if (isGooglePhotosUri(uri) || isGoogleDriveUri(uri)) {
                Log.d(TAG, "URI is Google Photos/Drive, attempting copy.");
                return copyFileToInternalStorage(context, uri, "temp_cloud_");
            }
            if (isOneDriveUri(uri)){
                Log.d(TAG, "URI is OneDrive, attempting copy.");
                return copyFileToInternalStorage(context, uri, "temp_onedrive_");
            }

            String path = getDataColumn(context, uri, null, null);
            if (path != null) return path;
            // Fallback for content URIs if direct path fails
            return copyFileToInternalStorage(context, uri, "temp_content_");
        }
        // File
        else if ("file".equalsIgnoreCase(uri.getScheme())) {
            Log.d(TAG, "URI is file scheme");
            return uri.getPath();
        }

        Log.e(TAG, "Could not determine path for URI: " + uri + ". Attempting final fallback copy.");
        return copyFileToInternalStorage(context, uri, "fallback_"); // Fallback for unknown URIs
    }

    public static String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {
        if (uri == null) return null;
        Cursor cursor = null;
        final String column = MediaStore.MediaColumns.DATA; // _data column is deprecated but often still works for path
        final String[] projection = {column};

        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                final int columnIndex = cursor.getColumnIndex(column);
                if (columnIndex > -1) {
                    String path = cursor.getString(columnIndex);
                    if (path != null && new File(path).exists()) {
                         Log.d(TAG, "getDataColumn: Path from cursor: " + path + " for URI: " + uri);
                        return path;
                    } else {
                        Log.w(TAG, "getDataColumn: Path from cursor is null or file does not exist: " + path + " for URI: " + uri);
                    }
                } else {
                     Log.w(TAG, "getDataColumn: Column '_data' not found for URI: " + uri);
                }
            }
        } catch (IllegalArgumentException e) {
             Log.e(TAG, "getDataColumn: IllegalArgumentException for URI: " + uri, e);
        } catch (SecurityException e) {
             Log.e(TAG, "getDataColumn: SecurityException for URI: " + uri, e);
        } catch (Exception e) {
            Log.e(TAG, "getDataColumn: Generic exception for URI: " + uri, e);
        } finally {
            if (cursor != null)
                cursor.close();
        }
        Log.w(TAG, "getDataColumn: Could not retrieve path using cursor for URI: " + uri + ". Will attempt copy if necessary.");
        return null;
    }

    public static String copyFileToInternalStorage(Context context, Uri uri, String newFileNamePrefix) {
        if (uri == null) {
            Log.e(TAG, "copyFileToInternalStorage: URI is null");
            return null;
        }

        InputStream inputStream = null;
        OutputStream outputStream = null;
        String fileName = newFileNamePrefix + System.currentTimeMillis(); // Default filename

        // Attempt to get original file name
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                if (nameIndex != -1) {
                    String originalFileName = cursor.getString(nameIndex);
                    if (originalFileName != null && !originalFileName.isEmpty()) {
                        // Sanitize the file name and prepend the prefix
                        fileName = newFileNamePrefix + originalFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get display name for URI: " + uri, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        File cacheDir = context.getCacheDir();
        if (cacheDir == null) {
            Log.e(TAG, "Cache directory is null.");
            return null;
        }
        File outputFile = new File(cacheDir, fileName);


        try {
            inputStream = context.getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                Log.e(TAG, "Could not open input stream for URI: " + uri);
                return null;
            }
            outputStream = new FileOutputStream(outputFile);

            byte[] buffer = new byte[4 * 1024]; // 4KB buffer
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();
            Log.d(TAG, "File copied to cache: " + outputFile.getAbsolutePath() + " for URI: " + uri);
            return outputFile.getAbsolutePath();
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException: Error copying file to internal storage for URI: " + uri + ". Check permissions.", e);
        } catch (Exception e) {
            Log.e(TAG, "Error copying file to internal storage for URI: " + uri, e);
            // Delete partially written file if error occurs
            if (outputFile.exists()) {
                outputFile.delete();
            }
        } finally {
            try {
                if (inputStream != null) inputStream.close();
                if (outputStream != null) outputStream.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing streams", e);
            }
        }
        return null;
    }


    public static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    public static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    public static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    public static boolean isGooglePhotosUri(Uri uri) {
        return "com.google.android.apps.photos.content".equals(uri.getAuthority()) ||
               "com.google.android.apps.photos.contentprovider".equals(uri.getAuthority());
    }
    public static boolean isGoogleDriveUri(Uri uri) {
        return "com.google.android.apps.docs.storage".equals(uri.getAuthority()) ||
               "com.google.android.apps.docs.storage.legacy".equals(uri.getAuthority());
    }
    public static boolean isOneDriveUri(Uri uri) {
        return "com.microsoft.skydrive.content.StorageAccessProvider".equals(uri.getAuthority());
    }
}