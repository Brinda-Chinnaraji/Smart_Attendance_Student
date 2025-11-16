package com.codecatalyst.smartattendance.smartattendancestudent;

import com.codecatalyst.smartattendance.smartattendancestudent.ml.BitmapPreprocessor;
import com.codecatalyst.smartattendance.smartattendancestudent.ml.FaceNetModel;
import com.codecatalyst.smartattendance.smartattendancestudent.storage.FaceStorage;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;

import android.location.LocationManager;

import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.provider.Settings;

import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.common.util.concurrent.ListenableFuture;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "StudentBLE";
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1002;
    private static final int REQUEST_CAMERA_PERMISSION = 2003;

    // ✅ Stricter threshold for FaceNet distance
    private static final float FACE_MATCH_THRESHOLD = 0.65f;

    // BLE / Firestore
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private FirebaseFirestore db;

    // UI
    private TextView tvScanStatus, tvDetectedUUID, tvCourseInfo;
    private ProgressBar progressBar;
    private Button btnLogAttendance;

    // Overlay
    private View overlayContainer;
    private TextView overlayMessage;
    private Button btnOpenSettings;

    // Face verification UI
    private LinearLayout cardFaceVerification;
    private PreviewView previewFace;
    private View facePreviewContainer;
    private TextView tvFaceStatus;

    // Face verification engine
    private ExecutorService faceCameraExecutor;
    private ProcessCameraProvider faceCameraProvider;
    private FaceDetector faceDetector;
    private FaceNetModel faceNetModel;
    private float[] storedEmbedding;
    private boolean isAnalyzingFrame = false;

    // Data
    private String studentId;
    private String lastScannedUUID;
    private String activeSessionUUID;
    private String courseIdMatched;
    private String scheduleIdMatched;
    private boolean sessionResolved = false;

    private boolean isEnrolled = false;
    private boolean faceVerified = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        ImageView btnHistory = findViewById(R.id.btnHistory);
        btnHistory.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, AttendanceHistoryActivity.class);
            intent.putExtra("STUDENT_ID", studentId);
            startActivity(intent);
        });


        db = FirebaseFirestore.getInstance();

        // ---- Base UI ----
        tvScanStatus = findViewById(R.id.tvScanStatus);
        tvDetectedUUID = findViewById(R.id.tvDetectedUUID);
        tvCourseInfo = findViewById(R.id.tvCourseInfo);
        progressBar = findViewById(R.id.progressBarScanning);
        btnLogAttendance = findViewById(R.id.btnLogAttendance);
        btnLogAttendance.setEnabled(false);

        // Overlay UI
        overlayContainer = findViewById(R.id.overlayContainer);
        overlayMessage = findViewById(R.id.overlayMessage);
        btnOpenSettings = findViewById(R.id.btnOpenSettings);
        btnOpenSettings.setOnClickListener(v -> openSettingsScreen());

        // Face verification UI
        cardFaceVerification = findViewById(R.id.cardFaceVerification);
        previewFace = findViewById(R.id.previewFace);
        facePreviewContainer = findViewById(R.id.facePreviewContainer);
        tvFaceStatus = findViewById(R.id.tvFaceStatus);
        cardFaceVerification.setVisibility(View.GONE);

        // Student ID
        Intent intent = getIntent();
        studentId = intent.getStringExtra("STUDENT_ID");
        if (studentId == null || studentId.isEmpty()) {
            studentId = getSharedPreferences("StudentPrefs", MODE_PRIVATE)
                    .getString("STUDENT_ID", null);
        }
        Log.d(TAG, "MainActivity started with studentId = " + studentId);

        // Face embedding must exist (already enrolled)
        if (!FaceStorage.hasEmbedding(this)) {
            Intent enrollIntent = new Intent(this, FaceEnrollmentActivity.class);
            enrollIntent.putExtra("STUDENT_ID", studentId);
            startActivity(enrollIntent);
            finish();
            return;
        }
        storedEmbedding = FaceStorage.loadEmbedding(this);
        Log.d(TAG, "Stored embedding length = " +
                (storedEmbedding != null ? storedEmbedding.length : -1));

        // Init face detection & FaceNet
        FaceDetectorOptions fdOptions = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build();
        faceDetector = FaceDetection.getClient(fdOptions);
        faceNetModel = new FaceNetModel(this);
        faceCameraExecutor = Executors.newSingleThreadExecutor();

        // Initialize Bluetooth
        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = bluetoothManager.getAdapter();

        // Start monitoring BT + Location + BLE scanning
        checkSystemRequirements();

        btnLogAttendance.setOnClickListener(v -> logAttendance());
    }

    // ---------------------------------------------------------
    // SYSTEM REQUIREMENT CHECKS (Bluetooth + Location)
    // ---------------------------------------------------------
    private void checkSystemRequirements() {
        boolean btOn = isBluetoothEnabled();
        boolean locOn = isLocationEnabled();

        if (!btOn || !locOn) {
            showOverlay(btOn, locOn);
            stopBLEScan();
            return;
        }

        hideOverlay();
        checkBluetoothPermissions();
        startBLEScan();
    }

    private boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private void openSettingsScreen() {
        startActivity(new Intent(Settings.ACTION_SETTINGS));
    }

    private void showOverlay(boolean bluetoothOn, boolean locationOn) {
        overlayContainer.setVisibility(View.VISIBLE);
        StringBuilder msg = new StringBuilder("Please enable:\n");
        if (!bluetoothOn) msg.append("• Bluetooth\n");
        if (!locationOn) msg.append("• Location Services\n");
        overlayMessage.setText(msg.toString());
    }

    private void hideOverlay() {
        overlayContainer.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkSystemRequirements();
    }

    // ---------------------------------------------------------
    // BLUETOOTH PERMISSIONS
    // ---------------------------------------------------------
    private void checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this,
                            Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this,
                            Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.ACCESS_FINE_LOCATION
                        },
                        REQUEST_BLUETOOTH_PERMISSIONS);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_BLUETOOTH_PERMISSIONS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            boolean granted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) granted = false;
            }
            if (granted) startBLEScan();
        } else if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startFaceVerificationCamera();
            } else {
                tvFaceStatus.setText("Camera permission denied.");
            }
        }
    }

    // ---------------------------------------------------------
    // BLE SCANNING
    // ---------------------------------------------------------
    private void startBLEScan() {
        if (!isBluetoothEnabled() || !isLocationEnabled()) return;

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) return;

        tvScanStatus.setText("Scanning for nearby sessions...");
        progressBar.setVisibility(View.VISIBLE);
        tvDetectedUUID.setText("");
  btnLogAttendance.setEnabled(false);
        sessionResolved = false;
        activeSessionUUID = null;

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
            return;

        bluetoothLeScanner.startScan(scanCallback);
        Log.d(TAG, "BLE scanning started");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, @NonNull ScanResult result) {
            if (sessionResolved) return;

            if (result.getScanRecord() != null &&
                    result.getScanRecord().getServiceUuids() != null) {

                for (ParcelUuid parcelUuid : result.getScanRecord().getServiceUuids()) {
                    String uuid = parcelUuid.getUuid().toString();
                    lastScannedUUID = uuid;
                    tvDetectedUUID.setText(uuid);
                    checkActiveSessionInFirestore(uuid);
                }
            }
        }
    };

    private void stopBLEScan() {
        if (bluetoothLeScanner != null) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                return;
            bluetoothLeScanner.stopScan(scanCallback);
            progressBar.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------
    // CHECK ACTIVE SESSION IN FIRESTORE
    // ---------------------------------------------------------
    private void checkActiveSessionInFirestore(String scannedUuid) {
        if (sessionResolved) return;

        db.collectionGroup("Attendance")
                .get()
                .addOnSuccessListener(qs -> {
                    for (QueryDocumentSnapshot doc : qs) {
                        Map<String, Object> data = doc.getData();
                        if (data == null) continue;

                        for (Map.Entry<String, Object> e : data.entrySet()) {
                            if (!(e.getValue() instanceof Map)) continue;

                            Map<String, Object> session = (Map<String, Object>) e.getValue();
                            String storedUUID = (String) session.get("SessionUUID");
                            String status = (String) session.get("Status");

                            if ("Active".equals(status)) {
                                activeSessionUUID = storedUUID;

                                DocumentReference adc = doc.getReference();
                                scheduleIdMatched = adc.getParent().getParent().getId();
                                courseIdMatched = adc.getParent().getParent()
                                        .getParent().getParent().getId();

                                sessionResolved = true;
                                stopBLEScan();
                                tvScanStatus.setText("Session detected ✔");

                                verifyStudentEnrollment(courseIdMatched, scheduleIdMatched);
                                return;
                            }
                        }
                    }
                })
                .addOnFailureListener(e ->
                        Log.e(TAG, "Firestore error: " + e.getMessage()));
    }

    // ---------------------------------------------------------
    // VERIFY STUDENT ENROLLMENT
    // ---------------------------------------------------------
    private void verifyStudentEnrollment(String courseId, String scheduleId) {
        db.collection("Courses")
                .document(courseId)
                .collection("Schedule")
                .document(scheduleId)
                .get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) {
                        tvCourseInfo.setText("Schedule not found.");
                        isEnrolled = false;
                        updateLogAttendanceButton();
                        return;
                    }

                    List<String> enrolled = (List<String>) doc.get("StudentsEnrolled");

                    if (enrolled != null && enrolled.contains(studentId)) {
                        tvCourseInfo.setText("Course matched. You are enrolled ✔");
                        isEnrolled = true;
                        // Start face verification once we know student is enrolled
                        startFaceVerificationFlow();
                    } else {
                        tvCourseInfo.setText("Course matched but you are NOT enrolled ❌");
                        isEnrolled = false;
                        faceVerified = false;
                        stopFaceVerificationCamera();
                        cardFaceVerification.setVisibility(View.GONE);
                        updateLogAttendanceButton();
                    }
                });
    }

    // ---------------------------------------------------------
    // FACE VERIFICATION FLOW
    // ---------------------------------------------------------
    private void startFaceVerificationFlow() {
        if (storedEmbedding == null) {
            tvFaceStatus.setText("No stored face data. Please re-enroll.");
            return;
        }

        cardFaceVerification.setVisibility(View.VISIBLE);
        facePreviewContainer.setVisibility(View.VISIBLE);
        tvFaceStatus.setText("Align your face inside the circle...");

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
        } else {
            startFaceVerificationCamera();
        }
    }

    private void startFaceVerificationCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                faceCameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analysis.setAnalyzer(faceCameraExecutor, this::analyzeFaceFrame);

                CameraSelector selector = CameraSelector.DEFAULT_FRONT_CAMERA;

                preview.setSurfaceProvider(previewFace.getSurfaceProvider());

                faceCameraProvider.unbindAll();
                faceCameraProvider.bindToLifecycle(
                        this, selector, preview, analysis);

                Log.d(TAG, "Face verification camera started");

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera init error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopFaceVerificationCamera() {
        if (faceCameraProvider != null) {
            faceCameraProvider.unbindAll();
        }
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFaceFrame(@NonNull ImageProxy imageProxy) {

        if (isAnalyzingFrame || storedEmbedding == null) {
            imageProxy.close();
            return;
        }

        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        isAnalyzingFrame = true;

        InputImage img = InputImage.fromMediaImage(
                imageProxy.getImage(),
                imageProxy.getImageInfo().getRotationDegrees()
        );

        faceDetector.process(img)
                .addOnSuccessListener(faces -> {
                    if (faces == null || faces.isEmpty()) {
                        runOnUiThread(() ->
                                tvFaceStatus.setText("No face detected. Hold still..."));
                    } else {

                        Face face = faces.get(0);

                        // Convert frame correctly (NV21 → JPEG → Bitmap)
                        Bitmap frameBitmap = imageProxyToBitmap(imageProxy);
                        if (frameBitmap == null) {
                            isAnalyzingFrame = false;
                            imageProxy.close();
                            return;
                        }

                        // ✅ Mirror the camera frame (just like enrollment)
                        Bitmap mirrored = mirrorBitmap(frameBitmap);

                        // Crop same way as enrollment
                        Bitmap cropped = cropFace(mirrored, face.getBoundingBox());

                        // Preprocess & embed
                        float[] input = BitmapPreprocessor.preprocess(cropped);
                        float[] currentEmbedding = faceNetModel.getEmbedding(input);

                        float dist = l2Distance(storedEmbedding, currentEmbedding);

                        Log.d(TAG, "Face distance (verification) = " + dist);

                        if (dist < FACE_MATCH_THRESHOLD) {
                            onFaceVerified();
                        } else {
                            runOnUiThread(() ->
                                    tvFaceStatus.setText("Face not matched. Try again."));
                        }
                    }
                })
                .addOnFailureListener(e ->
                        Log.e(TAG, "Face detection error: " + e.getMessage()))
                .addOnCompleteListener(t -> {
                    isAnalyzingFrame = false;
                    imageProxy.close();
                });
    }

    private void onFaceVerified() {
        if (faceVerified) return;
        faceVerified = true;

        runOnUiThread(() -> {
            tvFaceStatus.setText("Face verified ✔");
            // Hide camera circle, keep the card as confirmation
            facePreviewContainer.setVisibility(View.GONE);
            stopFaceVerificationCamera();
            updateLogAttendanceButton();
        });
    }

    private void updateLogAttendanceButton() {
        boolean enable = (activeSessionUUID != null) && isEnrolled && faceVerified;
        btnLogAttendance.setEnabled(enable);
    }

    // --- Helpers for image conversion / cropping / distance ---
    @OptIn(markerClass = ExperimentalGetImage.class)
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {

        android.media.Image image = imageProxy.getImage();
        if (image == null) return null;

        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();

        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        byte[] y = new byte[yBuffer.remaining()];
        byte[] u = new byte[uBuffer.remaining()];
        byte[] v = new byte[vBuffer.remaining()];

        yBuffer.get(y);
        uBuffer.get(u);
        vBuffer.get(v);

        byte[] nv21 = new byte[y.length + u.length + v.length];

        // Copy Y
        System.arraycopy(y, 0, nv21, 0, y.length);

        // Copy VU (swap order)
        for (int i = 0; i < u.length; i += 2) {
            nv21[y.length + i] = v[i];
            nv21[y.length + i + 1] = u[i];
        }

        YuvImage yuvImage =
                new YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 90, out);
        byte[] jpeg = out.toByteArray();

        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
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

    private Bitmap mirrorBitmap(Bitmap original) {
        Matrix matrix = new Matrix();
        matrix.preScale(-1.0f, 1.0f);
        return Bitmap.createBitmap(original, 0, 0,
                original.getWidth(), original.getHeight(),
                matrix, true);
    }

    private float l2Distance(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return Float.MAX_VALUE;
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return (float) Math.sqrt(sum);
    }

    // ---------------------------------------------------------
    // LOG ATTENDANCE
    // ---------------------------------------------------------
    private void logAttendance() {
        if (activeSessionUUID == null ||
                courseIdMatched == null ||
                scheduleIdMatched == null ||
                !isEnrolled ||
                !faceVerified) {
            return;
        }

        String today = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? LocalDate.now().toString() : "unknown";

        DocumentReference doc = db.collection("Courses")
                .document(courseIdMatched)
                .collection("Schedule")
                .document(scheduleIdMatched)
                .collection("Attendance")
                .document(today);

        Map<String, Object> entry = new HashMap<>();
        entry.put("status", "Present");
        entry.put("timestamp", Timestamp.now());

        String path = activeSessionUUID + ".StudentAttendanceData." + studentId;

        doc.update(path, entry)
                .addOnSuccessListener(v -> btnLogAttendance.setEnabled(false))
                .addOnFailureListener(e ->
                        Log.e(TAG, "Failed to log attendance: " + e.getMessage()));
    }

    // ---------------------------------------------------------
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBLEScan();
        stopFaceVerificationCamera();
        if (faceCameraExecutor != null) {
            faceCameraExecutor.shutdown();
        }
    }
}
