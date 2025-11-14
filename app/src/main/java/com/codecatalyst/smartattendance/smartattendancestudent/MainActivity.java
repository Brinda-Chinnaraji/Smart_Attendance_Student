package com.codecatalyst.smartattendance.smartattendancestudent;

import com.codecatalyst.smartattendance.smartattendancestudent.storage.FaceStorage;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "StudentBLE";
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1002;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private FirebaseFirestore db;

    private TextView tvScanStatus, tvDetectedUUID, tvCourseInfo;
    private ProgressBar progressBar;
    private Button btnLogAttendance;

    private String studentId;

    private String lastScannedUUID;
    private String activeSessionUUID;
    private String courseIdMatched;
    private String scheduleIdMatched;
    private boolean sessionResolved = false;

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

        db = FirebaseFirestore.getInstance();

        // UI setup
        tvScanStatus = findViewById(R.id.tvScanStatus);
        tvDetectedUUID = findViewById(R.id.tvDetectedUUID);
        tvCourseInfo = findViewById(R.id.tvCourseInfo);
        progressBar = findViewById(R.id.progressBarScanning);
        btnLogAttendance = findViewById(R.id.btnLogAttendance);
        btnLogAttendance.setEnabled(false);

        // Retrieve student ID
        Intent intent = getIntent();
        studentId = intent.getStringExtra("STUDENT_ID");

        if (studentId == null || studentId.isEmpty()) {
            studentId = getSharedPreferences("StudentPrefs", MODE_PRIVATE)
                    .getString("STUDENT_ID", null);
            Log.d(TAG, "Recovered studentId from SharedPreferences: " + studentId);
        }

        Log.d(TAG, "✅ MainActivity started with studentId = " + studentId);

        // ✅ Check if face data exists
        if (!FaceStorage.hasEmbedding(this)) {
            Toast.makeText(this, "No face data found. Please register your face first.", Toast.LENGTH_LONG).show();
            Intent enrollIntent = new Intent(this, FaceEnrollmentActivity.class);
            enrollIntent.putExtra("STUDENT_ID", studentId);
            startActivity(enrollIntent);
            finish();
            return;
        }

        // Initialize Bluetooth
        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = bluetoothManager.getAdapter();

        checkBluetoothPermissions();
        startBLEScan();

        btnLogAttendance.setOnClickListener(v -> logAttendance());
    }

    // --------------------------------------------------
    // Bluetooth permissions
    // --------------------------------------------------
    private void checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.ACCESS_FINE_LOCATION
                        },
                        REQUEST_BLUETOOTH_PERMISSIONS);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_BLUETOOTH_PERMISSIONS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            startBLEScan();
        }
    }

    // --------------------------------------------------
    // BLE scanning logic
    // --------------------------------------------------
    private void startBLEScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Enable Bluetooth first", Toast.LENGTH_SHORT).show();
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            Toast.makeText(this, "BLE Scanner not available", Toast.LENGTH_SHORT).show();
            return;
        }

        tvScanStatus.setText("🔍 Scanning for nearby sessions...");
        progressBar.setVisibility(View.VISIBLE);
        tvDetectedUUID.setText("");
        tvCourseInfo.setText("");
        btnLogAttendance.setEnabled(false);
        sessionResolved = false;
        activeSessionUUID = null;
        courseIdMatched = null;
        scheduleIdMatched = null;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
            return;

        bluetoothLeScanner.startScan(scanCallback);
        Log.d(TAG, "🟢 BLE scanning started...");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, @NonNull ScanResult result) {
            super.onScanResult(callbackType, result);

            if (sessionResolved) return;

            if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                for (ParcelUuid parcelUuid : result.getScanRecord().getServiceUuids()) {
                    String uuid = parcelUuid.getUuid().toString();
                    Log.d(TAG, "🔹 Detected BLE UUID: " + uuid);
                    lastScannedUUID = uuid;
                    tvDetectedUUID.setText(uuid);
                    checkActiveSessionInFirestore(uuid);
                }
            }
        }
    };

    private void stopBLEScan() {
        if (bluetoothLeScanner != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                return;
            bluetoothLeScanner.stopScan(scanCallback);
            Log.d(TAG, "🛑 BLE scanning stopped.");
            progressBar.setVisibility(View.GONE);
        }
    }

    // --------------------------------------------------
    // Check Firestore for active session
    // --------------------------------------------------
    private void checkActiveSessionInFirestore(String scannedUuid) {
        if (sessionResolved) return;

        Log.d(TAG, "🔥 Checking Firestore for active sessions…");

        db.collectionGroup("Attendance")
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    for (QueryDocumentSnapshot document : querySnapshot) {
                        Map<String, Object> data = document.getData();
                        if (data == null) continue;

                        for (Map.Entry<String, Object> entry : data.entrySet()) {
                            if (!(entry.getValue() instanceof Map)) continue;

                            Map<String, Object> session = (Map<String, Object>) entry.getValue();
                            String storedUUID = (String) session.get("SessionUUID");
                            String status = (String) session.get("Status");

                            Log.d(TAG, "🗂 Session in DB – SessionUUID: " + storedUUID +
                                    " | Status: " + status);

                            if ("Active".equals(status)) {
                                activeSessionUUID = storedUUID;

                                DocumentReference attendanceDoc = document.getReference();
                                scheduleIdMatched = attendanceDoc.getParent().getParent().getId();
                                courseIdMatched = attendanceDoc.getParent().getParent()
                                        .getParent().getParent().getId();

                                Log.d(TAG, "✅ Active session matched – SessionUUID: " + activeSessionUUID +
                                        ", courseId: " + courseIdMatched +
                                        ", scheduleId: " + scheduleIdMatched);

                                sessionResolved = true;
                                stopBLEScan();
                                tvScanStatus.setText("✅ Session detected!");
                                verifyStudentEnrollment(courseIdMatched, scheduleIdMatched);
                                return;
                            }
                        }
                    }

                    if (!sessionResolved)
                        tvCourseInfo.setText("No active attendance session found.");
                })
                .addOnFailureListener(e ->
                        Log.e(TAG, "❌ Error checking Firestore: " + e.getMessage()));
    }

    // --------------------------------------------------
    // Verify enrollment
    // --------------------------------------------------
    private void verifyStudentEnrollment(String courseId, String scheduleId) {
        db.collection("Courses")
                .document(courseId)
                .collection("Schedule")
                .document(scheduleId)
                .get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        List<String> enrolled = (List<String>) doc.get("StudentsEnrolled");
                        if (enrolled != null) {
                            Log.d(TAG, "📜 StudentsEnrolled for schedule " + scheduleId + ":");
                            for (String id : enrolled)
                                Log.d(TAG, "   - " + id);

                            if (enrolled.contains(studentId)) {
                                tvCourseInfo.setText("Course matched.\nYou are enrolled.\nTap below to log attendance.");
                                btnLogAttendance.setEnabled(true);
                            } else {
                                tvCourseInfo.setText("Course matched, but you are NOT enrolled.");
                                btnLogAttendance.setEnabled(false);
                            }
                        } else {
                            tvCourseInfo.setText("No StudentsEnrolled list found for this schedule.");
                        }
                    } else {
                        tvCourseInfo.setText("Schedule document not found.");
                    }
                })
                .addOnFailureListener(e ->
                        Log.e(TAG, "❌ Enrollment check failed: " + e.getMessage()));
    }

    // --------------------------------------------------
    // Log attendance
    // --------------------------------------------------
    private void logAttendance() {
        if (activeSessionUUID == null || courseIdMatched == null || scheduleIdMatched == null) {
            Toast.makeText(this, "No valid active session to log.", Toast.LENGTH_SHORT).show();
            return;
        }

        String today = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? LocalDate.now().toString()
                : "unknown";

        DocumentReference attendanceDoc = db.collection("Courses")
                .document(courseIdMatched)
                .collection("Schedule")
                .document(scheduleIdMatched)
                .collection("Attendance")
                .document(today);

        Map<String, Object> studentInfo = new HashMap<>();
        studentInfo.put("status", "Present");
        studentInfo.put("timestamp", Timestamp.now());

        String fieldPath = activeSessionUUID + ".StudentAttendanceData." + studentId;

        attendanceDoc.update(fieldPath, studentInfo)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Attendance logged successfully!", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "✅ Attendance saved for student " + studentId +
                            " under session " + activeSessionUUID);
                    btnLogAttendance.setEnabled(false);
                })
                .addOnFailureListener(e ->
                        Log.e(TAG, "❌ Failed to log attendance: " + e.getMessage()));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBLEScan();
    }
}
