package org.vhanma.dnaforgemax;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * OsciVision Ultra v5 synthesis core.
 *
 * v5 drops the point-cloud-first representation. It vectorizes the source into ordered
 * luminance contours + structural edge traces, repeats a stable skeleton on a frequency
 * lattice, and rotates tonal/detail material through that skeleton. The selected frequency
 * banks are phase-closing lattices rather than decorative tones mixed on top of X/Y.
 */
final class V5Engine {
    private V5Engine() {}

    static final class Settings {
        int sampleRate = 192000;
        int fps = 8;
        int quality = 98;
        int profile = 0;          // 0=max photo, 1=portrait, 2=video, 3=line art
        int toneBands = 8;
        int bank = 0;             // 0=81 lattice, 1=Bagua64, 2=Sevenfold49, 3=raw
        boolean temporal = true;
        boolean invert = false;
        float gamma = 0.90f;
        float contourStrength = 0.72f;
        float fillStrength = 0.46f;
        float resonance = 0.28f;
        float smoothness = 0.22f;
        float xGain = 1f;
        float yGain = 1f;
        float rotationDeg = 0f;

        Settings copy() {
            Settings s = new Settings();
            s.sampleRate = sampleRate;
            s.fps = fps;
            s.quality = quality;
            s.profile = profile;
            s.toneBands = toneBands;
            s.bank = bank;
            s.temporal = temporal;
            s.invert = invert;
            s.gamma = gamma;
            s.contourStrength = contourStrength;
            s.fillStrength = fillStrength;
            s.resonance = resonance;
            s.smoothness = smoothness;
            s.xGain = xGain;
            s.yGain = yGain;
            s.rotationDeg = rotationDeg;
            return s;
        }
    }

    static final class Result {
        final float[] xy;
        final int grid;
        final int contourPaths;
        final int skeletonPaths;
        final int fillCenters;
        final int flybacks;
        final float continuity;
        final double latticeHz;
        final double primaryHz;
        final int samplesPerLoop;

        Result(float[] xy, int grid, int contourPaths, int skeletonPaths, int fillCenters,
               int flybacks, float continuity, double latticeHz, double primaryHz,
               int samplesPerLoop) {
            this.xy = xy;
            this.grid = grid;
            this.contourPaths = contourPaths;
            this.skeletonPaths = skeletonPaths;
            this.fillCenters = fillCenters;
            this.flybacks = flybacks;
            this.continuity = continuity;
            this.latticeHz = latticeHz;
            this.primaryHz = primaryHz;
            this.samplesPerLoop = samplesPerLoop;
        }
    }

    private static final class Pt {
        float x, y;
        Pt(float x, float y) { this.x = x; this.y = y; }
    }

    private static final class TracePath {
        final ArrayList<Pt> pts = new ArrayList<>();
        boolean closed;
        float importance;
        int kind; // 0 tone, 1 structural edge
        float length;

        void finish() {
            length = 0f;
            for (int i = 1; i < pts.size(); i++) {
                float dx = pts.get(i).x - pts.get(i - 1).x;
                float dy = pts.get(i).y - pts.get(i - 1).y;
                length += (float) Math.sqrt(dx * dx + dy * dy);
            }
            if (closed && pts.size() > 2) {
                float dx = pts.get(0).x - pts.get(pts.size() - 1).x;
                float dy = pts.get(0).y - pts.get(pts.size() - 1).y;
                length += (float) Math.sqrt(dx * dx + dy * dy);
            }
        }
    }

    private static final class Field {
        final int grid;
        final float[] lum;
        final float[] grad;
        final float[] gx;
        final float[] gy;
        final float[] contrast;
        final float[] fillWeight;

        Field(int grid, float[] lum, float[] grad, float[] gx, float[] gy,
              float[] contrast, float[] fillWeight) {
            this.grid = grid;
            this.lum = lum;
            this.grad = grad;
            this.gx = gx;
            this.gy = gy;
            this.contrast = contrast;
            this.fillWeight = fillWeight;
        }
    }

    private static final double GOLD = 0.6180339887498948482;
    private static final double GOLD_ANGLE = 2.39996322972865332;
    private static final double[][] BANKS = {
            {81, 121.5, 162, 243, 324, 486, 729},
            {64, 80, 96, 128, 160, 192, 256, 320, 384, 512},
            {49, 73.5, 98, 122.5, 147, 196, 245, 343, 490, 686}
    };
    // Greatest common frequency step that phase-closes every member of each bank.
    private static final double[] LATTICE = {13.5, 16.0, 24.5};
    private static final double[] PRIMARY = {81.0, 64.0, 49.0};
    private static final int[] PRIMARY_REPEATS = {6, 4, 2};

    static Result compile(Bitmap input, Settings s, long frameIndex) {
        if (input == null) {
            return new Result(new float[]{0f, 0f, 0f, 0f}, 1, 0, 0, 0, 0, 1f, 0, 0, 2);
        }

        int grid = chooseGrid(s);
        Field field = buildField(input, s, grid);
        List<TracePath> paths = buildVectorPaths(field, s);
        if (paths.isEmpty()) {
            float[] fallback = circle(s, Math.max(1024, s.sampleRate / Math.max(1, s.fps)));
            return new Result(fallback, grid, 0, 0, 0, 0, 1f, 0, 0, fallback.length / 2);
        }

        Collections.sort(paths, (a, b) -> Float.compare(scorePath(b), scorePath(a)));
        int keep = clamp(120 + s.quality * 5, 120, s.profile == 2 ? 360 : 620);
        if (paths.size() > keep) paths = new ArrayList<>(paths.subList(0, keep));

        ArrayList<TracePath> skeleton = new ArrayList<>();
        ArrayList<TracePath> detail = new ArrayList<>();
        int skeletonTarget = s.profile == 3 ? 140 : (s.profile == 2 ? 70 : 110);
        for (TracePath p : paths) {
            if ((p.kind == 1 || p.importance >= 0.68f) && skeleton.size() < skeletonTarget) skeleton.add(p);
            else detail.add(p);
        }
        if (skeleton.isEmpty()) skeleton.add(paths.get(0));

        orderPathsInPlace(skeleton);
        orderPathsInPlace(detail);

        float[] xy;
        int fillCenters = 0;
        double latticeHz = 0.0;
        double primaryHz = 0.0;

        if (s.bank >= 0 && s.bank < 3) {
            latticeHz = LATTICE[s.bank];
            primaryHz = PRIMARY[s.bank];
            int repeats = PRIMARY_REPEATS[s.bank];
            int loopN = clamp((int) Math.round(s.sampleRate / latticeHz), 2048, 32000);
            int segmentN = Math.max(512, loopN / repeats);
            loopN = segmentN * repeats;

            float skeletonShare;
            if (s.profile == 3) skeletonShare = 0.78f;
            else if (s.profile == 2) skeletonShare = 0.50f;
            else skeletonShare = 0.57f + 0.12f * clamp01(s.contourStrength);
            int skeletonN = clamp(Math.round(segmentN * skeletonShare), 256, segmentN - 128);
            int detailN = segmentN - skeletonN;

            float[] stableSkeleton = renderPaths(skeleton, skeletonN, field.grid, 0, true, s.smoothness);
            xy = new float[loopN * 2];
            int out = 0;
            for (int cell = 0; cell < repeats; cell++) {
                System.arraycopy(stableSkeleton, 0, xy, out, stableSkeleton.length);
                out += stableSkeleton.length;

                int pathPart = s.profile == 3 ? detailN : Math.round(detailN * (0.54f + 0.30f * s.contourStrength));
                pathPart = clamp(pathPart, 0, detailN);
                int fillPart = detailN - pathPart;
                float[] d = renderPaths(detail, pathPart, field.grid,
                        (int) ((frameIndex * 17 + cell * 37) & 0x7fffffff), false, s.smoothness);
                System.arraycopy(d, 0, xy, out, d.length);
                out += d.length;

                if (fillPart > 0) {
                    StippleResult st = renderStipple(field, fillPart,
                            frameIndex * 131L + cell * 977L, s, cell);
                    System.arraycopy(st.xy, 0, xy, out, st.xy.length);
                    out += st.xy.length;
                    fillCenters += st.centers;
                }
            }
            if (out < xy.length) {
                float lx = out >= 2 ? xy[out - 2] : 0f;
                float ly = out >= 2 ? xy[out - 1] : 0f;
                while (out < xy.length) { xy[out++] = lx; xy[out++] = ly; }
            }
            applyResonantMicroField(xy, s, latticeHz, frameIndex);
        } else {
            int n = clamp(Math.round(s.sampleRate / (float) Math.max(1, s.fps)), 1200, 32000);
            int pathN = s.profile == 3 ? n : Math.round(n * (0.62f + 0.28f * s.contourStrength));
            pathN = clamp(pathN, 0, n);
            float[] p = renderPaths(paths, pathN, field.grid, 0, false, s.smoothness);
            StippleResult st = renderStipple(field, n - pathN, frameIndex * 811L, s, 0);
            fillCenters += st.centers;
            xy = new float[n * 2];
            System.arraycopy(p, 0, xy, 0, p.length);
            System.arraycopy(st.xy, 0, xy, p.length, st.xy.length);
        }

        transform(xy, s);
        if (s.smoothness > 0.01f) localSpectralSmooth(xy, s.smoothness);

        int flybacks = countFlybacks(xy);
        int n = xy.length / 2;
        float continuity = clamp01(1f - flybacks / (float) Math.max(1, n / 160));
        return new Result(xy, grid, paths.size(), skeleton.size(), fillCenters, flybacks,
                continuity, latticeHz, primaryHz, n);
    }

    private static int chooseGrid(Settings s) {
        int base = s.profile == 2 ? 190 : 230;
        int add = Math.round(s.quality * (s.profile == 2 ? 1.25f : 1.75f));
        return clamp(base + add, 192, s.profile == 2 ? 300 : 404);
    }

    private static Field buildField(Bitmap input, Settings s, int grid) {
        Bitmap square = Bitmap.createBitmap(grid, grid, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(square);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float scale = Math.min(grid / (float) Math.max(1, input.getWidth()),
                grid / (float) Math.max(1, input.getHeight()));
        float dw = input.getWidth() * scale;
        float dh = input.getHeight() * scale;
        float left = (grid - dw) * 0.5f;
        float top = (grid - dh) * 0.5f;
        canvas.drawBitmap(input, null, new RectF(left, top, left + dw, top + dh), paint);

        int[] pixels = new int[grid * grid];
        square.getPixels(pixels, 0, grid, 0, 0, grid, grid);
        float[] lum = new float[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            float v = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
            if (s.invert) v = 1f - v;
            lum[i] = (float) Math.pow(clamp01(v), clamp(s.gamma, 0.35f, 2.2f));
        }

        float[] blur = boxBlur(lum, grid, 3);
        float[] gx = new float[lum.length];
        float[] gy = new float[lum.length];
        float[] grad = new float[lum.length];
        float maxGrad = 1e-6f;
        for (int y = 1; y < grid - 1; y++) {
            for (int x = 1; x < grid - 1; x++) {
                int i = y * grid + x;
                float dx = -lum[i-grid-1] + lum[i-grid+1]
                        - 2f * lum[i-1] + 2f * lum[i+1]
                        - lum[i+grid-1] + lum[i+grid+1];
                float dy = -lum[i-grid-1] - 2f * lum[i-grid] - lum[i-grid+1]
                        + lum[i+grid-1] + 2f * lum[i+grid] + lum[i+grid+1];
                float m = (float) Math.sqrt(dx * dx + dy * dy);
                gx[i] = dx; gy[i] = dy; grad[i] = m;
                if (m > maxGrad) maxGrad = m;
            }
        }
        float inv = 1f / maxGrad;
        float[] contrast = new float[lum.length];
        float[] fill = new float[lum.length];
        for (int i = 0; i < lum.length; i++) {
            gx[i] *= inv; gy[i] *= inv; grad[i] = clamp01(grad[i] * inv);
            contrast[i] = clamp01(Math.abs(lum[i] - blur[i]) * 3.6f);
            float tonal = (float) Math.pow(lum[i], 0.72);
            float structure = 1f + contrast[i] * (s.profile == 1 ? 1.20f : 0.72f) + grad[i] * 0.24f;
            fill[i] = Math.max(0f, tonal * structure);
        }
        return new Field(grid, lum, grad, gx, gy, contrast, fill);
    }

    private static List<TracePath> buildVectorPaths(Field f, Settings s) {
        ArrayList<TracePath> out = new ArrayList<>();
        int bands = clamp(s.toneBands, 3, 12);
        float[] thresholds = adaptiveThresholds(f.lum, bands);
        for (int b = 0; b < thresholds.length; b++) {
            boolean[] boundary = thresholdBoundary(f.lum, f.grid, thresholds[b]);
            float importance = 0.46f + 0.38f * (b / (float) Math.max(1, thresholds.length - 1));
            traceMask(boundary, f.grid, 0, importance, out,
                    s.profile == 2 ? 26 : 14,
                    s.profile == 2 ? 160 : 260);
        }

        float edgeThreshold = percentile(f.grad, s.profile == 3 ? 0.64f : (s.profile == 1 ? 0.70f : 0.76f));
        edgeThreshold = Math.max(0.10f, edgeThreshold);
        boolean[] edges = new boolean[f.grad.length];
        for (int y = 1; y < f.grid - 1; y++) {
            for (int x = 1; x < f.grid - 1; x++) {
                int i = y * f.grid + x;
                float g = f.grad[i];
                if (g < edgeThreshold) continue;
                float ax = Math.abs(f.gx[i]), ay = Math.abs(f.gy[i]);
                boolean localMax;
                if (ax >= ay) localMax = g >= f.grad[i - 1] && g >= f.grad[i + 1];
                else localMax = g >= f.grad[i - f.grid] && g >= f.grad[i + f.grid];
                edges[i] = localMax;
            }
        }
        traceMask(edges, f.grid, 1, 0.96f, out,
                s.profile == 2 ? 16 : 8,
                s.profile == 2 ? 180 : 360);

        // Remove tiny fragments and simplify gentle runs without destroying corners.
        ArrayList<TracePath> clean = new ArrayList<>();
        float tolerance = s.profile == 2 ? 0.85f : (1.05f - 0.55f * s.quality / 100f);
        for (TracePath p : out) {
            if (p.pts.size() < 5) continue;
            simplifyInPlace(p, Math.max(0.22f, tolerance));
            p.finish();
            if (p.length >= (s.profile == 2 ? 4f : 2.5f) && p.pts.size() >= 4) clean.add(p);
        }
        return clean;
    }

    private static float[] adaptiveThresholds(float[] lum, int bands) {
        float[] sorted = lum.clone();
        java.util.Arrays.sort(sorted);
        float[] t = new float[bands];
        for (int i = 0; i < bands; i++) {
            float q = 0.12f + 0.76f * (i + 1f) / (bands + 1f);
            int idx = clamp(Math.round(q * (sorted.length - 1)), 0, sorted.length - 1);
            t[i] = sorted[idx];
            if (i > 0 && t[i] < t[i - 1] + 0.018f) t[i] = Math.min(0.98f, t[i - 1] + 0.018f);
        }
        return t;
    }

    private static boolean[] thresholdBoundary(float[] lum, int grid, float threshold) {
        boolean[] b = new boolean[lum.length];
        for (int y = 1; y < grid - 1; y++) {
            for (int x = 1; x < grid - 1; x++) {
                int i = y * grid + x;
                if (lum[i] < threshold) continue;
                if (lum[i - 1] < threshold || lum[i + 1] < threshold
                        || lum[i - grid] < threshold || lum[i + grid] < threshold) b[i] = true;
            }
        }
        return b;
    }

    private static final int[] NDX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] NDY = {0, 1, 1, 1, 0, -1, -1, -1};

    private static void traceMask(boolean[] mask, int grid, int kind, float importance,
                                  List<TracePath> out, int minLen, int maxPaths) {
        boolean[] used = new boolean[mask.length];
        int made = 0;
        for (int start = 0; start < mask.length && made < maxPaths; start++) {
            if (!mask[start] || used[start]) continue;
            int sx = start % grid, sy = start / grid;
            int cx = sx, cy = sy, prevDir = -1;
            TracePath p = new TracePath();
            p.kind = kind;
            p.importance = importance;
            int guard = 0;
            while (guard++ < 12000) {
                int ci = cy * grid + cx;
                if (used[ci]) break;
                used[ci] = true;
                p.pts.add(new Pt(cx, cy));

                int bestDir = -1;
                float bestScore = Float.MAX_VALUE;
                for (int d = 0; d < 8; d++) {
                    int nx = cx + NDX[d], ny = cy + NDY[d];
                    if (nx <= 0 || ny <= 0 || nx >= grid - 1 || ny >= grid - 1) continue;
                    int ni = ny * grid + nx;
                    if (!mask[ni] || used[ni]) continue;
                    float turn = prevDir < 0 ? 0f : circularDirDistance(prevDir, d);
                    float diagPenalty = (d & 1) == 1 ? 0.04f : 0f;
                    float score = turn + diagPenalty;
                    if (score < bestScore) { bestScore = score; bestDir = d; }
                }
                if (bestDir < 0) break;
                cx += NDX[bestDir];
                cy += NDY[bestDir];
                prevDir = bestDir;
                if (p.pts.size() > 5 && Math.abs(cx - sx) <= 1 && Math.abs(cy - sy) <= 1) {
                    p.closed = true;
                    break;
                }
            }
            if (p.pts.size() >= minLen) {
                p.finish();
                out.add(p);
                made++;
            }
        }
    }

    private static float circularDirDistance(int a, int b) {
        int d = Math.abs(a - b);
        d = Math.min(d, 8 - d);
        return d * d * 0.18f;
    }

    private static void simplifyInPlace(TracePath p, float tol) {
        if (p.pts.size() < 5) return;
        ArrayList<Pt> keep = new ArrayList<>();
        keep.add(p.pts.get(0));
        Pt last = p.pts.get(0);
        float tol2 = tol * tol;
        for (int i = 1; i < p.pts.size() - 1; i++) {
            Pt q = p.pts.get(i);
            float dx = q.x - last.x, dy = q.y - last.y;
            Pt before = p.pts.get(i - 1), after = p.pts.get(i + 1);
            float ax = q.x - before.x, ay = q.y - before.y;
            float bx = after.x - q.x, by = after.y - q.y;
            float cross = Math.abs(ax * by - ay * bx);
            if (dx * dx + dy * dy >= tol2 || cross > 0.42f) {
                keep.add(q);
                last = q;
            }
        }
        keep.add(p.pts.get(p.pts.size() - 1));
        p.pts.clear();
        p.pts.addAll(keep);
    }

    private static float scorePath(TracePath p) {
        float kindBoost = p.kind == 1 ? 1.45f : 1f;
        return kindBoost * p.importance * (float) Math.sqrt(Math.max(1f, p.length));
    }

    private static void orderPathsInPlace(List<TracePath> paths) {
        if (paths.size() < 2) return;
        paths.sort(Comparator.comparingDouble(V5Engine::pathHilbertKey));
        int window = 24;
        for (int i = 0; i < paths.size() - 1; i++) {
            TracePath a = paths.get(i);
            Pt end = a.pts.get(a.pts.size() - 1);
            int best = i + 1;
            float bestD = endpointDistance2(end, paths.get(best));
            int lim = Math.min(paths.size(), i + 1 + window);
            for (int j = i + 2; j < lim; j++) {
                float d = endpointDistance2(end, paths.get(j));
                if (d < bestD) { bestD = d; best = j; }
            }
            if (best != i + 1) Collections.swap(paths, i + 1, best);
        }
    }

    private static double pathHilbertKey(TracePath p) {
        Pt q = p.pts.get(p.pts.size() / 2);
        return q.y * 65536.0 + q.x;
    }

    private static float endpointDistance2(Pt from, TracePath p) {
        Pt a = p.pts.get(0), b = p.pts.get(p.pts.size() - 1);
        return Math.min(dist2(from, a), dist2(from, b));
    }

    private static float[] renderPaths(List<TracePath> source, int count, int grid,
                                       int offset, boolean stable, float smoothness) {
        if (count <= 0) return new float[0];
        float[] out = new float[count * 2];
        if (source == null || source.isEmpty()) return out;

        int use = Math.min(source.size(), Math.max(1, 18 + count / 34));
        ArrayList<TracePath> paths = new ArrayList<>(use);
        int start = stable ? 0 : Math.floorMod(offset, source.size());
        int stride = stable ? 1 : 37;
        boolean[] seen = new boolean[source.size()];
        for (int k = 0; k < source.size() && paths.size() < use; k++) {
            int idx = Math.floorMod(start + k * stride, source.size());
            if (!seen[idx]) { seen[idx] = true; paths.add(source.get(idx)); }
        }
        if (paths.isEmpty()) paths.add(source.get(0));

        double totalWeight = 0.0;
        for (TracePath p : paths) totalWeight += Math.max(1.0, p.length) * (0.55 + p.importance);
        int written = 0;
        float lastX = 0f, lastY = 0f;
        for (int pi = 0; pi < paths.size() && written < count; pi++) {
            TracePath p = paths.get(pi);
            int remaining = count - written;
            int n;
            if (pi == paths.size() - 1) n = remaining;
            else {
                double w = Math.max(1.0, p.length) * (0.55 + p.importance);
                n = clamp((int) Math.round(count * w / totalWeight), 6, remaining);
            }
            float[] r = resamplePath(p, n, grid, lastX, lastY, written > 0, smoothness);
            System.arraycopy(r, 0, out, written * 2, r.length);
            written += r.length / 2;
            if (written > 0) { lastX = out[(written - 1) * 2]; lastY = out[(written - 1) * 2 + 1]; }
        }
        while (written < count) {
            out[written * 2] = lastX;
            out[written * 2 + 1] = lastY;
            written++;
        }
        rotateLargestGap(out);
        return out;
    }

    private static float[] resamplePath(TracePath p, int n, int grid,
                                        float prevX, float prevY, boolean havePrev,
                                        float smoothness) {
        n = Math.max(2, n);
        int m = p.pts.size();
        if (m < 2) return new float[n * 2];

        boolean reverse = false;
        int rotate = 0;
        if (havePrev) {
            if (p.closed) {
                float best = Float.MAX_VALUE;
                for (int i = 0; i < m; i++) {
                    float nx = gridToNorm(p.pts.get(i).x, grid);
                    float ny = -gridToNorm(p.pts.get(i).y, grid);
                    float dx = nx - prevX, dy = ny - prevY;
                    float d = dx * dx + dy * dy;
                    if (d < best) { best = d; rotate = i; }
                }
            } else {
                Pt first = p.pts.get(0), last = p.pts.get(m - 1);
                float fx = gridToNorm(first.x, grid), fy = -gridToNorm(first.y, grid);
                float lx = gridToNorm(last.x, grid), ly = -gridToNorm(last.y, grid);
                float df = (fx - prevX) * (fx - prevX) + (fy - prevY) * (fy - prevY);
                float dl = (lx - prevX) * (lx - prevX) + (ly - prevY) * (ly - prevY);
                reverse = dl < df;
            }
        }

        int segs = p.closed ? m : m - 1;
        float[] cumulative = new float[segs + 1];
        cumulative[0] = 0f;
        for (int i = 0; i < segs; i++) {
            Pt a = pathPoint(p, i, rotate, reverse);
            Pt b = pathPoint(p, i + 1, rotate, reverse);
            float dx = b.x - a.x, dy = b.y - a.y;
            cumulative[i + 1] = cumulative[i] + (float) Math.sqrt(dx * dx + dy * dy);
        }
        float total = cumulative[segs];
        float[] out = new float[n * 2];
        if (total <= 1e-6f) return out;
        int seg = 0;
        for (int i = 0; i < n; i++) {
            float u = (p.closed ? i / (float) n : i / (float) Math.max(1, n - 1)) * total;
            while (seg < segs - 1 && cumulative[seg + 1] < u) seg++;
            float den = Math.max(1e-6f, cumulative[seg + 1] - cumulative[seg]);
            float f = (u - cumulative[seg]) / den;
            Pt a = pathPoint(p, seg, rotate, reverse);
            Pt b = pathPoint(p, seg + 1, rotate, reverse);
            float x = a.x + (b.x - a.x) * f;
            float y = a.y + (b.y - a.y) * f;
            out[i * 2] = gridToNorm(x, grid);
            out[i * 2 + 1] = -gridToNorm(y, grid);
        }
        if (smoothness > 0.03f) smoothClosedSafe(out, smoothness, p.closed);
        return out;
    }

    private static Pt pathPoint(TracePath p, int logical, int rotate, boolean reverse) {
        int m = p.pts.size();
        int idx;
        if (p.closed) idx = Math.floorMod(rotate + logical, m);
        else idx = reverse ? (m - 1 - clamp(logical, 0, m - 1)) : clamp(logical, 0, m - 1);
        return p.pts.get(idx);
    }

    private static void smoothClosedSafe(float[] xy, float amount, boolean closed) {
        int n = xy.length / 2;
        if (n < 5) return;
        float a = clamp(amount * 0.34f, 0f, 0.32f);
        float[] tmp = xy.clone();
        int passes = amount > 0.65f ? 2 : 1;
        for (int pass = 0; pass < passes; pass++) {
            for (int i = 1; i < n - 1; i++) {
                xy[i * 2] = tmp[i * 2] * (1f - 2f * a) + a * (tmp[(i - 1) * 2] + tmp[(i + 1) * 2]);
                xy[i * 2 + 1] = tmp[i * 2 + 1] * (1f - 2f * a) + a * (tmp[(i - 1) * 2 + 1] + tmp[(i + 1) * 2 + 1]);
            }
            if (closed) {
                xy[0] = tmp[0] * (1f - 2f * a) + a * (tmp[(n - 1) * 2] + tmp[2]);
                xy[1] = tmp[1] * (1f - 2f * a) + a * (tmp[(n - 1) * 2 + 1] + tmp[3]);
            }
            System.arraycopy(xy, 0, tmp, 0, xy.length);
        }
    }

    private static final class StippleResult {
        final float[] xy;
        final int centers;
        StippleResult(float[] xy, int centers) { this.xy = xy; this.centers = centers; }
    }

    private static StippleResult renderStipple(Field f, int count, long seed, Settings s, int cell) {
        if (count <= 0 || s.fillStrength <= 0.001f) return new StippleResult(new float[Math.max(0, count) * 2], 0);
        int orbit = s.profile == 2 ? 3 : 4;
        int centers = Math.max(1, count / orbit);
        double[] cdf = new double[f.fillWeight.length];
        double total = 0.0;
        for (int i = 0; i < f.fillWeight.length; i++) {
            double w = Math.pow(Math.max(0.0, f.fillWeight[i]), 0.72 + 0.42 * (1.0 - s.fillStrength));
            total += w;
            cdf[i] = total;
        }
        float[] out = new float[count * 2];
        if (total <= 1e-12) return new StippleResult(out, 0);
        float baseRadius = (1.84f / Math.max(1, f.grid - 1f)) * (0.34f + 0.55f * s.fillStrength);
        int o = 0;
        double rot = fract(seed * 0.7548776662466927 + cell * 0.2193456688);
        for (int k = 0; k < centers && o / 2 < count; k++) {
            double u = fract((k + 0.5) * GOLD + rot) * total;
            int idx = lowerBound(cdf, u);
            int px = idx % f.grid, py = idx / f.grid;
            float cx = gridToNorm(px + (float) (halton(k + 1 + (int) (seed & 63), 2) - 0.5), f.grid);
            float cy = -gridToNorm(py + (float) (halton(k + 1 + (int) ((seed >> 3) & 63), 3) - 0.5), f.grid);
            float g = f.grad[idx];
            float gx = f.gx[idx], gy = -f.gy[idx];
            float gm = (float) Math.sqrt(gx * gx + gy * gy);
            float tx = gm > 1e-5f ? -gy / gm : 1f;
            float ty = gm > 1e-5f ? gx / gm : 0f;
            float nx = -ty, ny = tx;
            for (int j = 0; j < orbit && o / 2 < count; j++) {
                double a = 2.0 * Math.PI * j / orbit + k * GOLD_ANGLE;
                float along = (float) Math.cos(a) * baseRadius * (0.72f + 0.55f * g);
                float across = (float) Math.sin(a) * baseRadius * (0.45f + 0.30f * (1f - g));
                out[o++] = clamp(cx + tx * along + nx * across, -0.96f, 0.96f);
                out[o++] = clamp(cy + ty * along + ny * across, -0.96f, 0.96f);
            }
        }
        float lx = o >= 2 ? out[o - 2] : 0f, ly = o >= 2 ? out[o - 1] : 0f;
        while (o < out.length) { out[o++] = lx; out[o++] = ly; }
        return new StippleResult(out, centers);
    }

    private static void applyResonantMicroField(float[] xy, Settings s, double latticeHz, long frameIndex) {
        if (s.resonance <= 0.001f || s.bank < 0 || s.bank >= BANKS.length || xy.length < 8) return;
        double[] bank = BANKS[s.bank];
        int n = xy.length / 2;
        float amp = 0.0012f + 0.0048f * clamp01(s.resonance);
        double phaseBase = (s.temporal ? 0.0 : frameIndex * 0.1732050807568877);
        for (int i = 1; i < n - 1; i++) {
            float dx = xy[(i + 1) * 2] - xy[(i - 1) * 2];
            float dy = xy[(i + 1) * 2 + 1] - xy[(i - 1) * 2 + 1];
            float d2 = dx * dx + dy * dy;
            if (d2 > 0.028f || d2 < 1e-8f) continue;
            float inv = 1f / (float) Math.sqrt(d2);
            float nx = -dy * inv, ny = dx * inv;
            double t = i / (double) Math.max(1, s.sampleRate);
            double m = 0.0, ws = 0.0;
            for (int k = 0; k < bank.length; k++) {
                double w = 1.0 / (1.0 + 0.46 * k);
                m += w * Math.sin(2.0 * Math.PI * bank[k] * t + phaseBase + k * GOLD_ANGLE);
                ws += w;
            }
            m /= Math.max(1e-9, ws);
            xy[i * 2] += nx * amp * (float) m;
            xy[i * 2 + 1] += ny * amp * (float) m;
        }
    }

    private static void transform(float[] xy, Settings s) {
        double r = Math.toRadians(s.rotationDeg);
        float cs = (float) Math.cos(r), sn = (float) Math.sin(r);
        for (int i = 0; i < xy.length; i += 2) {
            float x = xy[i] * s.xGain;
            float y = xy[i + 1] * s.yGain;
            float xr = x * cs - y * sn;
            float yr = x * sn + y * cs;
            xy[i] = clamp(xr, -0.985f, 0.985f);
            xy[i + 1] = clamp(yr, -0.985f, 0.985f);
        }
    }

    private static void localSpectralSmooth(float[] xy, float amount) {
        int n = xy.length / 2;
        if (n < 5) return;
        float a = clamp(amount * 0.16f, 0f, 0.16f);
        float[] src = xy.clone();
        for (int i = 1; i < n - 1; i++) {
            float dx0 = src[i * 2] - src[(i - 1) * 2];
            float dy0 = src[i * 2 + 1] - src[(i - 1) * 2 + 1];
            float dx1 = src[(i + 1) * 2] - src[i * 2];
            float dy1 = src[(i + 1) * 2 + 1] - src[i * 2 + 1];
            if (dx0 * dx0 + dy0 * dy0 > 0.022f || dx1 * dx1 + dy1 * dy1 > 0.022f) continue;
            xy[i * 2] = src[i * 2] * (1f - 2f * a) + a * (src[(i - 1) * 2] + src[(i + 1) * 2]);
            xy[i * 2 + 1] = src[i * 2 + 1] * (1f - 2f * a) + a * (src[(i - 1) * 2 + 1] + src[(i + 1) * 2 + 1]);
        }
    }

    private static void rotateLargestGap(float[] xy) {
        int n = xy.length / 2;
        if (n < 4) return;
        int at = n - 1;
        float max = segmentD2(xy, n - 1, 0);
        for (int i = 0; i < n - 1; i++) {
            float d = segmentD2(xy, i, i + 1);
            if (d > max) { max = d; at = i; }
        }
        if (at == n - 1) return;
        int shift = at + 1;
        float[] copy = xy.clone();
        for (int i = 0; i < n; i++) {
            int src = (i + shift) % n;
            xy[i * 2] = copy[src * 2];
            xy[i * 2 + 1] = copy[src * 2 + 1];
        }
    }

    private static int countFlybacks(float[] xy) {
        int n = xy.length / 2;
        int count = 0;
        for (int i = 1; i < n; i++) if (segmentD2(xy, i - 1, i) > 0.028f) count++;
        if (n > 2 && segmentD2(xy, n - 1, 0) > 0.028f) count++;
        return count;
    }

    static float[] circle(Settings s, int n) {
        n = clamp(n, 512, 32000);
        float[] xy = new float[n * 2];
        for (int i = 0; i < n; i++) {
            double a = 2.0 * Math.PI * i / n;
            xy[i * 2] = (float) Math.cos(a) * 0.82f;
            xy[i * 2 + 1] = (float) Math.sin(a) * 0.82f;
        }
        transform(xy, s);
        return xy;
    }

    static float[] grid(Settings s, int n) {
        n = clamp(n, 1024, 32000);
        float[] xy = new float[n * 2];
        int lines = 9;
        int segs = lines * 2;
        for (int i = 0; i < n; i++) {
            float t = i / (float) Math.max(1, n - 1) * segs;
            int seg = Math.min(segs - 1, (int) t);
            float u = t - seg;
            float x, y;
            if ((seg & 1) == 0) {
                int row = seg / 2;
                y = -0.82f + 1.64f * row / Math.max(1, lines - 1f);
                x = -0.82f + 1.64f * u;
            } else {
                int col = seg / 2;
                x = -0.82f + 1.64f * col / Math.max(1, lines - 1f);
                y = -0.82f + 1.64f * u;
            }
            xy[i * 2] = x;
            xy[i * 2 + 1] = y;
        }
        transform(xy, s);
        return xy;
    }

    private static float[] boxBlur(float[] src, int grid, int radius) {
        float[] tmp = new float[src.length];
        float[] out = new float[src.length];
        int dia = radius * 2 + 1;
        for (int y = 0; y < grid; y++) {
            float sum = 0f;
            for (int x = -radius; x <= radius; x++) sum += src[y * grid + clamp(x, 0, grid - 1)];
            for (int x = 0; x < grid; x++) {
                tmp[y * grid + x] = sum / dia;
                int remove = clamp(x - radius, 0, grid - 1);
                int add = clamp(x + radius + 1, 0, grid - 1);
                sum += src[y * grid + add] - src[y * grid + remove];
            }
        }
        for (int x = 0; x < grid; x++) {
            float sum = 0f;
            for (int y = -radius; y <= radius; y++) sum += tmp[clamp(y, 0, grid - 1) * grid + x];
            for (int y = 0; y < grid; y++) {
                out[y * grid + x] = sum / dia;
                int remove = clamp(y - radius, 0, grid - 1);
                int add = clamp(y + radius + 1, 0, grid - 1);
                sum += tmp[add * grid + x] - tmp[remove * grid + x];
            }
        }
        return out;
    }

    private static float percentile(float[] a, float q) {
        float[] b = a.clone();
        java.util.Arrays.sort(b);
        return b[clamp(Math.round(clamp01(q) * (b.length - 1)), 0, b.length - 1)];
    }

    private static float gridToNorm(float v, int grid) {
        return (v - (grid - 1) * 0.5f) * (1.84f / Math.max(1f, grid - 1f));
    }

    private static int lowerBound(double[] a, double v) {
        int lo = 0, hi = a.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < v) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private static double halton(int index, int base) {
        double f = 1.0, r = 0.0;
        int i = Math.max(1, index);
        while (i > 0) { f /= base; r += f * (i % base); i /= base; }
        return r;
    }

    private static double fract(double x) { return x - Math.floor(x); }
    private static float dist2(Pt a, Pt b) { float x = a.x - b.x, y = a.y - b.y; return x * x + y * y; }
    private static float segmentD2(float[] xy, int a, int b) {
        float dx = xy[a * 2] - xy[b * 2], dy = xy[a * 2 + 1] - xy[b * 2 + 1];
        return dx * dx + dy * dy;
    }
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
