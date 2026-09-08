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
 * OsciVision Ultra v4 synthesis core.
 *
 * Design goals:
 *  - full-field, non-raster XY synthesis
 *  - multi-scale photo/edge/detail preservation
 *  - closed-loop residual correction against a simulated phosphor density map
 *  - low-travel path planning with local 2-opt cleanup
 *  - geometry-safe harmonic timing plus a tiny harmonic micro-orbit "aura"
 *  - deterministic temporal mode for GIF/video
 */
final class V4Engine {
    private V4Engine() {}

    static final class Settings {
        int sampleRate = 192000;
        int fps = 12;
        int quality = 97;
        int profile = 0;      // 0 ultra photo, 1 portrait/detail, 2 video stable, 3 edge/line
        int residualPasses = 3;
        int bank = 0;         // 0=81, 1=64, 2=49, 3=raw
        boolean temporal = true;
        boolean invert = false;
        float harmony = 0.34f;
        float aura = 0.16f;
        float gamma = 0.95f;
        float xGain = 1f;
        float yGain = 1f;
        float rotationDeg = 0f;
        float lowFrequencyLift = 0.12f;
        float smoothness = 0.28f;

        Settings copy() {
            Settings s = new Settings();
            s.sampleRate = sampleRate;
            s.fps = fps;
            s.quality = quality;
            s.profile = profile;
            s.residualPasses = residualPasses;
            s.bank = bank;
            s.temporal = temporal;
            s.invert = invert;
            s.harmony = harmony;
            s.aura = aura;
            s.gamma = gamma;
            s.xGain = xGain;
            s.yGain = yGain;
            s.rotationDeg = rotationDeg;
            s.lowFrequencyLift = lowFrequencyLift;
            s.smoothness = smoothness;
            return s;
        }
    }

    static final class Result {
        final float[] xy;
        final float matchScore;
        final float pathScore;
        final int flybacks;
        final int points;
        final int grid;

        Result(float[] xy, float matchScore, float pathScore, int flybacks, int points, int grid) {
            this.xy = xy;
            this.matchScore = matchScore;
            this.pathScore = pathScore;
            this.flybacks = flybacks;
            this.points = points;
            this.grid = grid;
        }
    }

    private static final class Point {
        float x, y;
        int hilbert;
        Point(float x, float y, int hilbert) {
            this.x = x;
            this.y = y;
            this.hilbert = hilbert;
        }
    }

    private static final class Gradient {
        final float[] gx, gy, mag;
        Gradient(float[] gx, float[] gy, float[] mag) {
            this.gx = gx;
            this.gy = gy;
            this.mag = mag;
        }
    }

    private static final class Field {
        final int grid;
        final float[] lum;
        final float[] edge;
        final float[] detail;
        final float[] contrast;
        final float[] target;
        final Gradient grad;

        Field(int grid, float[] lum, float[] edge, float[] detail, float[] contrast,
              float[] target, Gradient grad) {
            this.grid = grid;
            this.lum = lum;
            this.edge = edge;
            this.detail = detail;
            this.contrast = contrast;
            this.target = target;
            this.grad = grad;
        }
    }

    private static final double GOLDEN_CONJ = 0.6180339887498948482;
    private static final double GOLDEN_ANGLE = 2.39996322972865332;
    private static final double[][] BANKS = {
            {81, 121.5, 162, 243, 324, 486, 729},
            {64, 80, 96, 128, 160, 192, 256, 320, 384, 512},
            {49, 73.5, 98, 122.5, 147, 196, 245, 343, 490, 686}
    };

    static Result compile(Bitmap input, Settings s, long frameIndex) {
        if (input == null) return empty();
        int rate = clamp(s.sampleRate, 8000, 192000);
        int fps = clamp(s.fps, 4, 120);
        int budget = clamp(Math.round(rate / (float) fps), 1200, 32000);

        int baseGrid = 144 + Math.round(s.quality * 2.16f);
        if (s.profile == 2) baseGrid -= 36;
        if (s.profile == 3) baseGrid += 20;
        int grid = clamp(baseGrid, 144, 384);

        Field field = buildField(input, s, grid);
        if (max(field.target) < 1e-5f) return empty();

        List<Point> points = closedLoopSample(field, s, budget, frameIndex);
        if (points.size() < 4) return empty();

        int side = nextPow2(grid);
        int bits = Integer.numberOfTrailingZeros(side);
        for (Point p : points) {
            p.hilbert = hilbertIndex(
                    clamp(Math.round(p.x), 0, grid - 1),
                    clamp(Math.round(p.y), 0, grid - 1),
                    bits);
        }

        Collections.sort(points, Comparator.comparingInt(a -> a.hilbert));
        int window = s.quality >= 92 ? 30 : (s.quality >= 75 ? 22 : 14);
        localNearest(points, window);
        twoOpt(points, s.quality >= 90 ? 18 : 11, s.quality >= 90 ? 2 : 1);
        if (!s.temporal) rotateLargestGapToSeam(points);

        float match = estimateMatch(field.target, grid, points);
        float path = pathScore(points, grid);

        float[] xy = pointsToXY(points, grid, s);
        xy = smoothLocalSegments(xy, s.smoothness, s.quality);
        xy = applyHarmonicTiming(xy, s, frameIndex);
        xy = applyHarmonicAura(xy, s, frameIndex);
        xy = applyLowFrequencyLift(xy, s.lowFrequencyLift);
        softLimit(xy);

        int flybacks = countFlybacks(xy, 0.075f);
        return new Result(xy, match, path, flybacks, xy.length / 2, grid);
    }

    private static Result empty() {
        return new Result(new float[]{0f, 0f, 0f, 0f}, 0f, 0f, 0, 2, 2);
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

        int[] px = new int[grid * grid];
        square.getPixels(px, 0, grid, 0, 0, grid, grid);
        float[] lum = new float[px.length];
        float gamma = clamp(s.gamma, 0.35f, 2.3f);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            float l = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
            if (s.invert) l = 1f - l;
            lum[i] = (float) Math.pow(clamp01(l), gamma);
        }

        float[] blur = blur5(lum, grid, grid);
        Gradient gFine = gradients(lum, grid, grid);
        Gradient gCoarse = gradients(blur, grid, grid);
        float[] detail = laplacianDetail(lum, grid, grid);
        float[] contrast = new float[lum.length];
        float maxContrast = 1e-6f;
        for (int i = 0; i < contrast.length; i++) {
            float v = Math.abs(lum[i] - blur[i]);
            contrast[i] = v;
            if (v > maxContrast) maxContrast = v;
        }
        for (int i = 0; i < contrast.length; i++) contrast[i] = clamp01(contrast[i] / maxContrast);

        float[] edge = new float[lum.length];
        float[] target = new float[lum.length];
        for (int i = 0; i < lum.length; i++) {
            float e = clamp01(gFine.mag[i] * 0.72f + gCoarse.mag[i] * 0.42f);
            e = (float) Math.pow(e, 0.72);
            edge[i] = e;
            float l = lum[i];
            float d = detail[i];
            float c = contrast[i];
            float t;
            switch (s.profile) {
                case 1: // portrait/detail: protect midtone structure and small facial features
                    float mid = 1f - Math.min(1f, Math.abs(l - 0.52f) * 1.75f);
                    t = l * 0.54f + e * 0.31f + d * 0.18f + c * 0.16f + mid * e * 0.12f;
                    break;
                case 2: // video stable: less microdetail, more persistent structure
                    t = l * 0.70f + e * 0.28f + c * 0.08f + d * 0.05f;
                    break;
                case 3: // edge / line art
                    t = e * 0.78f + d * 0.34f + c * 0.12f + l * 0.08f;
                    break;
                default: // ultra photo
                    t = l * 0.62f + e * 0.31f + d * 0.17f + c * 0.13f;
                    break;
            }
            target[i] = clamp01(t);
        }

        normalizeEnergy(target);
        return new Field(grid, lum, edge, detail, contrast, target, gFine);
    }

    private static List<Point> closedLoopSample(Field f, Settings s, int budget, long frameIndex) {
        int passes = clamp(s.residualPasses, 1, 4);
        ArrayList<Point> out = new ArrayList<>(budget);
        float[] density = new float[f.target.length];
        float[] residual = f.target.clone();
        int used = 0;

        for (int pass = 0; pass < passes; pass++) {
            int remaining = budget - used;
            if (remaining <= 0) break;
            int count;
            if (pass == passes - 1) count = remaining;
            else {
                float share;
                if (passes == 2) share = pass == 0 ? 0.66f : 0.34f;
                else if (passes == 3) share = pass == 0 ? 0.52f : 0.56f;
                else share = pass == 0 ? 0.43f : (pass == 1 ? 0.48f : 0.54f);
                count = Math.max(64, Math.round(remaining * share));
                count = Math.min(count, remaining);
            }

            float[] weights = new float[f.target.length];
            for (int i = 0; i < weights.length; i++) {
                float base = f.target[i];
                float miss = pass == 0 ? base : residual[i];
                float edgeProtect = f.edge[i] * (s.profile == 3 ? 0.42f : 0.18f);
                float micro = f.detail[i] * (0.05f + 0.08f * s.quality / 100f);
                weights[i] = Math.max(0f, miss * (pass == 0 ? 1f : 1.55f) + edgeProtect + micro);
            }

            long seed;
            if (s.temporal) seed = 17L + pass * 101L;
            else seed = frameIndex * 977L + pass * 101L + 17L;
            sampleWeighted(weights, f, count, out, seed, s.profile == 3 || pass > 0);
            used = out.size();

            depositIncremental(density, f.grid, out, used - count, used);
            float scale = bestDensityScale(f.target, density);
            for (int i = 0; i < residual.length; i++) {
                float predicted = clamp01(density[i] * scale);
                float miss = Math.max(0f, f.target[i] - predicted);
                // A small target floor prevents the correction loop from abandoning already-good structure.
                residual[i] = clamp01(miss * 1.75f + f.target[i] * 0.13f);
            }
        }

        while (out.size() < budget && !out.isEmpty()) {
            Point p = out.get(out.size() % Math.max(1, out.size()));
            out.add(new Point(p.x, p.y, p.hilbert));
        }
        if (out.size() > budget) out.subList(budget, out.size()).clear();
        return out;
    }

    private static void sampleWeighted(float[] weights, Field f, int count, List<Point> out,
                                       long seed, boolean edgeBiased) {
        if (count <= 0) return;
        double[] cdf = new double[weights.length];
        double total = 0.0;
        for (int i = 0; i < weights.length; i++) {
            total += Math.max(0.0, weights[i]);
            cdf[i] = total;
        }
        if (total <= 1e-12) return;

        double rot = fract(seed * 0.7548776662466927 + 0.1732050807568877);
        for (int k = 0; k < count; k++) {
            double u = fract((k + 0.5) * GOLDEN_CONJ + rot) * total;
            int idx = lowerBound(cdf, u);
            int x = idx % f.grid;
            int y = idx / f.grid;
            int seq = k + 1 + (int) (seed & 1023);
            float hx = (float) (halton(seq, 2) - 0.5);
            float hy = (float) (halton(seq, 3) - 0.5);
            float jx, jy;
            if (edgeBiased && f.grad.mag[idx] > 0.035f) {
                float gx = f.grad.gx[idx];
                float gy = f.grad.gy[idx];
                float inv = 1f / Math.max(1e-6f, (float) Math.sqrt(gx * gx + gy * gy));
                float tx = -gy * inv;
                float ty = gx * inv;
                float along = hx * 0.94f;
                float across = hy * 0.13f;
                jx = tx * along + gx * inv * across;
                jy = ty * along + gy * inv * across;
            } else {
                // Rotated low-discrepancy jitter avoids axis-aligned "pixel grit".
                double a = GOLDEN_ANGLE * (seq & 255);
                float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                jx = (hx * ca - hy * sa) * 0.92f;
                jy = (hx * sa + hy * ca) * 0.92f;
            }
            out.add(new Point(clamp(x + jx, 0f, f.grid - 1f),
                    clamp(y + jy, 0f, f.grid - 1f), 0));
        }
    }

    private static void depositIncremental(float[] density, int grid, List<Point> pts, int from, int to) {
        from = Math.max(0, from);
        to = Math.min(to, pts.size());
        for (int i = from; i < to; i++) {
            Point p = pts.get(i);
            int x = clamp(Math.round(p.x), 1, grid - 2);
            int y = clamp(Math.round(p.y), 1, grid - 2);
            int idx = y * grid + x;
            density[idx] += 1.0f;
            density[idx - 1] += 0.28f;
            density[idx + 1] += 0.28f;
            density[idx - grid] += 0.28f;
            density[idx + grid] += 0.28f;
            density[idx - grid - 1] += 0.09f;
            density[idx - grid + 1] += 0.09f;
            density[idx + grid - 1] += 0.09f;
            density[idx + grid + 1] += 0.09f;
        }
    }

    private static float bestDensityScale(float[] target, float[] density) {
        double td = 0.0, dd = 0.0;
        for (int i = 0; i < target.length; i++) {
            td += target[i] * density[i];
            dd += density[i] * density[i];
        }
        return dd <= 1e-12 ? 1f : (float) (td / dd);
    }

    private static float estimateMatch(float[] target, int grid, List<Point> points) {
        float[] density = new float[target.length];
        depositIncremental(density, grid, points, 0, points.size());
        float scale = bestDensityScale(target, density);
        double mae = 0.0;
        double energy = 0.0;
        for (int i = 0; i < target.length; i++) {
            float pred = clamp01(density[i] * scale);
            mae += Math.abs(target[i] - pred);
            energy += target[i] + 0.04;
        }
        float score = (float) (1.0 - mae / Math.max(1e-9, energy));
        return clamp(score * 100f, 0f, 100f);
    }

    private static void localNearest(List<Point> p, int window) {
        int n = p.size();
        for (int i = 0; i < n - 2; i++) {
            Point cur = p.get(i);
            int best = i + 1;
            float bestD = dist2(cur, p.get(best));
            int end = Math.min(n, i + 1 + window);
            for (int j = i + 2; j < end; j++) {
                float d = dist2(cur, p.get(j));
                if (d < bestD) {
                    bestD = d;
                    best = j;
                }
            }
            if (best != i + 1) Collections.swap(p, i + 1, best);
        }
    }

    private static void twoOpt(List<Point> p, int window, int passes) {
        int n = p.size();
        if (n < 8) return;
        for (int pass = 0; pass < passes; pass++) {
            for (int i = 0; i < n - 4; i += 2) {
                Point a = p.get(i);
                Point b = p.get(i + 1);
                float ab = dist2(a, b);
                int end = Math.min(n - 1, i + 2 + window);
                for (int j = i + 2; j < end; j++) {
                    Point c = p.get(j);
                    Point d = p.get(j + 1);
                    float before = ab + dist2(c, d);
                    float after = dist2(a, c) + dist2(b, d);
                    if (after + 0.0001f < before) {
                        reverse(p, i + 1, j);
                        break;
                    }
                }
            }
        }
    }

    private static void reverse(List<Point> p, int lo, int hi) {
        while (lo < hi) Collections.swap(p, lo++, hi--);
    }

    private static void rotateLargestGapToSeam(List<Point> p) {
        int n = p.size();
        if (n < 4) return;
        int at = n - 1;
        float max = dist2(p.get(n - 1), p.get(0));
        for (int i = 0; i < n - 1; i++) {
            float d = dist2(p.get(i), p.get(i + 1));
            if (d > max) {
                max = d;
                at = i;
            }
        }
        if (at != n - 1) Collections.rotate(p, -(at + 1));
    }

    private static float pathScore(List<Point> p, int grid) {
        if (p.size() < 2) return 0f;
        double sum = 0.0;
        int longJumps = 0;
        float longThreshold = grid * grid * 0.010f;
        for (int i = 1; i < p.size(); i++) {
            float d = dist2(p.get(i - 1), p.get(i));
            sum += Math.sqrt(d) / Math.max(1f, grid);
            if (d > longThreshold) longJumps++;
        }
        double avg = sum / (p.size() - 1);
        float local = (float) Math.exp(-avg * 18.0) * 100f;
        float penalty = Math.min(35f, longJumps * 0.12f);
        return clamp(local - penalty, 0f, 100f);
    }

    private static float[] pointsToXY(List<Point> pts, int grid, Settings s) {
        float[] out = new float[pts.size() * 2];
        double rad = Math.toRadians(s.rotationDeg);
        float cs = (float) Math.cos(rad);
        float sn = (float) Math.sin(rad);
        float half = (grid - 1) * 0.5f;
        float norm = 1.84f / Math.max(1f, grid - 1f);
        int o = 0;
        for (Point p : pts) {
            float x = (p.x - half) * norm * s.xGain;
            float y = -(p.y - half) * norm * s.yGain;
            float xr = x * cs - y * sn;
            float yr = x * sn + y * cs;
            out[o++] = xr;
            out[o++] = yr;
        }
        return out;
    }

    private static float[] smoothLocalSegments(float[] xy, float amount, int quality) {
        if (xy == null || xy.length < 12) return xy;
        float a = clamp(amount, 0f, 1f) * (0.15f - quality * 0.00055f);
        a = clamp(a, 0f, 0.11f);
        if (a <= 0.0001f) return xy;
        float[] out = xy.clone();
        int n = xy.length / 2;
        for (int pass = 0; pass < 2; pass++) {
            float[] src = out.clone();
            for (int i = 1; i < n - 1; i++) {
                int p = i * 2;
                float x0 = src[p - 2], y0 = src[p - 1];
                float x1 = src[p], y1 = src[p + 1];
                float x2 = src[p + 2], y2 = src[p + 3];
                float d0x = x1 - x0, d0y = y1 - y0;
                float d1x = x2 - x1, d1y = y2 - y1;
                if (d0x * d0x + d0y * d0y < 0.010f && d1x * d1x + d1y * d1y < 0.010f) {
                    out[p] = x1 * (1f - 2f * a) + (x0 + x2) * a;
                    out[p + 1] = y1 * (1f - 2f * a) + (y0 + y2) * a;
                }
            }
        }
        return out;
    }

    private static float[] applyHarmonicTiming(float[] xy, Settings s, long frameIndex) {
        if (xy == null || xy.length < 8 || s.bank < 0 || s.bank >= BANKS.length || s.harmony <= 0.002f) return xy;
        int n = xy.length / 2;
        double[] cum = new double[n];
        cum[0] = 0.0;
        double[] bank = BANKS[s.bank];
        double wsum = 0.0;
        for (int k = 0; k < bank.length; k++) wsum += 1.0 / (1.0 + k * 0.48);
        double strength = clamp(s.harmony, 0f, 1f) * 0.68;
        double t0 = frameIndex * n / (double) Math.max(1, s.sampleRate);
        for (int i = 1; i < n; i++) {
            double t = t0 + i / (double) s.sampleRate;
            double m = 0.0;
            for (int k = 0; k < bank.length; k++) {
                double w = 1.0 / (1.0 + k * 0.48);
                double phase = k * GOLDEN_ANGLE;
                m += w * Math.sin(2.0 * Math.PI * bank[k] * t + phase);
            }
            m /= Math.max(1e-9, wsum);
            double speed = Math.exp(strength * m);
            cum[i] = cum[i - 1] + speed;
        }
        double total = cum[n - 1];
        if (total <= 1e-9) return xy;
        float[] out = new float[xy.length];
        int src = 0;
        for (int j = 0; j < n; j++) {
            double target = total * j / Math.max(1.0, n - 1.0);
            while (src < n - 2 && cum[src + 1] < target) src++;
            double den = Math.max(1e-12, cum[src + 1] - cum[src]);
            float f = (float) ((target - cum[src]) / den);
            float x0 = xy[src * 2], y0 = xy[src * 2 + 1];
            float x1 = xy[(src + 1) * 2], y1 = xy[(src + 1) * 2 + 1];
            float dx = x1 - x0, dy = y1 - y0;
            if (dx * dx + dy * dy > 0.0085f) f = f < 0.5f ? 0f : 1f;
            out[j * 2] = x0 + dx * f;
            out[j * 2 + 1] = y0 + dy * f;
        }
        return out;
    }

    private static float[] applyHarmonicAura(float[] xy, Settings s, long frameIndex) {
        if (xy == null || xy.length < 12 || s.bank < 0 || s.bank >= BANKS.length || s.aura <= 0.002f) return xy;
        float[] out = xy.clone();
        int n = xy.length / 2;
        double[] bank = BANKS[s.bank];
        float amp = 0.0007f + 0.0085f * clamp(s.aura, 0f, 1f) * (0.35f + 0.65f * clamp(s.harmony, 0f, 1f));
        double t0 = frameIndex * n / (double) Math.max(1, s.sampleRate);
        for (int i = 1; i < n - 1; i++) {
            int p = i * 2;
            float tx = xy[p + 2] - xy[p - 2];
            float ty = xy[p + 3] - xy[p - 1];
            float len = (float) Math.sqrt(tx * tx + ty * ty);
            if (len < 1e-7f || len > 0.22f) continue;
            float nx = -ty / len;
            float ny = tx / len;
            double t = t0 + i / (double) s.sampleRate;
            double osc = 0.0;
            double norm = 0.0;
            int tones = Math.min(5, bank.length);
            for (int k = 0; k < tones; k++) {
                double w = 1.0 / (1.0 + k * 0.65);
                osc += w * Math.sin(2.0 * Math.PI * bank[k] * t + k * 1.41421356237);
                norm += w;
            }
            float v = (float) (osc / Math.max(1e-9, norm));
            out[p] += nx * amp * v;
            out[p + 1] += ny * amp * v;
        }
        return out;
    }

    private static float[] applyLowFrequencyLift(float[] xy, float amount) {
        amount = clamp(amount, 0f, 0.45f);
        if (xy == null || xy.length < 8 || amount <= 0.001f) return xy;
        float[] out = xy.clone();
        float lpx = out[0], lpy = out[1];
        final float k = 0.012f;
        float max = 0f;
        for (int i = 0; i + 1 < out.length; i += 2) {
            float x = out[i], y = out[i + 1];
            lpx += k * (x - lpx);
            lpy += k * (y - lpy);
            float xx = x + amount * lpx;
            float yy = y + amount * lpy;
            out[i] = xx;
            out[i + 1] = yy;
            max = Math.max(max, Math.max(Math.abs(xx), Math.abs(yy)));
        }
        if (max > 0.985f) {
            float scale = 0.985f / max;
            for (int i = 0; i < out.length; i++) out[i] *= scale;
        }
        return out;
    }

    static float[] makeStillSuperLoop(Result rawResult, Settings s, int seconds) {
        if (rawResult == null || rawResult.xy == null) return new float[]{0f, 0f, 0f, 0f};
        float[] base = rawResult.xy;
        int frames = clamp(s.fps * clamp(seconds, 1, 4), 2, 180);
        long total = (long) base.length * frames;
        if (total > 8_000_000L) frames = Math.max(2, 8_000_000 / Math.max(1, base.length));
        float[] out = new float[Math.max(base.length, base.length * frames)];
        int pos = 0;
        for (int f = 0; f < frames; f++) {
            float[] timed = applyHarmonicTiming(base, s, f);
            timed = applyHarmonicAura(timed, s, f);
            int copy = Math.min(timed.length, out.length - pos);
            if (copy <= 0) break;
            System.arraycopy(timed, 0, out, pos, copy);
            pos += copy;
        }
        if (pos == out.length) return out;
        float[] trimmed = new float[pos];
        System.arraycopy(out, 0, trimmed, 0, pos);
        return trimmed;
    }

    static float[] circlePattern(Settings s) {
        int n = clamp(s.sampleRate / Math.max(1, s.fps), 1200, 32000);
        float[] out = new float[n * 2];
        for (int i = 0; i < n; i++) {
            double a = 2.0 * Math.PI * i / n;
            out[i * 2] = (float) Math.cos(a) * 0.82f;
            out[i * 2 + 1] = (float) Math.sin(a) * 0.82f;
        }
        return transformPattern(out, s);
    }

    static float[] gridPattern(Settings s) {
        int n = clamp(s.sampleRate / Math.max(1, s.fps), 1200, 32000);
        float[] out = new float[n * 2];
        int lines = 10;
        for (int i = 0; i < n; i++) {
            float t = i / (float) Math.max(1, n - 1);
            int seg = Math.min(lines * 2 - 1, (int) (t * lines * 2));
            float u = t * lines * 2 - seg;
            float x, y;
            if ((seg & 1) == 0) {
                y = -0.8f + 1.6f * ((seg / 2f) / Math.max(1, lines - 1));
                x = -0.8f + 1.6f * u;
            } else {
                x = -0.8f + 1.6f * ((seg / 2f) / Math.max(1, lines - 1));
                y = -0.8f + 1.6f * u;
            }
            out[i * 2] = x;
            out[i * 2 + 1] = y;
        }
        return transformPattern(out, s);
    }

    private static float[] transformPattern(float[] xy, Settings s) {
        double rad = Math.toRadians(s.rotationDeg);
        float cs = (float) Math.cos(rad), sn = (float) Math.sin(rad);
        float[] out = xy.clone();
        for (int i = 0; i + 1 < out.length; i += 2) {
            float x = out[i] * s.xGain;
            float y = out[i + 1] * s.yGain;
            out[i] = clamp(x * cs - y * sn, -0.985f, 0.985f);
            out[i + 1] = clamp(x * sn + y * cs, -0.985f, 0.985f);
        }
        return out;
    }

    private static int countFlybacks(float[] xy, float threshold) {
        if (xy == null || xy.length < 6) return 0;
        float t2 = threshold * threshold;
        int count = 0;
        for (int i = 2; i + 1 < xy.length; i += 2) {
            float dx = xy[i] - xy[i - 2];
            float dy = xy[i + 1] - xy[i - 1];
            if (dx * dx + dy * dy > t2) count++;
        }
        return count;
    }

    private static void softLimit(float[] a) {
        if (a == null) return;
        float max = 0f;
        for (float v : a) max = Math.max(max, Math.abs(v));
        if (max > 0.985f) {
            float sc = 0.985f / max;
            for (int i = 0; i < a.length; i++) a[i] *= sc;
        }
    }

    private static Gradient gradients(float[] a, int w, int h) {
        float[] gx = new float[a.length];
        float[] gy = new float[a.length];
        float[] mag = new float[a.length];
        float max = 1e-7f;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float dx = -a[i - w - 1] + a[i - w + 1] - 2f * a[i - 1] + 2f * a[i + 1]
                        - a[i + w - 1] + a[i + w + 1];
                float dy = -a[i - w - 1] - 2f * a[i - w] - a[i - w + 1]
                        + a[i + w - 1] + 2f * a[i + w] + a[i + w + 1];
                float m = (float) Math.sqrt(dx * dx + dy * dy);
                gx[i] = dx;
                gy[i] = dy;
                mag[i] = m;
                if (m > max) max = m;
            }
        }
        float inv = 1f / max;
        for (int i = 0; i < mag.length; i++) {
            gx[i] *= inv;
            gy[i] *= inv;
            mag[i] = clamp01(mag[i] * inv);
        }
        return new Gradient(gx, gy, mag);
    }

    private static float[] laplacianDetail(float[] a, int w, int h) {
        float[] d = new float[a.length];
        float max = 1e-7f;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float v = Math.abs(a[i] * 4f - a[i - 1] - a[i + 1] - a[i - w] - a[i + w]);
                d[i] = v;
                if (v > max) max = v;
            }
        }
        float inv = 1f / max;
        for (int i = 0; i < d.length; i++) d[i] = clamp01(d[i] * inv);
        return d;
    }

    private static float[] blur5(float[] a, int w, int h) {
        float[] tmp = new float[a.length];
        float[] out = new float[a.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                float sum = 0f, weight = 0f;
                for (int k = -2; k <= 2; k++) {
                    int xx = clamp(x + k, 0, w - 1);
                    float ww = k == 0 ? 6f : (Math.abs(k) == 1 ? 4f : 1f);
                    sum += a[row + xx] * ww;
                    weight += ww;
                }
                tmp[row + x] = sum / weight;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0f, weight = 0f;
                for (int k = -2; k <= 2; k++) {
                    int yy = clamp(y + k, 0, h - 1);
                    float ww = k == 0 ? 6f : (Math.abs(k) == 1 ? 4f : 1f);
                    sum += tmp[yy * w + x] * ww;
                    weight += ww;
                }
                out[y * w + x] = sum / weight;
            }
        }
        return out;
    }

    private static void normalizeEnergy(float[] a) {
        float max = max(a);
        if (max <= 1e-8f) return;
        float inv = 1f / max;
        for (int i = 0; i < a.length; i++) a[i] = clamp01(a[i] * inv);
    }

    private static float max(float[] a) {
        float m = 0f;
        for (float v : a) if (v > m) m = v;
        return m;
    }

    private static float dist2(Point a, Point b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return dx * dx + dy * dy;
    }

    private static int lowerBound(double[] a, double v) {
        int lo = 0, hi = a.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < v) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static double halton(int index, int base) {
        double f = 1.0, r = 0.0;
        int i = Math.max(1, index);
        while (i > 0) {
            f /= base;
            r += f * (i % base);
            i /= base;
        }
        return r;
    }

    private static double fract(double x) {
        return x - Math.floor(x);
    }

    private static int nextPow2(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    private static int hilbertIndex(int x, int y, int bits) {
        int index = 0;
        int n = 1 << bits;
        int xx = x, yy = y;
        for (int s = n >> 1; s > 0; s >>= 1) {
            int rx = (xx & s) > 0 ? 1 : 0;
            int ry = (yy & s) > 0 ? 1 : 0;
            index += s * s * ((3 * rx) ^ ry);
            if (ry == 0) {
                if (rx == 1) {
                    xx = n - 1 - xx;
                    yy = n - 1 - yy;
                }
                int t = xx;
                xx = yy;
                yy = t;
            }
        }
        return index;
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
