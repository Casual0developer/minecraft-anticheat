package dev.anticheat.utils;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

/**
 * Momentum — tracks the horizontal speed a player legitimately carries between
 * ticks, so speed checks allow speed that is bleeding off from a legit burst
 * (elytra landing, ice, knockback, launches) instead of flagging it.
 *
 * Vanilla, per tick:  move = carried + input accel (+0.2 on a sprint-jump),
 * then carried = move × friction, where friction is 0.546 on normal ground,
 * 0.91 in the air, and 1.0 while gliding (elytra applies its own drag before
 * moving).
 *
 * Only trusted ticks (exempt states such as gliding, or grace periods) may
 * raise the carried value to the player's actual speed.  On checked ticks it
 * can only follow the physics prediction or the player's actual speed,
 * whichever is lower, so a cheat can't pump it up — it always decays.
 *
 * One instance per player per check.  Main thread only.
 */
public final class Momentum {

    private static final double SPRINT_JUMP_BOOST = 0.2;
    /** Player air acceleration while sprinting (flying speed 0.026). */
    private static final double AIR_ACCEL = 0.026;
    private static final double SPRINT    = 1.3;

    private double carried = 0;

    /**
     * Returns the most horizontal movement (blocks) physics allows this tick
     * from carried momentum — POSITIVE_INFINITY for trusted ticks — then
     * advances the model with the player's actual movement.
     */
    public double step(Player player, Location from, Location to, double moved, boolean trusted) {
        boolean gliding = player.isGliding();
        boolean startGrounded = !gliding && GroundUtil.touchingGround(player, from);

        double predicted = Double.POSITIVE_INFINITY;
        if (!trusted) {
            double accel = startGrounded ? groundAccel(player) : AIR_ACCEL;
            boolean jumped = startGrounded && to.getY() - from.getY() > 0.2
                    && !GroundUtil.touchingGround(player, to);
            predicted = carried + accel + (jumped ? SPRINT_JUMP_BOOST : 0);
        }

        double kept = Math.min(moved, predicted);
        // The server clears the glide flag on its own tick AFTER the landing
        // packet, so the landing tick (still elytra physics) reads as gliding.
        double friction = gliding ? 1.0
                : startGrounded ? blockFriction(from) * 0.91
                : 0.91;
        carried = kept * friction;
        return predicted;
    }

    /** Forget carried momentum (e.g. after a lag gap of unknown length). */
    public void reset() {
        carried = 0;
    }

    /** Max ground acceleration: movement-speed attribute (includes potions/sprint) assuming sprint. */
    private static double groundAccel(Player player) {
        double speed = 0.1;
        try {
            AttributeInstance inst = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
            if (inst != null) speed = inst.getValue();
        } catch (Throwable ignored) {}
        // The sprint modifier is already in the attribute while the server sees
        // the player sprinting; otherwise assume they are (upper bound).
        return player.isSprinting() ? speed : speed * SPRINT;
    }

    /** Slipperiness of the block the player stood on at the start of the tick. */
    private static double blockFriction(Location from) {
        Material m = from.clone().subtract(0, 0.5000001, 0).getBlock().getType();
        return switch (m) {
            case ICE, PACKED_ICE, FROSTED_ICE -> 0.98;
            case BLUE_ICE -> 0.989;
            case SLIME_BLOCK -> 0.8;
            default -> 0.6;
        };
    }
}
