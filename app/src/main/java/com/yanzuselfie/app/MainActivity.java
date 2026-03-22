package com.yanzuselfie.app;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ExecutorService cameraExecutor;
    private PreviewView previewView;
    private Bitmap yanzuBitmap;

    private final String[] REQUIRED_PERMISSIONS = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
        ? new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}
        : new String[]{Manifest.permission.CAMERA};
    private ActivityResultLauncher<String[]> activityResultLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        Button captureButton = findViewById(R.id.captureButton);
        ImageButton infoButton = findViewById(R.id.infoButton);
        ImageButton galleryButton = findViewById(R.id.galleryButton);

        yanzuBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.yanzu);
        Bitmap mosaicBitmap = createMosaicBitmap(yanzuBitmap);
        if (mosaicBitmap != null) {
            galleryButton.setImageBitmap(mosaicBitmap);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();

        activityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            permissions -> {
                boolean permissionGranted = true;
                for (String permission : REQUIRED_PERMISSIONS) {
                    if (!permissions.getOrDefault(permission, false)) {
                        permissionGranted = false;
                    }
                }
                if (!permissionGranted) {
                    Toast.makeText(this, "需要相机权限才能预览", Toast.LENGTH_SHORT).show();
                } else {
                    startCamera();
                }
            }
        );

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            activityResultLauncher.launch(REQUIRED_PERMISSIONS);
        }

        captureButton.setOnClickListener(v -> {
            Toast.makeText(this, "拍照中...", Toast.LENGTH_SHORT).show();
            saveYanzuPhoto();
        });

        infoButton.setOnClickListener(v -> showInfoDialog());
        galleryButton.setOnClickListener(v -> openSystemGallery());
    }

    private Bitmap createMosaicBitmap(Bitmap source) {
        if (source == null) {
            return null;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        int blockSize = 24;
        int tinyWidth = Math.max(1, width / blockSize);
        int tinyHeight = Math.max(1, height / blockSize);
        Bitmap tiny = Bitmap.createScaledBitmap(source, tinyWidth, tinyHeight, false);
        return Bitmap.createScaledBitmap(tiny, width, height, false);
    }

    private void openSystemGallery() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "未找到可用相册应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void showInfoDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_info, null, false);
        TextView versionText = dialogView.findViewById(R.id.versionText);
        String versionInfo = getString(
            R.string.version_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        );
        versionText.setText(versionInfo);

        new AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(R.string.info_close, null)
            .show();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                cameraProvider.unbindAll();

                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview
                );

            } catch (Exception exc) {
                Toast.makeText(this, "相机启动失败: " + exc.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void saveYanzuPhoto() {
        new Thread(() -> {
            Uri imageUri = null;
            String legacyFilePath = null;
            try {
                if (yanzuBitmap == null) {
                    throw new IllegalStateException("图片资源加载失败");
                }

                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "YanzuSelfie_" + timestamp + ".jpg");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                } else {
                    java.io.File cameraDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM + "/Camera");
                    if (!cameraDir.exists() && !cameraDir.mkdirs()) {
                        throw new IllegalStateException("创建相册目录失败");
                    }
                    java.io.File imageFile = new java.io.File(cameraDir, "YanzuSelfie_" + timestamp + ".jpg");
                    legacyFilePath = imageFile.getAbsolutePath();
                    values.put(MediaStore.Images.Media.DATA, legacyFilePath);
                }

                imageUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (imageUri == null) {
                    throw new IllegalStateException("创建相册条目失败");
                }

                OutputStream fos = getContentResolver().openOutputStream(imageUri);
                if (fos == null) {
                    throw new IllegalStateException("无法打开输出流");
                }

                boolean success = yanzuBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                fos.flush();
                fos.close();
                if (!success) {
                    throw new IllegalStateException("图片写入失败");
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(imageUri, done, null, null);
                } else if (legacyFilePath != null) {
                    MediaScannerConnection.scanFile(this, new String[]{legacyFilePath}, new String[]{"image/jpeg"}, null);
                }

                runOnUiThread(() -> {
                    Toast.makeText(this, "照片已保存到系统相册", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                e.printStackTrace();
                if (imageUri != null) {
                    getContentResolver().delete(imageUri, null, null);
                }
                runOnUiThread(() -> {
                    Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}