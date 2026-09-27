package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.utils.GroundUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class PlayerStateListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final ViolationManager vm;

    public PlayerStateListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.vm = plugin.getViolationManager();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        // Enforce UUID ban — covers players who changed their username
        String uuidKick = plugin.getBanManager().getUUIDBanKickMessage(id);
        if (uuidKick != null) {
            player.kickPlayer(uuidKick);
            return;
        }

        vm.giveJoinGrace(id);
        vm.setPrevY(id, player.getLocation().getY());
        vm.setWasOnGround(id, true);
        vm.setLastSafeLocation(id, player.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cleanupPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        // Also clean up on kick (e.g. when banPlayer kicks the player)
        cleanupPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        vm.resetFlyVL(id);
        vm.resetAirTicks(id);
        vm.giveTeleportGrace(id);
        vm.setWasOnGround(id, true);
        cleanupListenerState(id);
    }

    // ignoreCancelled on every grace grant below: a cancelled teleport / hit /
    // mount never moved the player, so it must not pause checks.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        vm.giveTeleportGrace(id);
        vm.resetAirTicks(id);
        vm.setWasOnGround(id, true);
        if (event.getTo() != null) {
            vm.setPrevY(id, event.getTo().getY());
            double teleportY = event.getTo().getY();
            if (teleportY > 100) {
                int extraTicks = (int) ((teleportY - 100) / 5.0) + 40;
                if (extraTicks > 120) extraTicks = 120;
                vm.giveTeleportGraceTicks(id, extraTicks);
            }
        }
        cleanupListenerState(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        vm.giveDamageGrace(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        // Two-tier grace based on how long the velocity keeps the player
        // above the normal fly-check thresholds.
        //
        // LARGE launch (wind charge, explosion, cannon, plugin launches):
        //   Y > 0.6 or total length > 1.0.
        //
        //   Wind charges thrown at an angle have a moderate Y component
        //   (0.5–0.8 b/t) but high XZ, putting their total vector length in
        //   the 1.0–2.0 range.  The old thresholds (Y > 0.8 || length > 1.5)
        //   missed angled wind charges, sending them into the weaker
        //   damageGrace (10 ticks) branch — not long enough for the velocity
        //   to bleed off before checks resumed.  Lowering both thresholds
        //   catches all wind-charge vectors while still leaving regular
        //   arrow/mob knockback (typical length 0.4–0.9) in the medium tier.
        //
        //   teleportGrace (20 ticks) keeps checks quiet until the velocity
        //   has bled off to safe levels.
        //
        // REGULAR knockback (arrows, mob hits, minor explosions):
        //   Smaller vectors — damageGrace (10 ticks) is sufficient.
        var vel = event.getVelocity();
        UUID id  = event.getPlayer().getUniqueId();
        // Scaled grace: huge velocity from 255 lunge / 255 wind charges / boat launchers gets proportionally longer grace
        double velLen = vel.length();
        int scaledGrace = (int)Math.min(200, 22 + velLen * 18 + Math.max(0, vel.getY()) * 10);
        // Relaxed for velocity false flags: lowered thresholds to give more grace for moderate knockback
        if (vel.getY() > 0.42 || velLen > 0.85) {
            // Large vertical launch: wind charge, mace smash bounce, explosion,
            // cannon, or any plugin-applied burst.
            //
            // Three things are required here, not just grace:
            //
            //  1. teleportGrace (20 ticks) — suppresses fly/speed checks while
            //     the velocity bleeds off.
            //
            //  2. resetAirTicks — the mace smash hits after a long fall, so
            //     airTicks can be 40+ before the bounce even fires.  Without a
            //     reset, UpPostApex (threshold 25) trips on the very first tick
            //     after grace ends.  Resetting to 0 means airTicks = 20 when
            //     grace expires, giving 5 clean ticks before UpPostApex could
            //     ever fire — by which point the player is already descending.
            //
            //  3. cleanupListenerState — wipes prevDeltaY, gravityStreak,
            //     upTicks, and speed tracking so the checks start fresh from
            //     the bounce, not from stale mid-fall values.
            if (scaledGrace > 20) vm.giveTeleportGraceTicks(id, scaledGrace);
            else vm.giveTeleportGrace(id);
            vm.resetAirTicks(id);
            cleanupListenerState(id);
        } else if (vel.getY() > 0.20
                || Math.abs(vel.getX()) > 0.35
                || Math.abs(vel.getZ()) > 0.35
                || vel.length() > 0.50) {
            // Regular knockback: arrows, mob hits, minor explosions.
            vm.giveDamageGrace(id);
        }
    }

    /**
     * Wind charge grace — projectile-hit approach.
     *
     * PlayerVelocityEvent is NOT reliably fired for wind charge explosions in
     * 1.21 because the velocity burst is applied at the NMS physics level,
     * bypassing Player.setVelocity().  The velocity handler below is a
     * fallback only; this is the primary detection path.
     *
     * When a wind charge (player-thrown or breeze-launched) hits something we
     * immediately give teleport-grace + reset motion state to every player the
     * burst can actually knock back.  Grace fires BEFORE the velocity lands on
     * the player, so fly/speed checks are already suppressed when the burst hits.
     *
     * Knockback reach is 2 × the explosion radius: 2.4 b for a player wind
     * charge (radius 1.2), 6 b for a breeze charge (radius 3).  We add ~0.5 b
     * for lag.  Previously every player within a 6 b box got grace, so throwing
     * a charge at a nearby wall paused checks without launching anyone.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWindChargeHit(ProjectileHitEvent event) {
        EntityType type = event.getEntity().getType();
        if (type != EntityType.WIND_CHARGE && type != EntityType.BREEZE_WIND_CHARGE) return;

        Location loc = event.getEntity().getLocation();
        World world = loc.getWorld();
        if (world == null) return;

        double radius = type == EntityType.WIND_CHARGE ? 3.0 : 6.5;

        // Scale grace with nearby wind charge count to handle 255 spam
        int nearbyCharges = 0;
        for (Entity e : world.getNearbyEntities(loc, 6, 6, 6)) {
            if (e.getType() == EntityType.WIND_CHARGE || e.getType() == EntityType.BREEZE_WIND_CHARGE) nearbyCharges++;
        }
        int baseGrace = 20;
        if (nearbyCharges > 8) baseGrace = 20 + (nearbyCharges * 2);
        if (baseGrace > 200) baseGrace = 200;

        for (Entity nearby : world.getNearbyEntities(loc, radius, radius, radius)) {
            if (!(nearby instanceof Player player)) continue;
            if (player.getLocation().distanceSquared(loc) > radius * radius) continue;
            UUID id = player.getUniqueId();
            if (baseGrace > 20) vm.giveTeleportGraceTicks(id, baseGrace);
            else vm.giveTeleportGrace(id);
            vm.resetAirTicks(id);
            cleanupListenerState(id);
            // Wind charge grants fall-damage immunity on landing — skip NoFall check
            plugin.getNoFallListener().markSkipNextLanding(id);
        }
    }

    /**
     * Mace + Wind Burst grace — attacker-side.
     *
     * The Wind Burst enchantment launches the ATTACKER upward on a successful
     * smash hit.  Like wind charge explosions, the velocity is applied at the
     * NMS physics level and does NOT fire PlayerVelocityEvent, so the velocity
     * handler below never catches it.
     *
     * We listen to EntityDamageByEntityEvent: when the damager is a player
     * holding a mace with Wind Burst, give them the full large-launch treatment
     * (teleportGrace + airTicks reset + state wipe) before their next move
     * event arrives.  Wind Burst only activates on a smash (attacker airborne),
     * but checking !isOnGround() would add complexity for zero benefit — giving
     * grace on a ground-level mace hit is harmless.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMaceWindBurst(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

        ItemStack held = attacker.getInventory().getItemInMainHand();
        if (held.getType() != Material.MACE) return;
        if (!held.containsEnchantment(Enchantment.WIND_BURST)) return;

        UUID id = attacker.getUniqueId();

        // Calculate how many ticks the player will legitimately be ascending.
        //
        // Wind Burst launches the attacker at approximately 0.9 * level b/t.
        // Using vanilla drag (0.98) and gravity (0.08), the apex tick is:
        //   n = ln(GRAVITY / (v0 * (1 - DRAG) + GRAVITY)) / ln(DRAG)
        //     = ln(0.08 / (v0 * 0.02 + 0.08)) / ln(0.98)
        //
        // Level 1 → v0≈0.9  → apex ~10 t → grace 18
        // Level 2 → v0≈1.8  → apex ~18 t → grace 26
        // Level 3 → v0≈2.7  → apex ~25 t → grace 33
        // Level 10 → v0≈9.0 → apex ~54 t → grace 62
        //
        // We add 8 ticks of buffer above the apex so the player is clearly
        // descending before checks resume.
        int level   = held.getEnchantmentLevel(Enchantment.WIND_BURST);
        double v0   = 0.9 * level;
        int apexTick = (int) Math.ceil(
                Math.log(0.08 / (v0 * 0.02 + 0.08)) / Math.log(0.98));
        int graceTicks = apexTick + 8;

        vm.giveTeleportGraceTicks(id, graceTicks);
        vm.resetAirTicks(id);
        cleanupListenerState(id);
        // Wind Burst also grants fall-damage immunity on the launch landing
        plugin.getNoFallListener().markSkipNextLanding(id);
    }

    /**
     * Spear / Trident Lunge grace — accounts for Lunge 1-255.
     *
     * Lunge launches the attacker forward on hit. At 255 the velocity is enormous
     * (hundreds of blocks). We scale grace ticks with level: 30 + level*6 capped at 200
     * plus scaled by velocity to cover WAY further than possible vanilla travel.
     * Also accounts for wind charge spam (255 wind charges) and similar stacked boosts.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpearLunge(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        ItemStack held = attacker.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            held = attacker.getInventory().getItemInOffHand();
            if (held == null || held.getType().isAir()) return;
        }
        String typeName = held.getType().name();
        // Only spears/tridents/maces can have lunge — skip swords etc.
        if (!typeName.contains("SPEAR") && !typeName.contains("TRIDENT") && held.getType() != Material.MACE) {
            // Still check for custom lunge items via enchant presence (allow any item with lunge)
            if (getLungeLevel(held) == 0) return;
        }
        int lungeLevel = getLungeLevel(held);
        if (lungeLevel <= 0) return;

        UUID id = attacker.getUniqueId();
        // Scaled grace: base 30 + 6 per level, cap 200 ticks (~10s) to cover 255 lunge + windcharges
        int graceTicks = 30 + (lungeLevel * 6);
        if (graceTicks > 200) graceTicks = 200;
        // Add extra for extreme levels (255 needs to travel very far)
        if (lungeLevel >= 50) graceTicks = 200;
        if (lungeLevel >= 100) graceTicks = 200; // keep capped but ensure max

        // Also scale via wind charge count in inventory (255 wind charges)
        int windCharges = countWindCharges(attacker);
        if (windCharges > 16) {
            graceTicks = Math.min(200, graceTicks + (windCharges / 4));
        }

        vm.giveTeleportGraceTicks(id, graceTicks);
        vm.resetAirTicks(id);
        cleanupListenerState(id);
        plugin.getNoFallListener().markSkipNextLanding(id);
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][Lunge] " + attacker.getName() + " lunge x" + lungeLevel + " grace=" + graceTicks + " windCharges=" + windCharges);
        }
    }

    private int getLungeLevel(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0;
        // Try vanilla registry lookup
        try {
            Enchantment lunge = Enchantment.getByKey(NamespacedKey.minecraft("lunge"));
            if (lunge != null) {
                int lvl = item.getEnchantmentLevel(lunge);
                if (lvl > 0) return lvl;
            }
        } catch (Throwable ignored) {}
        try {
            Enchantment byName = Enchantment.getByName("LUNGE");
            if (byName != null) {
                int lvl = item.getEnchantmentLevel(byName);
                if (lvl > 0) return lvl;
            }
        } catch (Throwable ignored) {}
        // Fallback: scan all enchantments for key containing "lunge"
        try {
            for (var e : item.getEnchantments().entrySet()) {
                String key = e.getKey().getKey().toString().toLowerCase();
                if (key.contains("lunge")) {
                    return e.getValue();
                }
            }
        } catch (Throwable ignored) {}
        // Last resort: check lore/display for custom lunge
        try {
            if (item.hasItemMeta() && item.getItemMeta().hasLore()) {
                for (String line : item.getItemMeta().getLore()) {
                    if (line.toLowerCase().contains("lunge")) {
                        // Try parse number after lunge
                        String num = line.replaceAll("[^0-9]", " ").trim();
                        if (!num.isEmpty()) {
                            String[] parts = num.split("\\s+");
                            for (String p : parts) {
                                try { return Integer.parseInt(p); } catch (NumberFormatException ignored2) {}
                            }
                        }
                        return 1;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private int countWindCharges(Player p) {
        int c = 0;
        for (ItemStack is : p.getInventory().getContents()) {
            if (is != null && is.getType() == Material.WIND_CHARGE) c += is.getAmount();
        }
        ItemStack off = p.getInventory().getItemInOffHand();
        if (off != null && off.getType() == Material.WIND_CHARGE) c += off.getAmount();
        return c;
    }

    // No grace on wind-charge right-click: throwing one doesn't move the thrower,
    // and onWindChargeHit covers the actual launch.  The old right-click grace
    // also fired for a wind charge sitting in the off-hand on EVERY right-click
    // (including each block placed), pausing ~20 checks at will.

    /**
     * Boat launcher grace — boat with high velocity (piston/slime/TNT launcher) should not flag BoatFly.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBoatMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Boat boat)) return;
        if (boat.getPassengers().isEmpty()) return;
        // Check boat velocity
        org.bukkit.util.Vector vel = boat.getVelocity();
        double speed = Math.sqrt(vel.getX()*vel.getX() + vel.getZ()*vel.getZ() + vel.getY()*vel.getY());
        if (speed > 1.2) {
            for (Entity e : boat.getPassengers()) {
                if (e instanceof Player p) {
                    UUID id = p.getUniqueId();
                    int grace = (int)Math.min(120, 30 + speed * 22);
                    vm.giveTeleportGraceTicks(id, grace);
                    vm.resetAirTicks(id);
                    // Also reset boat fly state via plugin
                    try { plugin.getBoatFlyListener().resetGrace(id, grace); } catch (Throwable ignored) {}
                    if (plugin.getConfig().getBoolean("debug.enabled", false)) {
                        plugin.getLogger().info("[DEBUG][BoatLauncher] " + p.getName() + " boat speed=" + String.format("%.2f", speed) + " grace=" + grace);
                    }
                }
            }
        }
    }

    /**
     * Riptide-trident grace.
     *
     * A riptide throw launches the player at extreme velocity — both horizontal
     * and vertical — that would immediately trip the fly AND speed checks.
     * We give teleport-grace (20 ticks) so checks stay silent while the burst
     * velocity bleeds off under gravity/drag.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRiptide(PlayerRiptideEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        vm.giveTeleportGrace(id);
        cleanupListenerState(id); // wipe stale motion state before riptide lands
    }

    /**
     * Vehicle enter grace — mounting a horse, boat, minecart, etc.
     *
     * When a player mounts a vehicle their Y jumps immediately to the vehicle's
     * passenger position (e.g. horse-back is ~1.5 blocks above ground).  That
     * single-tick Y spike exceeds the UpVelocity threshold and trips the fly
     * check before isInsideVehicle() is fully settled.  Giving teleport-grace
     * + resetting listener state at mount time prevents any false fly/nofall
     * flags during the mount animation.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!(event.getEntered() instanceof Player player)) return;
        UUID id = player.getUniqueId();
        vm.giveTeleportGrace(id);
        vm.resetAirTicks(id);
        vm.setPrevY(id, player.getLocation().getY());
        vm.setWasOnGround(id, true);
        cleanupListenerState(id);
    }

    /**
     * Vehicle exit grace.
     *
     * While inside a vehicle (boat, minecart, horse, etc.) the player is
     * exempt from all fly checks — their Y is driven by the vehicle, not
     * vanilla jump physics.  On dismount the vehicle's velocity is transferred
     * to the player, so the first few move events after exiting look like
     * abnormal airborne motion.  We wipe all stale per-listener motion state
     * and grant teleport-grace (20 ticks) so checks don't fire until the
     * player's velocity has normalised under gravity.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        if (!(event.getExited() instanceof Player player)) return;
        UUID id = player.getUniqueId();
        vm.giveTeleportGrace(id);
        vm.resetAirTicks(id);
        vm.setPrevY(id, player.getLocation().getY());
        vm.setWasOnGround(id, false); // player may be mid-air after dismount
        cleanupListenerState(id);     // wipes prevDeltaY, upTicks, hoverTicks, etc.
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Safe-location tracker — updated whenever the player is solidly grounded.
    // Used by the rubberband punishment to teleport players back.
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMoveUpdateSafe(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        // Only save when grounded (client claim + server-confirmed) and not in any grace period
        if (!GroundUtil.isOnGround(player, event.getTo())) return;
        if (player.isFlying() || player.isInsideVehicle() || player.isGliding()) return;
        UUID id = player.getUniqueId();
        if (vm.hasJoinGrace(id) || vm.hasTeleportGrace(id)) return;
        vm.setLastSafeLocation(id, event.getTo());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Full cleanup: ViolationManager data + all per-listener HashMaps.
     * Called on quit/kick to prevent memory leaks.
     */
    private void cleanupPlayer(UUID id) {
        vm.remove(id);
        plugin.getFlyListener().removePlayer(id);
        plugin.getReachListener().removePlayer(id);
        plugin.getSpeedListener().removePlayer(id);
        plugin.getAirPlaceListener().removePlayer(id);
        plugin.getInventoryAttackListener().removePlayer(id);
        plugin.getMiscCheatListener().removePlayer(id);
        plugin.getAntiKnockbackListener().removePlayer(id);
        plugin.getNoFallListener().removePlayer(id);
        plugin.getInventoryWalkListener().removePlayer(id);
        plugin.getFastLadderListener().removePlayer(id);
        plugin.getSnowWalkListener().removePlayer(id);
        plugin.getScaffoldListener().removePlayer(id);
        plugin.getClickTpListener().removePlayer(id);
        plugin.getVClipListener().removePlayer(id);
        plugin.getCritListener().removePlayer(id);
        plugin.getImpossibleSprintListener().removePlayer(id);

        plugin.getNoWebListener().removePlayer(id);
        plugin.getBoatFlyListener().removePlayer(id);
        plugin.getJetpackListener().removePlayer(id);
        plugin.getCombatSimulationListener().removePlayer(id);
        plugin.getMaceDamageListener().removePlayer(id);
        plugin.getElytraFlightListener().removePlayer(id);
        plugin.getTriggerBotListener().removePlayer(id);
        plugin.getMoveSimListener().removePlayer(id);
        plugin.getAntiTimerListener().removePlayer(id);

        plugin.getAntiFastBreakListener().removePlayer(id);
        plugin.getAirJumpListener().removePlayer(id);
        plugin.getPhysicsSimListener().removePlayer(id);
        plugin.getLevitationSimListener().removePlayer(id);
        if (plugin.getDiscordWebhookManager() != null) plugin.getDiscordWebhookManager().removePlayer(id);
    }

    /**
     * Cleans only per-listener motion state (not VL/grace data).
     * Safe to call on teleport/respawn without losing VL counters.
     *
     * Listeners that keep VL in the same object as their motion baselines
     * expose resetMotion(), which rebuilds the baselines but carries the VL
     * over.  Checks that don't track movement (combat timing, block breaking,
     * placing, crits, ladders, powder snow) aren't touched at all.  Previously
     * this wiped every check's VL, so a pearl or a boat re-mount between flags
     * meant a cheater's VL never built up.
     */
    private void cleanupListenerState(UUID id) {
        plugin.getFlyListener().resetMotion(id);
        plugin.getReachListener().removePlayer(id);           // flag cooldown only; VL lives in ViolationManager
        plugin.getSpeedListener().removePlayer(id);           // VL lives in ViolationManager
        plugin.getMiscCheatListener().removePlayer(id);       // consecutive-tick streaks only
        plugin.getAntiKnockbackListener().resetMotion(id);
        plugin.getNoFallListener().removePlayer(id);          // peak Y must restart after a teleport
        plugin.getInventoryWalkListener().removePlayer(id);   // reference position must restart
        plugin.getScaffoldListener().removePlayer(id);        // VL lives in ViolationManager
        plugin.getClickTpListener().resetMotion(id);
        plugin.getVClipListener().removePlayer(id);           // bans on first confirmed flag; clears pending
        plugin.getImpossibleSprintListener().resetMotion(id);

        plugin.getNoWebListener().removePlayer(id);           // consecutive-tick streaks only
        plugin.getBoatFlyListener().resetMotion(id);
        plugin.getJetpackListener().removePlayer(id);         // already resets itself on every landing
        plugin.getMaceDamageListener().resetMotion(id);
        plugin.getElytraFlightListener().resetMotion(id);
        plugin.getMoveSimListener().resetMotion(id);
        plugin.getAntiTimerListener().resetMotion(id);

        plugin.getAirJumpListener().resetMotion(id);
        plugin.getPhysicsSimListener().resetMotion(id);
        plugin.getLevitationSimListener().removePlayer(id);   // dormant while movement-simulation is on
    }
}
