package com.basefinder;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Software-rendered rotating Minecraft-style block for the GUI. Every texel is drawn as a projected
 * quad with GuiGraphics.fill, so it needs no textures, no shaders and no matrix stack.
 * Styles: 0 grass, 1 dirt, 2 stone, 3 gold ore.
 */
public final class CubeRenderer {
    private CubeRenderer() {}

    // origin, U axis, V axis, normal  (top + 4 sides; the bottom is never visible with a downward tilt)
    private static final float[][][] FACES = {
            {{-1, 1, -1}, {2, 0, 0}, {0, 0, 2}, {0, 1, 0}},
            {{-1, 1, 1}, {2, 0, 0}, {0, -2, 0}, {0, 0, 1}},
            {{1, 1, -1}, {-2, 0, 0}, {0, -2, 0}, {0, 0, -1}},
            {{1, 1, 1}, {0, 0, -2}, {0, -2, 0}, {1, 0, 0}},
            {{-1, 1, -1}, {0, 0, 2}, {0, -2, 0}, {-1, 0, 0}}
    };

    public static void draw(GuiGraphics g, int cx, int cy, float size, double angleDeg, int n, int style) {
        double a = Math.toRadians(angleDeg), t = Math.toRadians(28);
        double ca = Math.cos(a), sa = Math.sin(a), ct = Math.cos(t), st = Math.sin(t);

        for (int f = 0; f < FACES.length; f++) {
            float[][] face = FACES[f];
            // rotate the normal; skip faces that look away from the viewer
            double nx = face[3][0], ny = face[3][1], nz = face[3][2];
            double nx1 = nx * ca + nz * sa;
            double nz1 = -nx * sa + nz * ca;
            double ny2 = ny * ct - nz1 * st;
            double nz2 = ny * st + nz1 * ct;
            if (nz2 < 0.05) continue;

            double light = nx1 * -0.35 + ny2 * 0.75 + nz2 * 0.55;
            float shade = (float) (0.42 + 0.58 * Math.max(0, Math.min(1, light)));

            // projected lattice of the face
            float[] sx = new float[(n + 1) * (n + 1)];
            float[] sy = new float[(n + 1) * (n + 1)];
            for (int i = 0; i <= n; i++) {
                for (int j = 0; j <= n; j++) {
                    double u = i / (double) n, v = j / (double) n;
                    double x = face[0][0] + face[1][0] * u + face[2][0] * v;
                    double y = face[0][1] + face[1][1] * u + face[2][1] * v;
                    double z = face[0][2] + face[1][2] * u + face[2][2] * v;
                    double x1 = x * ca + z * sa;
                    double z1 = -x * sa + z * ca;
                    double y2 = y * ct - z1 * st;
                    sx[i * (n + 1) + j] = (float) (cx + x1 * size);
                    sy[i * (n + 1) + j] = (float) (cy - y2 * size);
                }
            }

            float[] qx = new float[4];
            float[] qy = new float[4];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    int a0 = i * (n + 1) + j, a1 = (i + 1) * (n + 1) + j,
                            a2 = (i + 1) * (n + 1) + j + 1, a3 = i * (n + 1) + j + 1;
                    qx[0] = sx[a0]; qy[0] = sy[a0];
                    qx[1] = sx[a1]; qy[1] = sy[a1];
                    qx[2] = sx[a2]; qy[2] = sy[a2];
                    qx[3] = sx[a3]; qy[3] = sy[a3];
                    quad(g, qx, qy, texel(style, f, i * 8 / n, j * 8 / n, shade));
                }
            }
        }
    }

    /** Fills a convex quad with 1px scanlines. */
    private static void quad(GuiGraphics g, float[] x, float[] y, int color) {
        float minY = Math.min(Math.min(y[0], y[1]), Math.min(y[2], y[3]));
        float maxY = Math.max(Math.max(y[0], y[1]), Math.max(y[2], y[3]));
        int y0 = (int) Math.ceil(minY - 0.5f), y1 = (int) Math.ceil(maxY - 0.5f);
        for (int row = y0; row < y1; row++) {
            float yc = row + 0.5f;
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int e = 0; e < 4; e++) {
                int m = (e + 1) & 3;
                float ya = y[e], yb = y[m];
                if ((ya <= yc && yb > yc) || (yb <= yc && ya > yc)) {
                    float tt = (yc - ya) / (yb - ya);
                    float xi = x[e] + tt * (x[m] - x[e]);
                    lo = Math.min(lo, xi);
                    hi = Math.max(hi, xi);
                }
            }
            if (hi > lo) {
                int xa = Math.round(lo), xb = Math.round(hi);
                if (xb > xa) g.fill(xa, row, xb, row + 1, color);
            }
        }
    }

    private static float noise(int face, int i, int j, int style) {
        int h = face * 7919 + i * 131 + j * 337 + style * 17 + 12345;
        h *= 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return (h & 0xFF) / 255f;
    }

    private static int mix(int rgb, float noise, float shade) {
        float k = (noise - 0.5f) * 0.28f + 1f;
        int r = Math.min(255, (int) (((rgb >> 16) & 255) * k * shade));
        int gg = Math.min(255, (int) (((rgb >> 8) & 255) * k * shade));
        int b = Math.min(255, (int) ((rgb & 255) * k * shade));
        return 0xFF000000 | (r << 16) | (gg << 8) | b;
    }

    private static int texel(int style, int face, int i, int j, float shade) {
        float nz = noise(face, i, j, style);
        boolean top = face == 0;
        switch (style) {
            case 1: // dirt
                return mix(top ? 0x8A5E38 : 0x7A5230, nz, shade);
            case 2: // stone
                return mix(0x808080, nz, shade);
            case 3: // gold ore
                return nz > 0.82f ? mix(0xF0CF4A, 0.5f, shade) : mix(0x808080, nz, shade);
            default: // grass
                if (top) return mix(0x57A832, nz, shade);
                if (j == 0) return mix(0x57A832, nz, shade);
                if (j == 1 && nz > 0.45f) return mix(0x57A832, nz, shade);
                return mix(0x7A5230, nz, shade);
        }
    }
}
