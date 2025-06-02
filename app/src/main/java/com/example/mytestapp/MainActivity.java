package com.example.mytestapp; // Ensure this matches your package name

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.bumptech.glide.Glide;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // UI Elements
    private EditText editTextTextData;
    private Button buttonCaptureImage1;
    private ImageView imageViewPreview1;
    private Button buttonCaptureImage2;
    private ImageView imageViewPreview2;
    private Button buttonUpload;
    private ProgressBar progressBarUpload;

    // CameraX
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private int currentImageSlotToCapture = 0; // 1 for image1, 2 for image2

    // URIs for captured images
    private Uri imageUri1 = null;
    private Uri imageUri2 = null;

    // Networking
    private final String BASE_URL = "https://sarsatiya.store/XJAAM-0.0.1-SNAPSHOT";

    private final String TOKEN_URL = BASE_URL + "/token/generate-token";
    private final String UPLOAD_URL = BASE_URL + "/upload";

    private final OkHttpClient okHttpClient = new OkHttpClient();
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private String authToken = null; // To store the obtained token


    private final ActivityResultLauncher<String> requestCameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show();
                    if (currentImageSlotToCapture != 0) {
                        startCameraAndCapture(currentImageSlotToCapture);
                    }
                } else {
                    Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
                    currentImageSlotToCapture = 0; // Reset
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI elements
        editTextTextData = findViewById(R.id.editTextTextData);
        buttonCaptureImage1 = findViewById(R.id.buttonCaptureImage1);
        imageViewPreview1 = findViewById(R.id.imageViewPreview1);
        buttonCaptureImage2 = findViewById(R.id.buttonCaptureImage2);
        imageViewPreview2 = findViewById(R.id.imageViewPreview2);
        buttonUpload = findViewById(R.id.buttonUpload);
        progressBarUpload = findViewById(R.id.progressBarUpload);

        // Initialize CameraX
        cameraExecutor = Executors.newSingleThreadExecutor();

        Log.d(TAG, "Activity created. Token URL: " + TOKEN_URL + ", Upload URL: " + UPLOAD_URL);

        // Attempt to get the token when the activity is created
        // IMPORTANT: Replace "admin" and "password" with actual user input or secure storage in a real app
        requestAuthToken("admin", "password");

        buttonCaptureImage1.setOnClickListener(v -> {
            currentImageSlotToCapture = 1;
            checkCameraPermissionAndCapture();
        });

        buttonCaptureImage2.setOnClickListener(v -> {
            currentImageSlotToCapture = 2;
            checkCameraPermissionAndCapture();
        });

        buttonUpload.setOnClickListener(v -> {
            if (authToken == null) {
                Toast.makeText(MainActivity.this, "Authenticating... Please try again shortly.", Toast.LENGTH_LONG).show();
                // Optionally, try to request the token again here if it's null
                requestAuthToken("admin", "password"); // Re-attempt or inform user
                return;
            }
            uploadData();
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void requestAuthToken(String username, String password) {
        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("username", username);
            jsonBody.put("password", password);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to create JSON for token request", e);
            mainThreadHandler.post(() -> Toast.makeText(MainActivity.this, "Error creating token request", Toast.LENGTH_SHORT).show());
            return;
        }

        RequestBody requestBody = RequestBody.create(jsonBody.toString(), JSON);
        Request request = new Request.Builder()
                .url(TOKEN_URL)
                .post(requestBody)
                .build();

        Log.d(TAG, "Requesting auth token from: " + TOKEN_URL);
        // Show progress or disable UI elements that require token
        // progressBarUpload.setVisibility(View.VISIBLE); // Example

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Token request failed: Network error", e);
                mainThreadHandler.post(() -> {
                    // progressBarUpload.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "Token request failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                // mainThreadHandler.post(() -> progressBarUpload.setVisibility(View.GONE));
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        String errorBodyString = responseBody != null ? responseBody.string() : "Unknown error";
                        Log.e(TAG, "Token request unsuccessful. Code: " + response.code() + ", Body: " + errorBodyString);
                        mainThreadHandler.post(() -> Toast.makeText(MainActivity.this, "Token request failed: " + response.code() + " " + errorBodyString, Toast.LENGTH_LONG).show());
                        return;
                    }

                    if (responseBody == null) {
                        Log.e(TAG, "Token response body is null");
                        mainThreadHandler.post(() -> Toast.makeText(MainActivity.this, "Token response empty", Toast.LENGTH_LONG).show());
                        return;
                    }

                    String responseBodyString = responseBody.string();
                    Log.d(TAG, "Token response successful: " + responseBodyString);

                    try {
                        JSONObject tokenResponseJson = new JSONObject(responseBodyString);
                        // ***** IMPORTANT: Adjust "token" or "id_token" to the actual key your server uses *****
                        if (tokenResponseJson.has("token")) {
                            authToken = tokenResponseJson.getString("token");
                        } else if (tokenResponseJson.has("id_token")) { // Common in Spring Security OAuth2
                            authToken = tokenResponseJson.getString("id_token");
                        } else {
                            Log.w(TAG, "Token field ('token' or 'id_token') not found in JSON response. Check server response format. Response: " + responseBodyString);
                            mainThreadHandler.post(() -> Toast.makeText(MainActivity.this, "Token field not found in response.", Toast.LENGTH_LONG).show());
                            return; // Don't proceed if token isn't found
                        }
                        Log.i(TAG, "Auth token obtained: " + authToken);
                        mainThreadHandler.post(() -> Toast.makeText(MainActivity.this, "Token obtained successfully!", Toast.LENGTH_SHORT).show());

                    } catch (JSONException e) {
                        Log.e(TAG, "Failed to parse token JSON response", e);
                        mainThreadHandler.post(() -> Toast.makeText(MainActivity.this, "Error parsing token response", Toast.LENGTH_LONG).show());
                    }
                }
            }
        });
    }

    private void checkCameraPermissionAndCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission already granted for slot " + currentImageSlotToCapture);
            startCameraAndCapture(currentImageSlotToCapture);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            Toast.makeText(this, "Camera permission is required to take pictures.", Toast.LENGTH_LONG).show();
            Log.d(TAG, "Showing rationale for camera permission for slot " + currentImageSlotToCapture);
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        } else {
            Log.d(TAG, "Requesting camera permission for slot " + currentImageSlotToCapture);
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCameraAndCapture(int slot) {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture);

                Log.d(TAG, "Camera bound, proceeding to take photo for slot " + slot);
                takePhoto(slot);

            } catch (Exception e) {
                Log.e(TAG, "CameraX Use case binding failed", e);
                Toast.makeText(this, "Camera setup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                currentImageSlotToCapture = 0;
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto(int slot) {
        if (imageCapture == null) {
            Log.e(TAG, "ImageCapture not initialized.");
            Toast.makeText(this, "Camera not ready.", Toast.LENGTH_SHORT).show();
            currentImageSlotToCapture = 0;
            return;
        }

        String name = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mytestapp");
        }

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(),
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
        ).build();

        Log.d(TAG, "Taking picture for slot " + slot + " with name " + name);

        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        Uri savedUri = outputFileResults.getSavedUri();
                        if (savedUri == null) {
                            Log.e(TAG, "Image save failed, URI is null for slot " + slot);
                            Toast.makeText(MainActivity.this, "Failed to save image", Toast.LENGTH_SHORT).show();
                            if (slot == 1) imageUri1 = null; else if (slot == 2) imageUri2 = null;
                            currentImageSlotToCapture = 0;
                            return;
                        }
                        Log.d(TAG, "Photo capture succeeded for slot " + slot + ": " + savedUri);
                        if (slot == 1) {
                            imageUri1 = savedUri;
                            Glide.with(MainActivity.this).load(imageUri1).into(imageViewPreview1);
                            imageViewPreview1.setVisibility(View.VISIBLE);
                        } else if (slot == 2) {
                            imageUri2 = savedUri;
                            Glide.with(MainActivity.this).load(imageUri2).into(imageViewPreview2);
                            imageViewPreview2.setVisibility(View.VISIBLE);
                        }
                        currentImageSlotToCapture = 0; // Reset
                        Toast.makeText(MainActivity.this, "Image " + slot + " captured!", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Photo capture failed for slot " + slot + ": " + exception.getMessage(), exception);
                        Toast.makeText(MainActivity.this, "Image capture failed: " + exception.getMessage(), Toast.LENGTH_LONG).show();
                        if (slot == 1) imageUri1 = null; else if (slot == 2) imageUri2 = null;
                        currentImageSlotToCapture = 0;
                    }
                }
        );
    }

    private void uploadData() {
        if (authToken == null) {
            Toast.makeText(this, "Authentication token not available. Cannot upload.", Toast.LENGTH_LONG).show();
            Log.w(TAG, "Upload attempt without auth token.");
            progressBarUpload.setVisibility(View.GONE);
            buttonUpload.setEnabled(true);
            // Optionally re-request token or guide user
            // requestAuthToken("admin", "password");
            return;
        }

        String textData = editTextTextData.getText().toString().trim();

        if (textData.isEmpty()) {
            Toast.makeText(this, "Please enter some text", Toast.LENGTH_SHORT).show();
            return;
        }
        if (imageUri1 == null || imageUri2 == null) {
            Toast.makeText(this, "Please capture both images", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBarUpload.setVisibility(View.VISIBLE);
        buttonUpload.setEnabled(false);
        Log.d(TAG, "Starting upload process. Text: '" + textData + "'");

        // --- You need PathUtil.java or an alternative to get file paths from Uris ---
        // This is a placeholder. Getting actual file path from Uri can be complex
        // and depends on how the Uri was obtained (MediaStore, file provider, etc.)
        // For MediaStore Uris from CameraX, directly using ContentResolver to open InputStream is safer.
        // However, OkHttp's RequestBody.create(File, MediaType) needs a File object.
        // If your PathUtil is robust, this might work.
        String path1 = PathUtil.getPath(this, imageUri1); // Ensure PathUtil is implemented correctly
        String path2 = PathUtil.getPath(this, imageUri2); // Ensure PathUtil is implemented correctly

        if (path1 == null || path2 == null) {
            Log.e(TAG, "Failed to get file paths from URIs. Path1: " + path1 + ", Path2: " + path2);
            Toast.makeText(this, "Error getting image file paths.", Toast.LENGTH_LONG).show();
            progressBarUpload.setVisibility(View.GONE);
            buttonUpload.setEnabled(true);
            return;
        }
        Log.d(TAG, "Image paths obtained. Path1: " + path1 + ", Path2: " + path2);

        File file1 = new File(path1);
        File file2 = new File(path2);

        if (!file1.exists() || !file1.canRead()) {
            Log.e(TAG, "Image file 1 does not exist or cannot be read: " + path1);
            Toast.makeText(this, "Error with image file 1.", Toast.LENGTH_LONG).show();
            progressBarUpload.setVisibility(View.GONE);
            buttonUpload.setEnabled(true);
            return;
        }
        if (!file2.exists() || !file2.canRead()) {
            Log.e(TAG, "Image file 2 does not exist or cannot be read: " + path2);
            Toast.makeText(this, "Error with image file 2.", Toast.LENGTH_LONG).show();
            progressBarUpload.setVisibility(View.GONE);
            buttonUpload.setEnabled(true);
            return;
        }

        try {
            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("text_data", textData)
                    .addFormDataPart(
                            "image1",
                            file1.getName(),
                            RequestBody.create(file1, MediaType.parse("image/jpeg")) // Assumes JPEG
                    )
                    .addFormDataPart(
                            "image2",
                            file2.getName(),
                            RequestBody.create(file2, MediaType.parse("image/jpeg")) // Assumes JPEG
                    )
                    .build();

            Request request = new Request.Builder()
                    .url(UPLOAD_URL)
                    .header("Authorization", "Bearer " + authToken) // Add the token here
                    .post(requestBody)
                    .build();

            Log.d(TAG, "Sending upload request to: " + UPLOAD_URL + " with auth token.");

            okHttpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e(TAG, "Upload failed: Network error", e);
                    mainThreadHandler.post(() -> {
                        progressBarUpload.setVisibility(View.GONE);
                        buttonUpload.setEnabled(true);
                        Toast.makeText(MainActivity.this, "Upload failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    final String responseBodyString;
                    try (ResponseBody responseBody = response.body()) { // Ensure responseBody is closed
                        responseBodyString = responseBody != null ? responseBody.string() : "Empty response body";
                    }

                    mainThreadHandler.post(() -> {
                        progressBarUpload.setVisibility(View.GONE);
                        buttonUpload.setEnabled(true);
                        if (response.isSuccessful()) {
                            Toast.makeText(MainActivity.this, "Upload successful: " + responseBodyString, Toast.LENGTH_LONG).show();
                            Log.d(TAG, "Upload successful. Response: " + responseBodyString);
                            // Clear fields after successful upload
                            editTextTextData.setText("");
                            imageViewPreview1.setImageDrawable(null);
                            imageViewPreview1.setVisibility(View.GONE);
                            imageViewPreview2.setImageDrawable(null);
                            imageViewPreview2.setVisibility(View.GONE);
                            imageUri1 = null;
                            imageUri2 = null;
                        } else {
                            Toast.makeText(MainActivity.this, "Upload failed: " + response.code() + " " + response.message() + " - " + responseBodyString, Toast.LENGTH_LONG).show();
                            Log.e(TAG, "Upload failed. Code: " + response.code() + ", Message: " + response.message() + ", Body: " + responseBodyString);
                            if (response.code() == 401 || response.code() == 403) {
                                Log.w(TAG, "Authentication error during upload. Token might be invalid/expired.");
                                authToken = null; // Clear the potentially invalid token
                                Toast.makeText(MainActivity.this, "Authentication failed. Please try again or re-login.", Toast.LENGTH_LONG).show();
                                // Optionally, try to get a new token automatically or redirect to login
                                // requestAuthToken("admin", "password");
                            }
                        }
                    });
                }
            });

        } catch (Exception e) { // Catch broader exceptions for request building
            Log.e(TAG, "Upload failed with exception during request building", e);
            mainThreadHandler.post(() -> {
                progressBarUpload.setVisibility(View.GONE);
                buttonUpload.setEnabled(true);
                Toast.makeText(MainActivity.this, "Upload error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }

    // --- Helper class PathUtil (Example - you might need a more robust version) ---
    // You should place this in its own PathUtil.java file for better organization
    // For simplicity, it's included here. Search for "Android PathUtil from Uri" for more complete examples.
    public static class PathUtil {
        public static String getPath(final android.content.Context context, final Uri uri) {
            // DocumentProvider
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && android.provider.DocumentsContract.isDocumentUri(context, uri)) {
                // ExternalStorageProvider
                if (isExternalStorageDocument(uri)) {
                    final String docId = android.provider.DocumentsContract.getDocumentId(uri);
                    final String[] split = docId.split(":");
                    final String type = split[0];

                    if ("primary".equalsIgnoreCase(type)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            return context.getExternalFilesDir(null) + "/" + split[1]; // Approximation, might not be universally correct
                        } else {
                            return android.os.Environment.getExternalStorageDirectory() + "/" + split[1];
                        }
                    }
                }
                // DownloadsProvider
                else if (isDownloadsDocument(uri)) {
                    final String id = android.provider.DocumentsContract.getDocumentId(uri);
                    if (id != null && id.startsWith("raw:")) {
                        return id.substring(4);
                    }
                    String[] contentUriPrefixesToTry = new String[]{
                            "content://downloads/public_downloads",
                            "content://downloads/my_downloads",
                            "content://downloads/all_downloads"
                    };
                    for (String contentUriPrefix : contentUriPrefixesToTry) {
                        try {
                            Uri contentUri = android.content.ContentUris.withAppendedId(Uri.parse(contentUriPrefix), Long.parseLong(id));
                            String path = getDataColumn(context, contentUri, null, null);
                            if (path != null) return path;
                        } catch (NumberFormatException e) {
                            // Ignore if id is not a number
                        }
                    }
                    // Fallback for certain devices/versions
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try (android.database.Cursor cursor = context.getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                            if (cursor != null && cursor.moveToFirst()) {
                                String fileName = cursor.getString(0);
                                File file = new File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), fileName);
                                if (file.exists()) return file.getAbsolutePath();
                            }
                        }
                    }

                }
                // MediaProvider
                else if (isMediaDocument(uri)) {
                    final String docId = android.provider.DocumentsContract.getDocumentId(uri);
                    final String[] split = docId.split(":");
                    final String type = split[0];

                    Uri contentUri = null;
                    if ("image".equals(type)) {
                        contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    } else if ("video".equals(type)) {
                        contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                    } else if ("audio".equals(type)) {
                        contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                    }

                    final String selection = "_id=?";
                    final String[] selectionArgs = new String[]{split[1]};

                    return getDataColumn(context, contentUri, selection, selectionArgs);
                }
            }
            // MediaStore (and general)
            else if ("content".equalsIgnoreCase(uri.getScheme())) {
                // Return the remote address
                if (isGooglePhotosUri(uri))
                    return uri.getLastPathSegment();
                return getDataColumn(context, uri, null, null);
            }
            // File
            else if ("file".equalsIgnoreCase(uri.getScheme())) {
                return uri.getPath();
            }
            return null;
        }

        public static String getDataColumn(android.content.Context context, Uri uri, String selection, String[] selectionArgs) {
            android.database.Cursor cursor = null;
            final String column = "_data";
            final String[] projection = {column};
            try {
                cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
                if (cursor != null && cursor.moveToFirst()) {
                    final int index = cursor.getColumnIndexOrThrow(column);
                    return cursor.getString(index);
                }
            } finally {
                if (cursor != null)
                    cursor.close();
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
            return "com.google.android.apps.photos.content".equals(uri.getAuthority());
        }
    }
}