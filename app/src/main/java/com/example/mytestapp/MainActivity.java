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

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Locale;
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
import okhttp3.logging.HttpLoggingInterceptor;
import okio.BufferedSource;
import okio.Okio;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // UI Elements
    private EditText editTextTextData; // Will be used for "productName"
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
    private Uri imageUri2 = null; // Still here, but not uploaded in the current version of uploadData

    // Networking
    private final String BASE_URL = "https://sarsatiya.store/XJAAM-0.0.1-SNAPSHOT"; // Replace if different
    private final String TOKEN_URL = BASE_URL + "/token/generate-token";
    private final String UPLOAD_URL = BASE_URL + "/uploadproducts";

    private OkHttpClient okHttpClient;
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private String authToken = null;


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
        editTextTextData = findViewById(R.id.editTextTextData); // This EditText will provide the "productName"
        buttonCaptureImage1 = findViewById(R.id.buttonCaptureImage1);
        imageViewPreview1 = findViewById(R.id.imageViewPreview1);
        buttonCaptureImage2 = findViewById(R.id.buttonCaptureImage2);
        imageViewPreview2 = findViewById(R.id.imageViewPreview2);
        buttonUpload = findViewById(R.id.buttonUpload);
        progressBarUpload = findViewById(R.id.progressBarUpload);

        cameraExecutor = Executors.newSingleThreadExecutor();

        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);

        okHttpClient = new OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .build();

        Log.d(TAG, "Activity created. Token URL: " + TOKEN_URL + ", Upload URL: " + UPLOAD_URL);
        requestAuthToken("admin", "password"); // Replace with actual credentials/logic

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
                requestAuthToken("admin", "password"); // Or your actual auth logic
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

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Token request failed: Network error", e);
                mainThreadHandler.post(() -> {
                    Toast.makeText(MainActivity.this, "Token request failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
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
                        if (tokenResponseJson.has("token")) {
                            authToken = tokenResponseJson.getString("token");
                        } else if (tokenResponseJson.has("id_token")) {
                            authToken = tokenResponseJson.getString("id_token");
                        } else {
                            Log.w(TAG, "Token field ('token' or 'id_token') not found in JSON response. Check server. Response: " + responseBodyString);
                            mainThreadHandler.post(() -> Toast.makeText(MainActivity.this, "Token field not found in response.", Toast.LENGTH_LONG).show());
                            return;
                        }
                        Log.i(TAG, "Auth token obtained: " + authToken.substring(0, Math.min(authToken.length(), 20)) + "...");
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
                Log.e(TAG, "CameraX Use case binding failed or error during camera setup for slot " + slot, e);
                Toast.makeText(this, "Failed to start camera.", Toast.LENGTH_SHORT).show();
                currentImageSlotToCapture = 0; // Reset
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto(int slot) {
        if (imageCapture == null) {
            Log.e(TAG, "ImageCapture is null, cannot take photo for slot " + slot);
            Toast.makeText(this, "Camera not ready.", Toast.LENGTH_SHORT).show();
            currentImageSlotToCapture = 0; // Reset
            return;
        }

        String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg"); // Capturing as JPEG
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MyTestApp");
        }

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions
                .Builder(getContentResolver(),
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
                .build();

        Log.d(TAG, "Attempting to take photo for slot: " + slot);
        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        Uri savedUri = outputFileResults.getSavedUri();
                        Log.i(TAG, "Photo capture succeeded for slot " + slot + ": " + savedUri);
                        if (slot == 1) {
                            imageUri1 = savedUri;
                            Glide.with(MainActivity.this).load(imageUri1).into(imageViewPreview1);
                        } else if (slot == 2) {
                            imageUri2 = savedUri;
                            Glide.with(MainActivity.this).load(imageUri2).into(imageViewPreview2);
                        }
                        currentImageSlotToCapture = 0; // Reset
                        Toast.makeText(MainActivity.this, "Image " + slot + " captured!", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Photo capture failed for slot " + slot + ": " + exception.getMessage(), exception);
                        Toast.makeText(MainActivity.this, "Failed to capture image " + slot, Toast.LENGTH_SHORT).show();
                        currentImageSlotToCapture = 0; // Reset
                    }
                }
        );
    }

    private RequestBody createRequestBodyFromUri(Uri contentUri, String defaultFileNameForLog) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(contentUri);
            if (inputStream == null) {
                Log.e(TAG, "Unable to open InputStream for URI: " + contentUri);
                return null;
            }
            byte[] fileBytes = null;
            try (BufferedSource bufferedSource = Okio.buffer(Okio.source(inputStream))) {
                fileBytes = bufferedSource.readByteArray();
            } // InputStream will be closed by Okio.source(inputStream).close() which is called by bufferedSource.close()
            // and try-with-resources ensures bufferedSource.close() is called.

            String mimeType = getContentResolver().getType(contentUri);
            if (mimeType == null) {
                mimeType = "image/jpeg"; // Fallback, consistent with camera capture
            }
            Log.d(TAG, "Creating RequestBody for URI: " + contentUri + " with MIME type: " + mimeType + " (Logged filename: " + defaultFileNameForLog + ")");
            return RequestBody.create(fileBytes, MediaType.parse(mimeType));
        } catch (IOException e) {
            Log.e(TAG, "IOException while creating RequestBody from URI: " + contentUri, e);
            return null;
        }
    }


    private void uploadData() {
        String productNameValue = editTextTextData.getText().toString().trim();

        if (productNameValue.isEmpty()) {
            Toast.makeText(this, "Please enter product name.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Focus on uploading only the first image to match the successful Postman request
        if (imageUri1 == null) {
            Toast.makeText(this, "Please capture image 1.", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG, "uploadData: productNameValue = " + productNameValue);
        Log.d(TAG, "uploadData: imageUri1 (for 'file' part) = " + imageUri1.toString());

        progressBarUpload.setVisibility(View.VISIBLE);
        buttonUpload.setEnabled(false);

        RequestBody image1Body = createRequestBodyFromUri(imageUri1, "image1_for_upload.jpg"); // default name for log

        if (image1Body == null) {
            Log.e(TAG, "Failed to create RequestBody for image1.");
            Toast.makeText(this, "Error preparing image for upload. Check logs.", Toast.LENGTH_LONG).show();
            progressBarUpload.setVisibility(View.GONE);
            buttonUpload.setEnabled(true);
            return;
        }

        MultipartBody.Builder multipartBodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("productName", productNameValue); // Key "productName"

        // The filename in addFormDataPart is sent to the server.
        // The KEY for the part MUST be "file" to match the successful Postman request.
        String imageFileNameForServer = "image_upload_" + System.currentTimeMillis() + ".jpg"; // Server will see this filename

        multipartBodyBuilder.addFormDataPart("file", imageFileNameForServer, image1Body); // Key "file"

        // imageUri2 is not being sent in this version to match Postman test.
        // If you need to send it, you'll need to know the server's expected key for the second file.
        /*
        if (imageUri2 != null) {
            RequestBody image2Body = createRequestBodyFromUri(imageUri2, "image2_for_upload.jpg");
            if (image2Body != null) {
                String image2FileNameForServer = "image2_upload_" + System.currentTimeMillis() + ".jpg";
                // multipartBodyBuilder.addFormDataPart("file2", image2FileNameForServer, image2Body); // Example if server expects "file2"
                Log.d(TAG, "Note: image2 is ready but not being added to this request version.");
            } else {
                 Log.w(TAG, "Could not create RequestBody for image2, not sending it.");
            }
        }
        */

        RequestBody requestBody = multipartBodyBuilder.build();

        Request.Builder requestBuilder = new Request.Builder()
                .url(UPLOAD_URL)
                .post(requestBody);

        if (authToken != null) {
            requestBuilder.addHeader("Authorization", "Bearer " + authToken);
            Log.d(TAG, "Uploading with Auth Token: Bearer " + authToken.substring(0, Math.min(authToken.length(), 20)) + "...");
        } else {
            Log.w(TAG, "Auth token is null during upload attempt. This shouldn't happen if button logic is correct.");
            Toast.makeText(this, "Authentication error. Please restart the app.", Toast.LENGTH_LONG).show();
            progressBarUpload.setVisibility(View.GONE);
            buttonUpload.setEnabled(true);
            return;
        }

        Request request = requestBuilder.build();

        Log.d(TAG, "Attempting to upload data to: " + UPLOAD_URL);

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
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                // Try to get the raw response body string for logging, even on failure
                String responseBodyString = "";
                // It's important to read the response body only once.
                // And ensure it's closed, which try-with-resources does.
                try (ResponseBody responseBody = response.body()) {
                    if (responseBody != null) {
                        responseBodyString = responseBody.string();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading response body string", e);
                    // responseBodyString will remain empty or be what was read before error
                }

                final String finalResponseBodyString = responseBodyString; // final for lambda

                mainThreadHandler.post(() -> { // Post UI updates to main thread
                    progressBarUpload.setVisibility(View.GONE);
                    buttonUpload.setEnabled(true);

                    if (!response.isSuccessful()) {
                        Log.e(TAG, "Upload failed. Code: " + response.code() + ", Message: " + response.message() + ", Body: " + finalResponseBodyString);
                        Toast.makeText(MainActivity.this, "Upload failed: " + response.code() + " " + finalResponseBodyString, Toast.LENGTH_LONG).show();
                    } else {
                        Log.i(TAG, "Upload successful: " + finalResponseBodyString);
                        Toast.makeText(MainActivity.this, "Upload successful! Response: " + finalResponseBodyString, Toast.LENGTH_SHORT).show();

                        editTextTextData.setText("");
                        imageViewPreview1.setImageResource(0);
                        // imageViewPreview2.setImageResource(0); // If you were clearing image2
                        imageUri1 = null;
                        // imageUri2 = null;
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}
