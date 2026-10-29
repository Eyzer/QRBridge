package com.example.qrbridge;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
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
import com.google.zxing.Binarizer;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;
import java.util.zip.Inflater;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_REQ = 100;
    private static final String BUILD = "v1.4";

    private PreviewView previewView;
    private TextView tvStatus, tvFp, tvProgress, tvInfo, tvHeart, tvLast;
    private ProgressBar bar;
    private LinearLayout grid;
    private Button btnClear, btnSave;

    private final MultiFormatReader reader = new MultiFormatReader();
    private final EnumMap<DecodeHintType, Object> hintsFast = new EnumMap<>(DecodeHintType.class);
    private final EnumMap<DecodeHintType, Object> hintsHard = new EnumMap<>(DecodeHintType.class);
    private long lastScan = 0;

    // 后台单线程执行分析，避免阻塞相机/主线程导致部分机型（如 VIVO/originOS）停止送帧
    private ExecutorService analyzerExecutor;
    private byte[] nv21Buf = null;            // 复用的 YUV 缓冲，避免每帧分配 4.8MB
    private long frameCount = 0;
    private long lastFrameTs = 0;
    private long lastDecodeTs = 0;
    private long decodedCount = 0;
    private int lastFrameW = 0, lastFrameH = 0;

    private final Map<Integer, String> chunks = new HashMap<>();
    private String fp = null;
    private JSONObject fnChunk = null;
    private int total = 0;
    private String rxName = "";
    private long rxSize = 0;
    private byte[] lastFile = null;
    private String lastRaw = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.barcode_scanner);
        tvStatus = findViewById(R.id.tv_status);
        tvFp = findViewById(R.id.tv_fp);
        tvProgress = findViewById(R.id.tv_progress);
        tvInfo = findViewById(R.id.tv_info);
        tvHeart = findViewById(R.id.tv_heart);
        tvLast = findViewById(R.id.tv_last);
        bar = findViewById(R.id.bar);
        grid = findViewById(R.id.grid);
        btnClear = findViewById(R.id.btn_clear);
        btnSave = findViewById(R.id.btn_save);

        EnumMap<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        reader.setHints(hints);
        hintsFast.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hintsHard.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hintsHard.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hintsHard.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);

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
            // 后台线程执行扫码分析，主线程只负责预览与 UI
            analyzerExecutor = Executors.newSingleThreadExecutor();
            ProcessCameraProvider.getInstance(this).addListener(() -> {
                try {
                    ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                    Preview preview = new Preview.Builder().build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());

                    CameraSelector sel = new CameraSelector.Builder()
                            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                            .build();
                    // 逐级尝试：超高分辨率优先（高密度码需要更多像素/模块），失败自动降级
                    int[][] sizes = {{2560, 1920}, {1920, 1440}, {1280, 960}};
                    Exception lastErr = null;
                    for (int[] sz : sizes) {
                        try {
                            ImageAnalysis analysis = new ImageAnalysis.Builder()
                                    .setTargetResolution(new android.util.Size(sz[0], sz[1]))
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build();
                            analysis.setAnalyzer(analyzerExecutor, this::decodeFrame);
                            provider.unbindAll();
                            provider.bindToLifecycle(this, sel, preview, analysis);
                            runUi(() -> tvStatus.setText("对准二维码 (" + sz[0] + "x" + sz[1] + ")"));
                            lastErr = null;
                            break;
                        } catch (Exception e) {
                            lastErr = e;
                        }
                    }
                    if (lastErr != null) throw lastErr;
                } catch (Exception e) {
                    toast("相机启动失败: " + e.getMessage(), true);
                }
            }, ContextCompat.getMainExecutor(this));
        } catch (Exception e) {
            toast("相机初始化失败: " + e.getMessage(), true);
        }
    }

    // 关键修复：立刻关闭相机 ImageProxy（释放缓冲），再从本地副本解码，杜绝部分机型“送帧停摆”
    private void decodeFrame(ImageProxy image) {
        long now = System.currentTimeMillis();
        if (now - lastScan < 120) {
            image.close();
            return;
        }
        lastScan = now;
        frameCount++;
        lastFrameTs = now;

        byte[] data;
        int w, h;
        try {
            w = image.getWidth();
            h = image.getHeight();
            lastFrameW = w;
            lastFrameH = h;
            data = yuv420ToNV21(image);   // 写入复用缓冲，不每帧 new 大数组
        } catch (Exception e) {
            image.close();
            updateHeart();
            return;
        }
        image.close();   // 立即释放相机缓冲

        try {
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(
                    data, w, h, 0, 0, w, h, false);
            // 多路兜底：快速(Hybrid) -> TRY_HARDER(Hybrid) -> TRY_HARDER(GlobalHistogram, 抗反光/渐变)
            if (!tryDecode(src, false, false)) {
                if (!tryDecode(src, true, false)) {
                    tryDecode(src, true, true);
                }
            }
        } catch (Exception ignore) {
        }
        updateHeart();
    }

    private boolean tryDecode(PlanarYUVLuminanceSource src, boolean harder, boolean global) {
        try {
            Binarizer bin = global ? new GlobalHistogramBinarizer(src) : new HybridBinarizer(src);
            Result r = reader.decode(new BinaryBitmap(bin), harder ? hintsHard : hintsFast);
            if (r != null && r.getText() != null && !r.getText().isEmpty()) {
                onResult(r);
                return true;
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    private void onResult(Result r) {
        String t = r.getText();
        if (t == null || t.isEmpty()) return;
        lastDecodeTs = System.currentTimeMillis();
        decodedCount++;
        // 诊断：显示解出的分片 i / t 与长度，便于判断是“没解出”还是“解出被去重/拒绝”
        String diag = "解码#" + decodedCount + " len=" + t.length();
        try {
            int a = t.indexOf('{');
            int z = t.lastIndexOf('}');
            if (a >= 0 && z > a) {
                JSONObject o = new JSONObject(t.substring(a, z + 1));
                diag += " i=" + o.optInt("i", -1) + " t=" + o.optInt("t", -1);
            }
        } catch (Exception ignore) {
        }
        final String d = diag;
        runUi(() -> tvLast.setText(d));
        handle(t);
    }

    // YUV420 -> NV21（复用 nv21Buf），Y 平面 stride 规整为 w，兼容各类机型
    private byte[] yuv420ToNV21(ImageProxy image) {
        int w = image.getWidth(), h = image.getHeight();
        ByteBuffer yB = image.getPlanes()[0].getBuffer();
        ByteBuffer uB = image.getPlanes()[1].getBuffer();
        ByteBuffer vB = image.getPlanes()[2].getBuffer();
        int yRow = image.getPlanes()[0].getRowStride();
        int uvRow = image.getPlanes()[1].getRowStride();
        int uvPix = image.getPlanes()[1].getPixelStride();
        int vRow = image.getPlanes()[2].getRowStride();
        int vPix = image.getPlanes()[2].getPixelStride();
        int ySize = w * h;
        int need = ySize + w * h / 2;
        if (nv21Buf == null || nv21Buf.length < need) nv21Buf = new byte[need];
        byte[] out = nv21Buf;

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
                int vOff = y * vRow + x * vPix;
                int uOff = y * uvRow + x * uvPix;
                out[pos++] = vB.get(vOff);
                out[pos++] = uB.get(uOff);
            }
        }
        return out;
    }

    private void updateHeart() {
        long since = (System.currentTimeMillis() - lastDecodeTs) / 1000;
        final String txt = "帧 " + frameCount + " · " + lastFrameW + "x" + lastFrameH
                + " · 解码" + since + "s前 · " + BUILD;
        runUi(() -> tvHeart.setText(txt));
    }

    private void runUi(Runnable r) {
        runOnUiThread(r);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (analyzerExecutor != null) analyzerExecutor.shutdown();
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

            // 同一张二维码在画面里停留会反复解码：直接静默忽略，避免刷屏“分片X已有”
            if (s.equals(lastRaw)) return;

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
                lastRaw = s;
                saveState();
                toast("文件名分片已收");
                runUi(() -> tvStatus.setText("文件名: " + rxName + "  (共 " + total + " 片)"));
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
                    lastRaw = s;                 // 已收过，静默忽略
                    return;
                }
                if (total == 0) total = i;
                chunks.put(idx, d);
                lastRaw = s;
                saveState();
                int got = chunks.size();
                int fi = idx, ft = total, fg = got;
                runUi(() -> tvStatus.setText("已收 " + (fi + 1) + "/" + ft
                        + " 片   下一待收第 " + (fg + 1) + " 片"));
                toast("收到分片 " + (idx + 1) + "/" + total);
            }
            runUi(this::updateUI);
            checkDone();
        } catch (Exception e) {
            toast("处理失败", true);
        }
    }

    private void checkDone() {
        if (total > 0 && chunks.size() >= total) {
            if (fnChunk == null) {
                runUi(() -> tvStatus.setText("数据已齐，请扫最后一张「文件名」二维码"));
                return;
            }
            runUi(() -> tvStatus.setText("全部收齐，正在重组…"));
            new Thread(this::assemble).start();
        }
    }

    // ===== 重组：Base45 解码 -> zlib 解压 -> 存文件 =====
    private void assemble() {
        try {
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < total; i++) {
                String d = chunks.get(i);
                if (d == null) throw new Exception("缺少分片" + (i + 1));
                all.write(base45Decode(d));
            }
            byte[] out = inflate(all.toByteArray());
            lastFile = out;
            final String info = "文件名: " + rxName + "\n大小: " + fmt(out.length)
                    + "\n类型: " + ftype(rxName) + "\n分片数: " + total;
            runUi(() -> {
                tvStatus.setText("重组完成");
                tvInfo.setText(info);
                btnSave.setEnabled(true);
                btnSave.setVisibility(android.view.View.VISIBLE);
                toast("重组完成，已可保存");
            });
            saveFile(out, rxName);
        } catch (Exception e) {
            runUi(() -> toast("重组失败: " + e.getMessage(), true));
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
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
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
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
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
        lastRaw = "";
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
