package dev.anticheat.utils;

/**
 * McMath — Minecraft's lookup-table sin/cos (net.minecraft.util.Mth), so
 * physics predictions match the client's numbers rather than Math.sin's.
 */
public final class McMath {

    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; i++) {
            SIN[i] = (float) Math.sin(i * Math.PI * 2.0 / 65536.0);
        }
    }

    private McMath() {}

    public static float sin(float rad) {
        return SIN[(int) (rad * 10430.378F) & 65535];
    }

    public static float cos(float rad) {
        return SIN[(int) (rad * 10430.378F + 16384.0F) & 65535];
    }

    /**
     * Entity look vector for a Bukkit yaw/pitch (Entity#calculateViewVector).
     * Returns {x, y, z}.
     */
    public static double[] lookVector(float yaw, float pitch) {
        float pitchRad = pitch * 0.017453292F;
        float yawRad   = -yaw * 0.017453292F;
        float cp = cos(pitchRad);
        return new double[]{ sin(yawRad) * cp, -sin(pitchRad), cos(yawRad) * cp };
    }
}
