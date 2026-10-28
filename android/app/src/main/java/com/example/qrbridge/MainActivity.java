package com.example.qrbridge;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.zip.CRC32;
import java.util.zip.Inflater;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_REQ = 100;

    private PreviewView previewView;
    private TextView tvStatus, tvFp, tvProgress, tvInfo;
    private ProgressBar bar;
    private LinearLayout grid;
    private Button btnClear, btnSave;

    private final MultiFormatReader reader = new MultiFormatReader();
    private long lastScan = 0;

    private final Map<Integer, String> chunks = new HashMap<>();
    private String fp = null;
    private JSONObject fnChunk = null;
    private int total = 0;
    private String rxName = "";
    private long rxSize = 0;
    private byte[] lastFile = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.barcode_scanner);
        tvStatus = findViewById(R.id.tv_status);
        tvFp = findViewById(R.id.tv_fp);
        tvProgress = findViewById(R.id.tv_progress);
        tvInfo = findViewById(R.id.tv_info);
        bar = findViewById(R.id.bar);
        grid = findViewById(R.id.grid);
        btnClear = findViewById(R.id.btn_clear);
        btnSave = findViewById(R.id.btn_save);

        EnumMap<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        reader.setHints(hints);

        loadState();
        updateUI();

        btnClear.setOnClickListener(v -> clearAll());
        btnSave.setOnClickListener(v -> {
            if (lastFile != null) saveFile(lastFile, rxName);
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_REQ);
        }
    }

    private void startCamera() {
        try {
            Executor ex = ContextCompat.getMainExecutor(this);
            ProcessCameraProvider.getInstance(this).addListener(() -> {
                try {
                    ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                    Preview preview = new Preview.Builder().build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());

                    ImageAnalysis analysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();
                    analysis.setAnalyzer(ex, this::decodeFrame);

                    CameraSelector sel = new CameraSelector.Builder()
                            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                            .build();
                    provider.unbindAll();
                    provider.bindToLifecycle(this, sel, preview, analysis);
                    tvStatus.setText("对准二维码");
                } catch (Exception e) {
                    toast("相机启动失败: " + e.getMessage(), true);
                }
            }, ex);
        } catch (Exception e) {
            toast("相机初始化失败: " + e.getMessage(), true);
        }
    }

    private void decodeFrame(ImageProxy image) {
        long now = System.currentTimeMillis();
        if (now - lastScan < 160) {
            image.close();
            return;
        }
        lastScan = now;
        try {
            byte[] nv21 = yuv420ToNV21(image);
            int w = image.getWidth(), h = image.getHeight();
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, w, h), 90, baos);
            Bitmap bmp = BitmapFactory.decodeByteArray(baos.toByteArray(), 0, baos.size());
            if (bmp != null) {
                int[] px = new int[w * h];
                bmp.getPixels(px, 0, w, 0, 0, w, h);
                RGBLuminanceSource src = new RGBLuminanceSource(w, h, px);
                BinaryBitmap bb = new BinaryBitmap(new HybridBinarizer(src));
                try {
                    Result r = reader.decodeWithState(bb);
                    String t = r.getText();
                    if (t != null && !t.isEmpty()) handle(t);
                } catch (Exception ignore) {
                }
                bmp.recycle();
            }
        } catch (Exception ignore) {
        } finally {
            image.close();
        }
    }

    private static byte[] yuv420ToNV21(ImageProxy image) {
        int w = image.getWidth(), h = image.getHeight();
        ByteBuffer yB = image.getPlanes()[0].getBuffer();
        ByteBuffer uB = image.getPlanes()[1].getBuffer();
        ByteBuffer vB = image.getPlanes()[2].getBuffer();
        int yRow = image.getPlanes()[0].getRowStride();
        int uvRow = image.getPlanes()[1].getRowStride();
        int uvPix = image.getPlanes()[1].getPixelStride();
        int ySize = w * h;
        byte[] out = new byte[ySize + w * h / 2];
        yB.rewind();
        if (yRow == w) {
            yB.get(out, 0, ySize);
        } else {
            for (int y = 0; y < h; y++) {
                yB.get(out, y * w, w);
                yB.position(Math.min(yB.limit(), yB.position() + (yRow - w)));
            }
        }
        int pos = ySize;
        for (int y = 0; y < h / 2; y++) {
            for (int x = 0; x < w / 2; x++) {
                int uOff = y * uvRow + x * uvPix;
                int vOff = y * image.getPlanes()[2].getRowStride() + x * image.getPlanes()[2].getPixelStride();
                out[pos++] = vB.get(vOff);
                out[pos++] = uB.get(uOff);
            }
        }
        return out;
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] p, @NonNull int[] g) {
        if (req == CAMERA_REQ && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "需要摄像头权限才能扫码", Toast.LENGTH_LONG).show();
        }
    }

    // ===== 处理扫到的二维码 =====
    private void handle(String raw) {
        try {
            String s = raw.trim();
            int a = s.indexOf('{');
            int z = s.lastIndexOf('}');
            if (a < 0 || z < 0) return;
            s = s.substring(a, z + 1);
            JSONObject c = new JSONObject(s);

            if ("fn".equals(c.optString("t"))) {
                String f = c.getString("f");
                String n = c.getString("n");
                long sz = c.getLong("s");
                long ts = c.optLong("ts", 0);
                long tc = c.optLong("tc", 0);
                String calc = crc32("{\"t\":\"fn\",\"f\":\"" + f + "\",\"n\":\"" + n
                        + "\",\"s\":" + sz + ",\"ts\":" + ts + ",\"tc\":" + tc + "}");
                if (!calc.equalsIgnoreCase(c.getString("h"))) {
                    toast("文件名校验失败", true);
                    return;
                }
                if (fp != null && !fp.equals(f)) {
                    toast("文件指纹不匹配", true);
                    return;
                }
                fnChunk = c;
                fp = f;
                total = (int) tc;
                rxName = decodeName(n);
                rxSize = sz;
                saveState();
                toast("文件名分片已收");
                tvStatus.setText("文件名: " + rxName);
            } else {
                int i = c.getInt("t");          // 总分片数
                int idx = c.getInt("i");         // 分片索引
                String ch = c.getString("f");    // 文件指纹
                String d = c.getString("d");     // Base45 数据
                String h = c.getString("h");     // CRC32
                if (fp == null) {
                    fp = ch;
                } else if (!fp.equals(ch)) {
                    toast("不是同一文件", true);
                    return;
                }
                String calc = crc32(d);
                if (!calc.equalsIgnoreCase(h)) {
                    toast("分片" + (idx + 1) + "校验失败", true);
                    return;
                }
                if (chunks.containsKey(idx)) {
                    toast("分片" + (idx + 1) + "已有");
                    return;
                }
                if (total == 0) total = i;
                chunks.put(idx, d);
                saveState();
                toast("数据 " + (idx + 1) + "/" + i);
            }
            updateUI();
            checkDone();
        } catch (Exception e) {
            toast("处理失败", true);
        }
    }

    private void checkDone() {
        if (fnChunk != null && total > 0 && chunks.size() >= total) {
            tvStatus.setText("全部收齐，正在重组…");
            new Thread(this::assemble).start();
        }
    }

    // ===== 重组：Base45 解码 -> zlib 解压 -> 存文件 =====
    private void assemble() {
        try {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (int i = 0; i < total; i++) {
                String d = chunks.get(i);
                if (d == null) throw new Exception("缺少分片" + (i + 1));
                all.write(base45Decode(d));
            }
            byte[] out = inflate(all.toByteArray());
            lastFile = out;
            final String info = "文件名: " + rxName + "\n大小: " + fmt(out.length)
                    + "\n类型: " + ftype(rxName) + "\n分片数: " + total;
            runOnUiThread(() -> {
                tvStatus.setText("重组完成");
                tvInfo.setText(info);
                btnSave.setEnabled(true);
                btnSave.setVisibility(android.view.View.VISIBLE);
                toast("重组完成，已可保存");
            });
            saveFile(out, rxName);
        } catch (Exception e) {
            runOnUiThread(() -> toast("重组失败: " + e.getMessage(), true));
        }
    }

    private void saveFile(byte[] data, String name) {
        name = safeName(name);
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, guessMime(name));
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/QRBridge");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(data);
                }
                toast("已保存: 下载/QRBridge/" + name);
                return;
            }
        } catch (Exception ignore) { }
        try {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "QRBridge");
            dir.mkdirs();
            File f = new File(dir, name);
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write(data);
            }
            toast("已保存: " + f.getAbsolutePath());
        } catch (Exception e2) {
            toast("保存失败: " + e2.getMessage(), true);
        }
    }

    // ===== 工具 =====
    private static String crc32(String data) {
        CRC32 c = new CRC32();
        c.update(data.getBytes(StandardCharsets.UTF_8));
        String s = Long.toString(c.getValue() & 0xFFFFFFFFL, 36);
        while (s.length() < 5) s = "0" + s;
        return s.substring(s.length() - 5);
    }

    private static byte[] base45Decode(String s) {
        final String A = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
        int[] alph = new int[128];
        for (int k = 0; k < A.length(); k++) alph[A.charAt(k)] = k;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < s.length()) {
            int c0 = alph[s.charAt(i)];
            if (i + 2 < s.length()) {
                int c1 = alph[s.charAt(i + 1)];
                int c2 = alph[s.charAt(i + 2)];
                int val = c0 + c1 * 45 + c2 * 45 * 45;
                out.write(val & 0xFF);
                out.write((val >> 8) & 0xFF);
                i += 3;
            } else if (i + 1 < s.length()) {
                int c1 = alph[s.charAt(i + 1)];
                int val = c0 + c1 * 45;
                out.write(val & 0xFF);
                i += 2;
            } else {
                throw new IllegalArgumentException("Base45 长度错误");
            }
        }
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] comp) throws Exception {
        Inflater inf = new Inflater();   // zlib 格式（pako 默认）
        inf.setInput(comp);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.finished() || inf.needsDictionary()) break;
                    if (inf.needsInput()) break;
                }
                bos.write(buf, 0, n);
            }
        } finally {
            inf.end();
        }
        return bos.toByteArray();
    }

    private static String decodeName(String b64) {
        try {
            return new String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return b64;
        }
    }

    private static String safeName(String n) {
        if (n == null || n.isEmpty()) n = "file.bin";
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String guessMime(String n) {
        String e = n.toLowerCase().contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
        switch (e) {
            case "txt":  return "text/plain";
            case "pdf":  return "application/pdf";
            case "png":  return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "gif":  return "image/gif";
            case "zip":  return "application/zip";
            case "json": return "application/json";
            case "mp4":  return "video/mp4";
            case "mp3":  return "audio/mpeg";
            default:     return "application/octet-stream";
        }
    }

    private static String fmt(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / 1048576.0);
    }

    private static String ftype(String n) {
        String e = n.toLowerCase().contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
        switch (e) {
            case "txt":  return "文本";
            case "pdf":  return "PDF";
            case "jpg":
            case "jpeg": return "图片";
            case "png":  return "图片";
            case "gif":  return "图片";
            case "zip":
            case "rar":  return "压缩包";
            case "doc":
            case "docx": return "Word";
            case "xls":
            case "xlsx": return "Excel";
            case "ppt":
            case "pptx": return "PPT";
            case "mp3":  return "音频";
            case "mp4":  return "视频";
            case "js":   return "脚本";
            case "html": return "网页";
            case "css":  return "样式";
            case "json": return "JSON";
            default:     return "文件";
        }
    }

    // ===== 进度持久化（断点续传）=====
    private void saveState() {
        try {
            JSONObject o = new JSONObject();
            o.put("fp", fp == null ? "" : fp);
            o.put("total", total);
            o.put("name", rxName);
            o.put("size", rxSize);
            if (fnChunk != null) o.put("fn", fnChunk.toString());
            JSONObject data = new JSONObject();
            for (Map.Entry<Integer, String> e : chunks.entrySet()) {
                data.put(String.valueOf(e.getKey()), e.getValue());
            }
            o.put("data", data);
            File f = new File(getFilesDir(), "state.json");
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignore) { }
    }

    private void loadState() {
        try {
            File f = new File(getFilesDir(), "state.json");
            if (!f.exists()) return;
            byte[] b = new byte[(int) f.length()];
            try (FileInputStream is = new FileInputStream(f)) {
                int r = is.read(b);
            }
            JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
            String p = o.optString("fp", "");
            fp = p.isEmpty() ? null : p;
            total = o.optInt("total", 0);
            rxName = o.optString("name", "");
            rxSize = o.optLong("size", 0);
            if (o.has("fn")) fnChunk = new JSONObject(o.getString("fn"));
            JSONObject data = o.optJSONObject("data");
            if (data != null) {
                JSONArray ks = data.names();
                if (ks != null) {
                    for (int i = 0; i < ks.length(); i++) {
                        String key = ks.getString(i);
                        chunks.put(Integer.parseInt(key), data.getString(key));
                    }
                }
            }
            if (fp != null) tvStatus.setText("已恢复上次进度");
        } catch (Exception ignore) { }
    }

    private void clearAll() {
        chunks.clear();
        fnChunk = null;
        fp = null;
        total = 0;
        rxName = "";
        rxSize = 0;
        lastFile = null;
        File f = new File(getFilesDir(), "state.json");
        if (f.exists()) f.delete();
        btnSave.setVisibility(android.view.View.GONE);
        btnSave.setEnabled(false);
        tvInfo.setText("");
        tvFp.setText("");
        tvStatus.setText("等待扫描");
        updateUI();
    }

    private void updateUI() {
        int got = chunks.size() + (fnChunk != null ? 1 : 0);
        int all = total + (fnChunk != null ? 1 : 0);
        int pct = all > 0 ? Math.round(got * 100f / all) : 0;
        tvProgress.setText("已收 " + got + "/" + all + "  (" + pct + "%)");
        bar.setProgress(pct);
        if (fp != null) tvFp.setText("文件指纹: " + fp);

        grid.removeAllViews();
        for (int i = 0; i < total; i++) {
            TextView t = new TextView(this);
            t.setText(String.valueOf(i + 1));
            t.setTextSize(12);
            t.setPadding(10, 10, 10, 10);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(4, 4, 4, 4);
            t.setLayoutParams(lp);
            boolean ok = chunks.containsKey(i);
            t.setBackgroundColor(ok ? android.graphics.Color.parseColor("#27ae60") : android.graphics.Color.parseColor("#ecf0f1"));
            t.setTextColor(ok ? android.graphics.Color.WHITE : android.graphics.Color.DKGRAY);
            grid.addView(t);
        }
        if (fnChunk != null) {
            TextView t = new TextView(this);
            t.setText("📄");
            t.setTextSize(12);
            t.setPadding(10, 10, 10, 10);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(4, 4, 4, 4);
            t.setLayoutParams(lp);
            t.setBackgroundColor(android.graphics.Color.parseColor("#1a5276"));
            t.setTextColor(android.graphics.Color.WHITE);
            grid.addView(t);
        }
    }

    private void toast(String m) {
        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
    }

    private void toast(String m, boolean err) {
        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
    }
}
