package com.codecatalyst.smartattendance.smartattendancestudent;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.codecatalyst.smartattendance.smartattendancestudent.ml.BitmapPreprocessor;
import com.codecatalyst.smartattendance.smartattendancestudent.ml.FaceNetModel;
import com.codecatalyst.smartattendance.smartattendancestudent.storage.FaceStorage;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class FaceEnrollmentActivity extends AppCompatActivity {

    private static final String TAG = "FaceEnroll";
    private static final int CAMERA_PERMISSION_CODE = 1001;

    private PreviewView previewView;
    private Button btnCapture;

    private ImageCapture imageCapture;
    private FaceDetector faceDetector;
    private FaceNetModel faceNetModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_face_enrollment);

        previewView = findViewById(R.id.previewView);
        btnCapture = findViewById(R.id.btnCapture);

        // Init FaceNet model
        faceNetModel = new FaceNetModel(this);

        // ML Kit face detector
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build();
        faceDetector = FaceDetection.getClient(options);

        // Permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_CODE
            );
        } else {
            startCamera();
        }

        btnCapture.setOnClickListener(v -> captureFace());
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

                Log.d(TAG, "✅ Camera started");

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "❌ Camera init error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void captureFace() {
        if (imageCapture == null) return;

        imageCapture.takePicture(
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                        Bitmap bitmap = imageProxyToBitmap(imageProxy);
                        if (bitmap == null) {
                            imageProxy.close();
                            Toast.makeText(FaceEnrollmentActivity.this,
                                    "Capture failed", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        InputImage img = InputImage.fromBitmap(bitmap, 0);
                        faceDetector.process(img)
                                .addOnSuccessListener(faces -> handleFaces(bitmap, faces))
                                .addOnFailureListener(e ->
                                        Log.e(TAG, "Face detection failed: " + e.getMessage()))
                                .addOnCompleteListener(t -> imageProxy.close());
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exc) {
                        Log.e(TAG, "Image capture failed: " + exc.getMessage());
                    }
                });
    }

    private void handleFaces(Bitmap original, List<Face> faces) {
        if (faces == null || faces.isEmpty()) {
            Toast.makeText(this, "No face detected. Try again.", Toast.LENGTH_SHORT).show();
            return;
        }

        Face face = faces.get(0);
        Rect bounds = face.getBoundingBox();
        Bitmap cropped = cropFace(original, bounds);

        // Preprocess + get embedding
        float[] input = BitmapPreprocessor.preprocess(cropped);
        float[] embedding = faceNetModel.getEmbedding(input);

        // Save embedding locally
        FaceStorage.saveEmbedding(this, embedding);
        Toast.makeText(this, "✅ Face registered successfully!", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(FaceEnrollmentActivity.this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    // Very simple Y plane to bitmap conversion (works enough for ML Kit)
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        ImageProxy.PlaneProxy plane = imageProxy.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private Bitmap cropFace(Bitmap src, Rect rect) {
        int left = Math.max(rect.left, 0);
        int top = Math.max(rect.top, 0);
        int width = Math.min(rect.width(), src.getWidth() - left);
        int height = Math.min(rect.height(), src.getHeight() - top);
        try {
            return Bitmap.createBitmap(src, left, top, width, height);
        } catch (Exception e) {
            Log.e(TAG, "Crop failed, using full image: " + e.getMessage());
            return src;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
