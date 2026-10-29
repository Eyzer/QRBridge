package com.example.qrbridge;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
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
    private static final String BUILD = "v1.6";

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
    private byte[] lumaBuf = null;            // 复用的 Y 平面缓冲（只用亮度，不拷色度）
    private byte[] smallBuf = null;           // 降采样后的缓冲
    private int smallW = 0, smallH = 0;
    private long frameCount = 0;
    private long lastFrameTs = 0;
    private long lastDecodeTs = 0;
    private long decodedCount = 0;
    private long lastHeavyTs = 0;             // 上次做“重解”（TRY_HARDER 等）的时间
    private long lastFrameMs = 0;             // 单帧分析耗时，用于诊断
    private long lastHeartUi = 0;
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
                    // 分析分辨率必须压在 100 万像素级：2448x2448 那种全尺寸每帧解码要几秒，
                    // 会把分析线程拖到 0.2 帧/秒，连最简单的一张码都扫不出来。
                    // 高密度码的余量交给“降采样到 1280 + 多路重解”来补，不靠堆分辨率。
                    int[][] sizes = {{1280, 960}, {1024, 768}, {960, 720}, {640, 480}};
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
        long t0 = SystemClock.elapsedRealtime();
        if (t0 - lastScan < 70) {          // 限流，把 CPU 留给真正的解码
            image.close();
            return;
        }
        lastScan = t0;

        byte[] luma;
        int w, h;
        try {
            w = image.getWidth();
            h = image.getHeight();
            lastFrameW = w;
            lastFrameH = h;
            luma = readLuma(image);        // 只拷 Y 平面（解码只用亮度）
        } catch (Exception e) {
            image.close();
            return;
        }
        image.close();                     // 立即释放相机缓冲，杜绝送帧停摆

        frameCount++;
        long now = System.currentTimeMillis();

        try {
            // 大画面先盒式降采样：帧率（能否扫到）比像素数更关键
            byte[] data = downscale(luma, w, h);
            int dw = smallW, dh = smallH;
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, dw, dh, 0, 0, dw, dh, false);

            boolean ok = tryDecode(src, false, false);      // 每帧都做：便宜的快解
            if (!ok && (now - lastHeavyTs) >= 400) {        // 每 400ms 才做一次重解，保住帧率
                lastHeavyTs = now;
                ok = tryDecode(src, true, false)            // TRY_HARDER + 反色
                        || tryDecodeCrop(data, dw, dh)      // 中间 80% 裁切（码占满画面时更准）
                        || tryDecode(src, true, true);      // GlobalHistogram，抗屏幕反光/渐变
            }
        } catch (Exception ignore) {
        }
        lastFrameMs = SystemClock.elapsedRealtime() - t0;
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

    // 裁切中间 80% 再解：背景干净、模块更集中，对“码贴在屏幕上”的场景命中率更高
    private boolean tryDecodeCrop(byte[] data, int w, int h) {
        try {
            int cw = Math.max(64, w * 80 / 100);
            int ch = Math.max(64, h * 80 / 100);
            int cl = (w - cw) / 2, ct = (h - ch) / 2;
            PlanarYUVLuminanceSource crop = new PlanarYUVLuminanceSource(data, w, h, cl, ct, cw, ch, false);
            return tryDecode(crop, false, false) || tryDecode(crop, true, false);
        } catch (Exception e) {
            return false;
        }
    }

    // 只取 Y（亮度）平面：解码用不到色度，省掉一半拷贝和大量 ByteBuffer.get
    private byte[] readLuma(ImageProxy image) {
        ImageProxy.PlaneProxy yp = image.getPlanes()[0];
        ByteBuffer b = yp.getBuffer();
        int w = image.getWidth(), h = image.getHeight();
        int rowStride = yp.getRowStride();
        int pixStride = yp.getPixelStride();
        int need = w * h;
        if (lumaBuf == null || lumaBuf.length < need) lumaBuf = new byte[need];
        byte[] out = lumaBuf;
        int limit = b.limit();
        if (pixStride == 1 && rowStride == w && limit >= need) {
            b.position(0);
            b.get(out, 0, need);
        } else if (pixStride == 1) {
            int pos = 0;
            for (int y = 0; y < h; y++) {
                int p = y * rowStride;
                if (p + w > limit) break;
                b.position(p);
                b.get(out, pos, w);
                pos += w;
            }
        } else {
            int pos = 0;
            for (int y = 0; y < h; y++) {
                int base = y * rowStride;
                for (int x = 0; x < w; x++) {
                    int p = base + x * pixStride;
                    out[pos++] = p < limit ? b.get(p) : (byte) 0xff;
                }
            }
        }
        return out;
    }

    // 盒式平均降采样：最长边压到 1280 以内，兼顾“每模块像素够”与“解码够快”
    private byte[] downscale(byte[] src, int w, int h) {
        int maxSide = Math.max(w, h);
        int f = 1;
        while (maxSide / f > 1280) f++;
        if (f <= 1) {
            smallW = w;
            smallH = h;
            return src;
        }
        int dw = w / f, dh = h / f;
        int need = dw * dh;
        if (smallBuf == null || smallBuf.length < need) smallBuf = new byte[need];
        byte[] out = smallBuf;
        int area = f * f;
        for (int y = 0; y < dh; y++) {
            int sy0 = y * f;
            int rowOut = y * dw;
            for (int x = 0; x < dw; x++) {
                int sx0 = x * f;
                int sum = 0;
                for (int dy = 0; dy < f; dy++) {
                    int base = (sy0 + dy) * w + sx0;
                    for (int dx = 0; dx < f; dx++) sum += src[base + dx] & 0xff;
                }
                out[rowOut + x] = (byte) (sum / area);
            }
        }
        smallW = dw;
        smallH = dh;
        return out;
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

    private void updateHeart() {
        long now = System.currentTimeMillis();
        if (now - lastHeartUi < 250) return;      // 别让 UI 刷新反过来拖慢分析线程
        lastHeartUi = now;
        long since = lastDecodeTs == 0 ? -1 : (now - lastDecodeTs) / 1000;
        final String txt = "帧 " + frameCount + " · " + lastFrameW + "x" + lastFrameH
                + " · 解码" + (since < 0 ? "—" : since + "s") + "前 · " + lastFrameMs + "ms/帧 · " + BUILD;
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
    // 两套发送端的 Base45 字节序不同（原版 send.html 低位在前；EyHub/RFC 9285 高位在前），
    // 所以这里两种顺序都试，再看哪种能 inflate 成功——不猜。
    private void assemble() {
        String lastMsg = "";
        byte[] head = null;
        for (int order = 0; order < 2; order++) {
            boolean rfc = (order == 1);
            try {
                java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
                for (int i = 0; i < total; i++) {
                    String d = chunks.get(i);
                    if (d == null) throw new Exception("缺少分片" + (i + 1));
                    all.write(base45Decode(d, rfc));
                }
                byte[] comp = all.toByteArray();
                if (comp.length < 4) throw new Exception("数据过短");
                if (head == null) head = new byte[]{comp[0], comp[1], comp[2], comp[3]};

                byte[] out = null;
                Exception e1 = null;
                try {
                    out = inflate(comp, false);      // zlib 头（pako.deflate 默认）
                } catch (Exception ex) {
                    e1 = ex;
                    out = inflate(comp, true);       // 裸 deflate 兜底
                }
                if (out == null || out.length == 0) throw new Exception(e1 != null ? e1.getMessage() : "解压为空");
                // 长度自校验：错序解码有时能“解出”垃圾而不抛异常，用名片里的原始大小兜住
                if (rxSize > 0 && out.length != rxSize) {
                    throw new Exception("长度不符 " + out.length + "/" + rxSize);
                }

                lastFile = out;
                final String info = "文件名: " + rxName + "\n大小: " + fmt(out.length)
                        + "\n类型: " + ftype(rxName) + "\n分片数: " + total
                        + "\nBase45 字节序: " + (rfc ? "高位在前(RFC)" : "低位在前(原版)");
                final byte[] saved = out;
                runUi(() -> {
                    tvStatus.setText("重组完成");
                    tvInfo.setText(info);
                    btnSave.setEnabled(true);
                    btnSave.setVisibility(android.view.View.VISIBLE);
                });
                saveFile(saved, rxName);
                toast("重组完成，已保存");
                return;
            } catch (Exception e) {
                lastMsg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
        final String msg = lastMsg + (head == null ? "" : " 头字节=" + hex(head));
        runUi(() -> toast("重组失败: " + msg, true));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
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

    // rfc=false：低位在前（原版 send.html / v3 单文件版）
    // rfc=true ：高位在前（RFC 9285 / EyHub qrBridge.js）
    private static byte[] base45Decode(String s, boolean rfc) {
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
                if (rfc) {
                    out.write((val >> 8) & 0xFF);
                    out.write(val & 0xFF);
                } else {
                    out.write(val & 0xFF);
                    out.write((val >> 8) & 0xFF);
                }
                i += 3;
            } else if (i + 1 < s.length()) {
                int c1 = alph[s.charAt(i + 1)];
                int val = c0 + c1 * 45;
                out.write(val & 0xFF);      // 单字节组两种序一致
                i += 2;
            } else {
                throw new IllegalArgumentException("Base45 长度错误");
            }
        }
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] comp, boolean raw) throws Exception {
        Inflater inf = new Inflater(raw);   // raw=true 时按裸 deflate 处理
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
