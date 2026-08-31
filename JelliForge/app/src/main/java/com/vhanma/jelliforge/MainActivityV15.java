package com.vhanma.jelliforge;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rival 3 export upgrade layered on the v1.4 spring-mesh editor.
 * Keeps the proven v1.4 editor/physics and replaces SAVE ANIMATION with:
 * GIF -> High / Medium / Low
 * Video -> Short 10s / Medium 15s / Long 30s
 */
public class MainActivityV15 extends MainActivityV14 {
    private final ExecutorService exportWorker = Executors.newSingleThreadExecutor();

    private Method snapshotMethod;
    private Method renderSingleMethod;
    private Method advanceSnapshotMethod;
    private Method ensureVisibleMotionMethod;
    private Field editorField;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prepareReflectionBridge();
        replaceTitleAndAnimationButton(getWindow().getDecorView());
    }

    private void prepareReflectionBridge() {
        try {
            editorField = MainActivityV14.class.getDeclaredField("editor");
            editorField.setAccessible(true);

            Object editor = editorField.get(this);
            for (Method m : editor.getClass().getDeclaredMethods()) {
                if (m.getName().equals("snapshot") && m.getParameterCount() == 0) {
                    snapshotMethod = m;
                    snapshotMethod.setAccessible(true);
                    break;
                }
            }
            for (Method m : MainActivityV14.class.getDeclaredMethods()) {
                if (m.getName().equals("renderSingle") && m.getParameterCount() == 4) {
                    renderSingleMethod = m;
                    renderSingleMethod.setAccessible(true);
                } else if (m.getName().equals("advanceSnapshot") && m.getParameterCount() == 2) {
                    advanceSnapshotMethod = m;
                    advanceSnapshotMethod.setAccessible(true);
                } else if (m.getName().equals("ensureVisibleMotion") && m.getParameterCount() == 2) {
                    ensureVisibleMotionMethod = m;
                    ensureVisibleMotionMethod.setAccessible(true);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not connect v1.5 exporter to v1.4 mesh engine", e);
        }
    }

    private void replaceTitleAndAnimationButton(View view) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            CharSequence text = tv.getText();
            if (text != null && text.toString().startsWith("JelliForge v1.4")) {
                tv.setText("JelliForge v1.5 • Rival 3 Export");
            }
        }
        if (view instanceof Button) {
            Button b = (Button) view;
            if ("SAVE ANIMATION".contentEquals(b.getText())) {
                b.setText("SAVE ANIMATION • GIF / VIDEO");
                b.setOnClickListener(v -> showAnimationTypeDialog());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                replaceTitleAndAnimationButton(group.getChildAt(i));
            }
        }
    }

    private void showAnimationTypeDialog() {
        if (!hasPhotos()) {
            Toast.makeText(this, "Add photos first.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Save animation as")
                .setItems(new String[]{"GIF", "Video (MP4)"}, (dialog, which) -> {
                    if (which == 0) showGifQualityDialog();
                    else showVideoDurationDialog();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showGifQualityDialog() {
        new AlertDialog.Builder(this)
                .setTitle("GIF quality")
                .setItems(new String[]{
                        "High quality",
                        "Medium quality",
                        "Low quality"
                }, (dialog, which) -> {
                    if (which == 0) exportGifQuality(1080, 20, 4, "High");
                    else if (which == 1) exportGifQuality(720, 15, 4, "Medium");
                    else exportGifQuality(480, 10, 4, "Low");
                })
                .setNegativeButton("Back", null)
                .show();
    }

    private void showVideoDurationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Video length")
                .setItems(new String[]{
                        "Short • 10 seconds",
                        "Medium • 15 seconds",
                        "Long • 30 seconds"
                }, (dialog, which) -> {
                    if (which == 0) exportMp4(10, "Short");
                    else if (which == 1) exportMp4(15, "Medium");
                    else exportMp4(30, "Long");
                })
                .setNegativeButton("Back", null)
                .show();
    }

    private boolean hasPhotos() {
        try {
            Object snap = makeSnapshot();
            return !snapshotPhotos(snap).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void exportGifQuality(int maxSide, int fps, int seconds, String qualityName) {
        setStatus("Exporting " + qualityName + " GIF with the real jelly mesh…");
        exportWorker.execute(() -> {
            Uri uri = null;
            try {
                Object snap = makeSnapshot();
                int selected = intField(snap, "selected");
                ensureMotion(snap, selected);

                int frames = fps * seconds;
                int delayMs = Math.max(20, Math.round(1000f / fps));
                float dt = 1f / fps;
                String name = "JelliForge_GIF_" + qualityName + "_" + System.currentTimeMillis() + ".gif";

                Bitmap first = renderSingle(snap, selected, 0f, maxSide);
                int width = first.getWidth();
                int height = first.getHeight();
                uri = beginImageItem(name, "image/gif", width, height);

                try (OutputStream raw = getContentResolver().openOutputStream(uri, "w");
                     BufferedOutputStream out = new BufferedOutputStream(raw)) {
                    if (raw == null) throw new IOException("Gallery output stream unavailable");
                    GifEncoder332 encoder = new GifEncoder332(out, width, height, delayMs);
                    encoder.start();
                    encoder.addFrame(first);
                    first.recycle();

                    for (int f = 1; f < frames; f++) {
                        advanceSnapshot(snap, dt);
                        float phase = f * dt * 6f * floatField(snap, "speed");
                        Bitmap frame = renderSingle(snap, selected, phase, maxSide);
                        encoder.addFrame(frame);
                        frame.recycle();
                    }
                    encoder.finish();
                }
                finishMediaItem(uri, "image/gif");
                runOnUiThread(() -> {
                    setStatus(qualityName + " GIF saved to Gallery in DCIM/JelliForge.");
                    Toast.makeText(this, "GIF saved to Gallery", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                abortItem(uri);
                String msg = readableError(e);
                runOnUiThread(() -> setStatus("GIF export failed: " + msg));
            }
        });
    }

    private void exportMp4(int seconds, String lengthName) {
        setStatus("Rendering " + lengthName + " MP4 (" + seconds + " sec) with the real jelly mesh…");
        exportWorker.execute(() -> {
            Uri uri = null;
            ParcelFileDescriptor pfd = null;
            MediaCodec codec = null;
            MediaMuxer muxer = null;
            try {
                Object snap = makeSnapshot();
                int selected = intField(snap, "selected");
                ensureMotion(snap, selected);

                final int fps = 15;
                final int maxSide = 720;
                final int totalFrames = seconds * fps;
                final float dt = 1f / fps;

                Bitmap probe = renderSingle(snap, selected, 0f, maxSide);
                int width = even(Math.max(2, probe.getWidth()));
                int height = even(Math.max(2, probe.getHeight()));
                if (probe.getWidth() != width || probe.getHeight() != height) {
                    Bitmap scaled = Bitmap.createScaledBitmap(probe, width, height, true);
                    probe.recycle();
                    probe = scaled;
                }

                String name = "JelliForge_Video_" + lengthName + "_" + seconds + "s_" + System.currentTimeMillis() + ".mp4";
                uri = beginVideoItem(name, width, height, seconds * 1000L);
                pfd = getContentResolver().openFileDescriptor(uri, "rw");
                if (pfd == null) throw new IOException("Could not open MP4 destination");

                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                int colorFormat = chooseYuv420Color(codec);
                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                codec.start();

                muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int track = -1;
                boolean muxerStarted = false;

                for (int f = 0; f < totalFrames; f++) {
                    Bitmap frame;
                    if (f == 0) {
                        frame = probe;
                    } else {
                        advanceSnapshot(snap, dt);
                        float phase = f * dt * 6f * floatField(snap, "speed");
                        frame = renderSingle(snap, selected, phase, maxSide);
                        if (frame.getWidth() != width || frame.getHeight() != height) {
                            Bitmap scaled = Bitmap.createScaledBitmap(frame, width, height, true);
                            frame.recycle();
                            frame = scaled;
                        }
                    }

                    long ptsUs = (1_000_000L * f) / fps;
                    queueBitmap(codec, frame, width, height, colorFormat, ptsUs);
                    frame.recycle();

                    DrainState drained = drainCodec(codec, muxer, info, track, muxerStarted, false);
                    track = drained.track;
                    muxerStarted = drained.muxerStarted;
                }

                int eosInput = waitForInput(codec);
                codec.queueInputBuffer(eosInput, 0, 0, (1_000_000L * totalFrames) / fps,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                boolean eos = false;
                while (!eos) {
                    int outIndex = codec.dequeueOutputBuffer(info, 20_000);
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw new IOException("Encoder format changed twice");
                        track = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                    } else if (outIndex >= 0) {
                        ByteBuffer out = codec.getOutputBuffer(outIndex);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                        if (info.size > 0) {
                            if (!muxerStarted) throw new IOException("MP4 muxer never started");
                            if (out == null) throw new IOException("Encoder output unavailable");
                            out.position(info.offset);
                            out.limit(info.offset + info.size);
                            muxer.writeSampleData(track, out, info);
                        }
                        eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        codec.releaseOutputBuffer(outIndex, false);
                    }
                }

                codec.stop();
                codec.release();
                codec = null;
                if (muxerStarted) muxer.stop();
                muxer.release();
                muxer = null;
                pfd.close();
                pfd = null;

                finishMediaItem(uri, "video/mp4");
                runOnUiThread(() -> {
                    setStatus(lengthName + " video saved to Gallery: Movies/JelliForge (" + seconds + " sec).");
                    Toast.makeText(this, "MP4 saved to Gallery", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) { }
                try { if (muxer != null) muxer.release(); } catch (Exception ignored) { }
                try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
                abortItem(uri);
                String msg = readableError(e);
                runOnUiThread(() -> setStatus("Video export failed: " + msg));
            }
        });
    }

    private static final class DrainState {
        int track;
        boolean muxerStarted;
        DrainState(int track, boolean muxerStarted) {
            this.track = track;
            this.muxerStarted = muxerStarted;
        }
    }

    private DrainState drainCodec(MediaCodec codec, MediaMuxer muxer, MediaCodec.BufferInfo info,
                                  int track, boolean muxerStarted, boolean block) throws IOException {
        while (true) {
            int outIndex = codec.dequeueOutputBuffer(info, block ? 20_000 : 0);
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) throw new IOException("Encoder output format changed twice");
                track = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                continue;
            }
            if (outIndex >= 0) {
                ByteBuffer out = codec.getOutputBuffer(outIndex);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0) {
                    if (!muxerStarted) throw new IOException("MP4 muxer not started");
                    if (out == null) throw new IOException("Encoder output buffer unavailable");
                    out.position(info.offset);
                    out.limit(info.offset + info.size);
                    muxer.writeSampleData(track, out, info);
                }
                codec.releaseOutputBuffer(outIndex, false);
            }
        }
        return new DrainState(track, muxerStarted);
    }

    private int chooseYuv420Color(MediaCodec codec) {
        MediaCodecInfo.CodecCapabilities caps = codec.getCodecInfo()
                .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
        int planar = -1;
        int semi = -1;
        for (int value : caps.colorFormats) {
            if (value == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) return value;
            if (value == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) planar = value;
            if (value == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) semi = value;
        }
        if (semi != -1) return semi;
        if (planar != -1) return planar;
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
    }

    private int waitForInput(MediaCodec codec) throws IOException {
        for (int tries = 0; tries < 200; tries++) {
            int index = codec.dequeueInputBuffer(20_000);
            if (index >= 0) return index;
        }
        throw new IOException("Video encoder stopped accepting frames");
    }

    private void queueBitmap(MediaCodec codec, Bitmap bitmap, int width, int height,
                             int colorFormat, long ptsUs) throws IOException {
        int index = waitForInput(codec);
        Image image = null;
        try {
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                image = codec.getInputImage(index);
            }
            if (image != null && image.getFormat() == ImageFormat.YUV_420_888) {
                fillFlexibleYuv(image, bitmap, width, height);
                codec.queueInputBuffer(index, 0, 0, ptsUs, 0);
            } else {
                ByteBuffer buffer = codec.getInputBuffer(index);
                if (buffer == null) throw new IOException("Video input buffer unavailable");
                buffer.clear();
                int size = fillPackedYuv(buffer, bitmap, width, height, colorFormat);
                codec.queueInputBuffer(index, 0, size, ptsUs, 0);
            }
        } finally {
            if (image != null) image.close();
        }
    }

    private void fillFlexibleYuv(Image image, Bitmap bitmap, int width, int height) {
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRow = planes[0].getRowStride(), yPix = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride(), uPix = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride(), vPix = planes[2].getPixelStride();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int c = pixels[y * width + x];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                int yy = clampByte(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
                int yi = y * yRow + x * yPix;
                if (yi < yBuf.capacity()) yBuf.put(yi, (byte) yy);
                if ((x & 1) == 0 && (y & 1) == 0) {
                    int uu = clampByte(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                    int vv = clampByte(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
                    int ui = (y / 2) * uRow + (x / 2) * uPix;
                    int vi = (y / 2) * vRow + (x / 2) * vPix;
                    if (ui < uBuf.capacity()) uBuf.put(ui, (byte) uu);
                    if (vi < vBuf.capacity()) vBuf.put(vi, (byte) vv);
                }
            }
        }
    }

    private int fillPackedYuv(ByteBuffer out, Bitmap bitmap, int width, int height, int colorFormat) {
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int frameSize = width * height;
        byte[] yPlane = new byte[frameSize];
        byte[] uPlane = new byte[frameSize / 4];
        byte[] vPlane = new byte[frameSize / 4];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int c = pixels[y * width + x];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                yPlane[y * width + x] = (byte) clampByte(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
                if ((x & 1) == 0 && (y & 1) == 0) {
                    int ci = (y / 2) * (width / 2) + (x / 2);
                    uPlane[ci] = (byte) clampByte(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                    vPlane[ci] = (byte) clampByte(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
                }
            }
        }

        out.put(yPlane);
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
            out.put(uPlane);
            out.put(vPlane);
        } else {
            for (int i = 0; i < uPlane.length; i++) {
                out.put(uPlane[i]);
                out.put(vPlane[i]);
            }
        }
        return frameSize + frameSize / 2;
    }

    private static int clampByte(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int even(int value) {
        return (value & 1) == 0 ? value : value - 1;
    }

    private Uri beginImageItem(String name, String mime, int width, int height) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/JelliForge/");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        if (width > 0) values.put(MediaStore.Images.Media.WIDTH, width);
        if (height > 0) values.put(MediaStore.Images.Media.HEIGHT, height);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) throw new IOException("Gallery could not create GIF");
        return uri;
    }

    private Uri beginVideoItem(String name, int width, int height, long durationMs) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/JelliForge/");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());
        values.put(MediaStore.Video.Media.WIDTH, width);
        values.put(MediaStore.Video.Media.HEIGHT, height);
        values.put(MediaStore.Video.Media.DURATION, durationMs);
        Uri uri = getContentResolver().insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) throw new IOException("Gallery could not create MP4");
        return uri;
    }

    private void finishMediaItem(Uri uri, String mime) throws Exception {
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        if (getContentResolver().update(uri, done, null, null) <= 0) {
            throw new IOException("Gallery item could not be finalized");
        }
        getContentResolver().notifyChange(uri, null);
        Field uriField = MainActivityV14.class.getDeclaredField("lastGalleryUri");
        Field mimeField = MainActivityV14.class.getDeclaredField("lastGalleryMime");
        uriField.setAccessible(true);
        mimeField.setAccessible(true);
        uriField.set(this, uri);
        mimeField.set(this, mime);
    }

    private void abortItem(Uri uri) {
        if (uri != null) {
            try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
        }
    }

    private Object makeSnapshot() throws Exception {
        Object editor = editorField.get(this);
        return snapshotMethod.invoke(editor);
    }

    @SuppressWarnings("unchecked")
    private ArrayList<Object> snapshotPhotos(Object snap) throws Exception {
        Field f = snap.getClass().getDeclaredField("photos");
        f.setAccessible(true);
        return (ArrayList<Object>) (ArrayList<?>) f.get(snap);
    }

    private int intField(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(obj);
    }

    private float floatField(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getFloat(obj);
    }

    private void ensureMotion(Object snap, int selected) throws Exception {
        ArrayList<Object> photos = snapshotPhotos(snap);
        if (photos.isEmpty()) throw new IOException("No photos to export");
        selected = Math.max(0, Math.min(selected, photos.size() - 1));
        float bounce = floatField(snap, "bounce");
        ensureVisibleMotionMethod.invoke(null, photos.get(selected), bounce);
    }

    private void advanceSnapshot(Object snap, float dt) throws Exception {
        advanceSnapshotMethod.invoke(null, snap, dt);
    }

    private Bitmap renderSingle(Object snap, int selected, float phase, int maxSide) throws Exception {
        return (Bitmap) renderSingleMethod.invoke(this, snap, selected, phase, maxSide);
    }

    private void setStatus(String message) {
        try {
            Field status = MainActivityV14.class.getDeclaredField("statusView");
            status.setAccessible(true);
            TextView tv = (TextView) status.get(this);
            tv.setText(message);
        } catch (Exception ignored) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    private String readableError(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String msg = t.getMessage();
        return msg == null || msg.trim().isEmpty() ? t.getClass().getSimpleName() : msg;
    }

    @Override
    protected void onDestroy() {
        exportWorker.shutdownNow();
        super.onDestroy();
    }
}
