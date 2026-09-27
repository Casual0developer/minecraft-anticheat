package dev.anticheat.utils;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.util.BoundingBox;

/**
 * GroundUtil — server-side ground checks.
 *
 * Player.isOnGround() is just the flag the client puts in every movement
 * packet, so a cheat client can claim to be on the ground while flying (or
 * claim to be airborne while walking).  Never let that claim alone buy an
 * exemption:
 *
 *   • Where "on ground" skips or resets a check, use {@link #isOnGround} —
 *     the client must claim it AND the server must see something underfoot.
 *   • Where "airborne" loosens a check, use
 *     {@code player.isOnGround() || touchingGround(...)} so a fake
 *     "airborne" claim doesn't count when the server sees ground.
 *
 * Must be called from the main thread.
 */
public final class GroundUtil {

    /**
     * Full player hitbox half-width.  Must be the full 0.3: sneaking lets a
     * player hang over an edge with only a sliver of hitbox left on the block.
     */
    private static final double HALF_WIDTH = 0.3;
    /** How far below the feet a surface can be and still count as ground. */
    private static final double SLOP = 0.03;
    /** How far a surface may poke above the feet (tiny desync) and still count. */
    private static final double TOP_EPS = 0.001;
    /** Extra reach below the feet for standable entities — boats bob and desync slightly. */
    private static final double ENTITY_SLOP = 0.3;

    private GroundUtil() {}

    /** Client claims on-ground AND the server sees ground under the feet. */
    public static boolean isOnGround(Player player, Location loc) {
        return player.isOnGround() && touchingGround(player, loc);
    }

    /**
     * Server-side only: is a block collision box, the top of powder snow, or a
     * standable entity (boat, minecart, shulker, happy ghast) directly under
     * the player's feet?
     */
    public static boolean touchingGround(Player player, Location loc) {
        World w = loc.getWorld();
        if (w == null) return false;
        double x = loc.getX(), y = loc.getY(), z = loc.getZ();
        double minX = x - HALF_WIDTH, maxX = x + HALF_WIDTH;
        double minZ = z - HALF_WIDTH, maxZ = z + HALF_WIDTH;

        int bx0 = (int) Math.floor(minX), bx1 = (int) Math.floor(maxX);
        int bz0 = (int) Math.floor(minZ), bz1 = (int) Math.floor(maxZ);
        // Fences and walls top out 0.5 above their own block, so scan one block lower.
        int by0 = (int) Math.floor(y - SLOP) - 1;
        int by1 = (int) Math.floor(y + TOP_EPS);

        for (int bx = bx0; bx <= bx1; bx++) {
            for (int bz = bz0; bz <= bz1; bz++) {
                for (int by = by1; by >= by0; by--) {
                    Block b = w.getBlockAt(bx, by, bz);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    if (m == Material.POWDER_SNOW) {
                        // Powder snow has no collision outside an entity context; count its top surface.
                        if (isSurfaceUnderFeet(bx, by + 1, bz, bx + 1, bz + 1, minX, maxX, minZ, maxZ, y)) return true;
                        continue;
                    }
                    for (BoundingBox box : b.getCollisionShape().getBoundingBoxes()) {
                        box.shift(bx, by, bz);
                        if (isSurfaceUnderFeet(box.getMinX(), box.getMaxY(), box.getMinZ(), box.getMaxX(), box.getMaxZ(),
                                minX, maxX, minZ, maxZ, y)) return true;
                    }
                }
            }
        }

        BoundingBox below = new BoundingBox(minX, y - SLOP - ENTITY_SLOP, minZ, maxX, y, maxZ);
        return !w.getNearbyEntities(below, GroundUtil::isStandable).isEmpty();
    }

    /**
     * A collision box is ground only if its TOP is at the feet (within
     * SLOP below / TOP_EPS above) and it overlaps the hitbox horizontally by
     * any amount.  Judging by the top surface means a wall beside an airborne
     * player never counts, so the full hitbox width can be used.
     */
    private static boolean isSurfaceUnderFeet(double bMinX, double bTop, double bMinZ, double bMaxX, double bMaxZ,
                                              double minX, double maxX, double minZ, double maxZ, double feetY) {
        if (bTop < feetY - SLOP || bTop > feetY + TOP_EPS) return false;
        return bMinX < maxX && bMaxX > minX && bMinZ < maxZ && bMaxZ > minZ;
    }

    private static boolean isStandable(Entity e) {
        return e instanceof Boat || e instanceof Minecart || e instanceof Shulker
                || e.getType().name().equals("HAPPY_GHAST");
    }
}
