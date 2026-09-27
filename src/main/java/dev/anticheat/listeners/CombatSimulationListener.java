package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import dev.anticheat.utils.GroundUtil;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CombatSimulationListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final BanManager bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    private static class CombatState {
        int vl = 0;
        int behindHitVL = 0;
        int aimAssistVL = 0;
        int multiAuraVL = 0;
        int webVL = 0;
        int shieldBreakVL = 0;
        int bowVL = 0;

        float prevYaw = Float.MIN_VALUE;
        float prevPitch = Float.MIN_VALUE;
        float prevDeltaYaw = Float.MIN_VALUE;
        float prevDeltaPitch = Float.MIN_VALUE;

        long lastHitMs = 0;

        UUID lastTargetUUID = null;
        long multiHitWindowStart = 0;
        int multiHitCount = 0;
        UUID[] recentTargets = new UUID[5];
        int recentTargetIdx = 0;

        int smoothTrackTicks = 0;
        int snapAimCount = 0;
        int headLockTicks = 0;
        int jitterTicks = 0;
        int framePerfectHits = 0;
        int directionCorrelatedHits = 0;
        int behindHits = 0;

        Material lastMainHand = null;
        long lastWeaponSwitchMs = 0;
        int axeToWeaponSwitches = 0;
        long shieldBrokenMs = 0;

        float prevBowYaw = Float.MIN_VALUE;
        float prevBowPitch = Float.MIN_VALUE;
        long bowDrawStartMs = 0;
        int bowShots = 0;
    }

    private final Map<UUID, CombatState> state = new ConcurrentHashMap<>();

    private static final int BAN_VL = 2;
    private static final double BEHIND_ANGLE_TOL = 65.0;
    private static final double AIM_SMOOTH_THRESHOLD = 3.0;
    private static final int AIM_SMOOTH_TICKS = 5;
    private static final long MULTI_AURA_WINDOW_MS = 80;
    private static final int MAX_REACTION_TIME_MS = 40;
    private static final int BOW_SMOOTH_TICKS = 3;
    private static final double CRIT_SPEED_BONUS = 1.5;
    private static final int REACH_CHECK_TICKS = 2;

    public CombatSimulationListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm = plugin.getBanManager();
        this.vm = plugin.getViolationManager();
        this.wm = plugin.getWhitelistManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("checks.combat-simulation", true)) return;
        if (dev.anticheat.utils.TPSUtil.isSeverelyLagging()) return;
        // Melee only.  Thorns procs report the Thorns wearer as the damager (and
        // sword sweeps fire extra events), which tripped Behind/MultiAura on
        // players who weren't attacking at all.
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (attacker == victim) return;

        UUID id = attacker.getUniqueId();
        if (isExempt(attacker)) return;

        long now = System.currentTimeMillis();
        CombatState s = state.computeIfAbsent(id, k -> new CombatState());

        Location eye = attacker.getEyeLocation();
        float yaw = eye.getYaw();
        float pitch = eye.getPitch();

        double yawDiff = 0;
        if (s.prevYaw != Float.MIN_VALUE) {
            yawDiff = Math.abs(yaw - s.prevYaw);
            if (yawDiff > 180) yawDiff = 360 - yawDiff;
        }
        double pitchDiff = s.prevPitch != Float.MIN_VALUE ? Math.abs(pitch - s.prevPitch) : 0;

        s.prevYaw = yaw;
        s.prevPitch = pitch;

        checkBehindHit(s, attacker, victim, eye, yaw, pitch, id);
        checkAimAssist(s, yaw, pitch, yawDiff, pitchDiff, attacker, victim, id);
        checkMultiAura(s, attacker, victim, now, id);
        checkWebThroughHit(s, attacker, victim, eye, id);
        checkWeaponSwitch(s, attacker, now, id);
        checkReach(s, attacker, victim, eye, id);
        checkCritSpoof(s, attacker, event, id);
        checkCPS(s, attacker, now, id);
        checkAttackCooldown(s, attacker, event, id);

        s.prevDeltaYaw = (float) yawDiff;
        s.prevDeltaPitch = (float) pitchDiff;
        s.lastHitMs = now;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getBlock().getType() != Material.COBWEB) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (isExempt(player)) return;

        CombatState s = state.computeIfAbsent(id, k -> new CombatState());
        Location webLoc = event.getBlock().getLocation().add(0.5, 0.5, 0.5);

        for (org.bukkit.entity.Entity e : webLoc.getWorld().getNearbyEntities(webLoc, 1.5, 2.0, 1.5)) {
            if (!(e instanceof LivingEntity entity)) continue;
            if (entity == player) continue;

            double dist = entity.getLocation().distance(webLoc);
            if (dist < 1.5) {
                s.webVL++;
                debugLog(player, "Combat[WebPlace] placed web through " + entity.getType().name()
                        + " dist=" + fmt(dist) + " vl=" + s.webVL + "/" + BAN_VL);
                if (s.webVL >= BAN_VL) {
                    flag(player, id, "Combat[WebPlace] placing webs through entities");
                    s.webVL = 0;
                }
                return;
            }
        }
    }

    private void checkBehindHit(CombatState s, Player attacker, LivingEntity victim,
                                Location eye, float yaw, float pitch, UUID id) {
        // During lunge/wind/boat, angle checks are unreliable due to high velocity — add tolerance
        double lungeBonus = getLungeReachBonus(attacker);
        double windBonus = getWindChargeBonus(attacker);
        if ((lungeBonus > 0 || windBonus > 0) && vm.hasTeleportGrace(attacker.getUniqueId())) {
            // Skip behind-hit while lunging — player is launched, not aimbotting
            return;
        }
        double boatBonus = getBoatLauncherBonus(attacker);
        if (boatBonus > 4 && vm.hasTeleportGrace(attacker.getUniqueId())) return;

        double vCenterX = victim.getLocation().getX();
        double vCenterY = victim.getLocation().getY() + victim.getHeight() / 2.0;
        double vCenterZ = victim.getLocation().getZ();

        double dx = vCenterX - eye.getX();
        double dy = vCenterY - eye.getY();
        double dz = vCenterZ - eye.getZ();

        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double targetPitch = Math.toDegrees(Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));

        double yawDelta = Math.abs(targetYaw - yaw);
        if (yawDelta > 180) yawDelta = 360 - yawDelta;
        double pitchDelta = Math.abs(targetPitch - pitch);

        double distance = eye.distance(victim.getLocation().add(0, victim.getHeight() / 2, 0));

        double hitboxAngle = Math.toDegrees(Math.atan2(
                Math.max(victim.getWidth(), victim.getHeight()) / 2.0 + 0.3,
                Math.max(distance, 0.1)));

        AttributeInstance interactionRange = attacker.getAttribute(Attribute.PLAYER_ENTITY_INTERACTION_RANGE);
        double attrBonus = 0;
        if (interactionRange != null && interactionRange.getValue() > 3.0) {
            attrBonus = (interactionRange.getValue() - 3.0) * 2.0;
        }

        double effectiveTolerance = BEHIND_ANGLE_TOL + hitboxAngle + attrBonus;

        Vector lookDir = eye.getDirection();
        double vx = vCenterX - eye.getX();
        double vy = vCenterY - eye.getY();
        double vz = vCenterZ - eye.getZ();
        double vLen = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (vLen > 0) { vx /= vLen; vy /= vLen; vz /= vLen; }
        double dot = lookDir.getX() * vx + lookDir.getY() * vy + lookDir.getZ() * vz;
        double angleFromLook = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));

        if (angleFromLook > effectiveTolerance || yawDelta > effectiveTolerance || pitchDelta > effectiveTolerance) {
            s.behindHits++;
            if (s.behindHits >= 2) {
                s.behindHitVL++;
                s.behindHits = 0;
                debugLog(attacker, "Combat[Behind] angle=" + fmt(angleFromLook) + "/" + fmt(effectiveTolerance)
                        + " yaw=" + fmt(yawDelta) + " pitch=" + fmt(pitchDelta)
                        + " vl=" + s.behindHitVL + "/" + BAN_VL);
                if (s.behindHitVL >= BAN_VL) {
                    flag(attacker, id, "Combat[Behind] hitting entities behind view (angle=" + fmt(angleFromLook) + ")");
                    s.behindHitVL = 0;
                }
            }
        } else {
            s.behindHits = Math.max(0, s.behindHits - 1);
            if (angleFromLook < 30) {
                s.behindHitVL = Math.max(0, s.behindHitVL - 1);
            }
        }
    }

    private void checkAimAssist(CombatState s, float yaw, float pitch,
                                double yawDiff, double pitchDiff, Player attacker,
                                LivingEntity victim, UUID id) {
        if (s.prevDeltaYaw == Float.MIN_VALUE) return;

        double accelYaw = Math.abs(yawDiff - Math.abs(s.prevDeltaYaw));
        double accelPitch = Math.abs(pitchDiff - Math.abs(s.prevDeltaPitch));

        Location eye = attacker.getEyeLocation();
        double headY = victim.getLocation().getY() + victim.getHeight() * 0.85;
        double dx = victim.getLocation().getX() - eye.getX();
        double dyHead = headY - eye.getY();
        double dyBody = victim.getLocation().getY() + victim.getHeight() / 2.0 - eye.getY();
        double dz = victim.getLocation().getZ() - eye.getZ();
        double dist = Math.sqrt(dx * dx + dyBody * dyBody + dz * dz);

        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double targetPitchHead = Math.toDegrees(Math.atan2(-dyHead, Math.sqrt(dx * dx + dz * dz)));
        double targetPitchBody = Math.toDegrees(Math.atan2(-dyBody, Math.sqrt(dx * dx + dz * dz)));

        double yawToTarget = Math.abs(targetYaw - yaw);
        if (yawToTarget > 180) yawToTarget = 360 - yawToTarget;
        double pitchToHead = Math.abs(targetPitchHead - pitch);
        double pitchToBody = Math.abs(targetPitchBody - pitch);

        double prevYawToTarget = s.prevYaw != Float.MIN_VALUE ? Math.abs(targetYaw - s.prevYaw) : 999;
        if (prevYawToTarget > 180) prevYawToTarget = 360 - prevYawToTarget;
        boolean reducingAngle = yawToTarget < prevYawToTarget && yawDiff > 0.3;

        if (reducingAngle && yawDiff > 0.3 && accelYaw < AIM_SMOOTH_THRESHOLD) {
            s.smoothTrackTicks++;
            if (s.smoothTrackTicks >= AIM_SMOOTH_TICKS) {
                s.aimAssistVL++;
                debugLog(attacker, "Combat[SmoothAim] reducing=" + fmt(prevYawToTarget) + "->" + fmt(yawToTarget)
                        + " ticks=" + s.smoothTrackTicks + " vl=" + s.aimAssistVL + "/" + BAN_VL);
                if (s.aimAssistVL >= BAN_VL) { flag(attacker, id, "Combat[SmoothAim] tracking entity"); s.aimAssistVL = 0; }
                s.smoothTrackTicks = 0;
            }
        } else {
            s.smoothTrackTicks = Math.max(0, s.smoothTrackTicks - 1);
        }

        boolean snapToTarget = (yawDiff > 8 || pitchDiff > 6) && reducingAngle
                && yawToTarget < 20 && (accelYaw > 5 || accelPitch > 4);
        if (snapToTarget) {
            s.snapAimCount++;
            if (s.snapAimCount >= 2) {
                s.aimAssistVL++;
                debugLog(attacker, "Combat[SnapAim] yaw=" + fmt(yawDiff) + " pitch=" + fmt(pitchDiff)
                        + " toTarget=" + fmt(yawToTarget) + " vl=" + s.aimAssistVL + "/" + BAN_VL);
                if (s.aimAssistVL >= BAN_VL) { flag(attacker, id, "Combat[SnapAim] snap to entity"); s.aimAssistVL = 0; }
                s.snapAimCount = 0;
            }
        } else {
            s.snapAimCount = Math.max(0, s.snapAimCount - 1);
        }

        boolean headLocked = yawToTarget < 8 && pitchToHead < 8;
        if (headLocked) {
            s.headLockTicks++;
            if (s.headLockTicks >= 6) {
                s.aimAssistVL++;
                debugLog(attacker, "Combat[HeadLock] ticks=" + s.headLockTicks
                        + " yaw=" + fmt(yawToTarget) + " pitch=" + fmt(pitchToHead)
                        + " vl=" + s.aimAssistVL + "/" + BAN_VL);
                if (s.aimAssistVL >= BAN_VL) { flag(attacker, id, "Combat[HeadLock] head locked"); s.aimAssistVL = 0; }
                s.headLockTicks = 0;
            }
        } else {
            s.headLockTicks = Math.max(0, s.headLockTicks - 2);
        }

        if (yawDiff > 2 && pitchDiff > 1 && reducingAngle) {
            s.framePerfectHits++;
            if (s.framePerfectHits >= 4) {
                s.aimAssistVL++;
                debugLog(attacker, "Combat[FramePerfect] hits=" + s.framePerfectHits
                        + " yawDiff=" + fmt(yawDiff) + " vl=" + s.aimAssistVL + "/" + BAN_VL);
                if (s.aimAssistVL >= BAN_VL) { flag(attacker, id, "Combat[FramePerfect] frame-perfect aim"); s.aimAssistVL = 0; }
                s.framePerfectHits = 0;
            }
        } else {
            s.framePerfectHits = Math.max(0, s.framePerfectHits - 1);
        }

        boolean directionMatch = Math.signum(yawDiff) == Math.signum(targetYaw - yaw)
                || Math.abs(targetYaw - yaw) < 5;
        if (directionMatch && yawDiff > 1 && yawToTarget < 30) {
            s.directionCorrelatedHits++;
            if (s.directionCorrelatedHits >= 8) {
                s.aimAssistVL++;
                debugLog(attacker, "Combat[DirectionCorr] hits=" + s.directionCorrelatedHits
                        + " vl=" + s.aimAssistVL + "/" + BAN_VL);
                if (s.aimAssistVL >= BAN_VL) { flag(attacker, id, "Combat[DirectionCorr] correlated aim"); s.aimAssistVL = 0; }
                s.directionCorrelatedHits = 0;
            }
        } else {
            s.directionCorrelatedHits = Math.max(0, s.directionCorrelatedHits - 2);
        }
    }

    private boolean hasSweepingEdge(Player player) {
        var item = player.getInventory().getItemInMainHand();
        return item.getType().name().endsWith("_SWORD") && item.getEnchantmentLevel(Enchantment.SWEEPING_EDGE) > 0;
    }

    private void checkMultiAura(CombatState s, Player attacker, LivingEntity victim, long now, UUID id) {
        if (s.multiHitWindowStart == 0 || now - s.multiHitWindowStart > MULTI_AURA_WINDOW_MS) {
            s.multiHitWindowStart = now;
            s.multiHitCount = 1;
            s.recentTargets[0] = victim.getUniqueId();
            s.recentTargetIdx = 1;
        } else {
            s.multiHitCount++;

            boolean newTarget = true;
            for (int i = 0; i < s.recentTargetIdx && i < s.recentTargets.length; i++) {
                if (victim.getUniqueId().equals(s.recentTargets[i])) {
                    newTarget = false;
                    break;
                }
            }

            if (newTarget && s.recentTargetIdx < s.recentTargets.length) {
                s.recentTargets[s.recentTargetIdx++] = victim.getUniqueId();
            }

            if (s.multiHitCount >= 3 && s.recentTargetIdx >= 2 && !hasSweepingEdge(attacker)) {
                s.multiAuraVL++;
                debugLog(attacker, "Combat[MultiAura] hits=" + s.multiHitCount
                        + " targets=" + s.recentTargetIdx + " in " + (now - s.multiHitWindowStart) + "ms"
                        + " vl=" + s.multiAuraVL + "/" + BAN_VL);
                if (s.multiAuraVL >= BAN_VL) {
                    flag(attacker, id, "Combat[MultiAura] hitting multiple entities");
                    s.multiAuraVL = 0;
                }
                s.multiHitWindowStart = 0;
                s.multiHitCount = 0;
                s.recentTargetIdx = 0;
            }
        }

        if (s.lastTargetUUID != null && !s.lastTargetUUID.equals(victim.getUniqueId())) {
            if (s.lastHitMs > 0 && now - s.lastHitMs < MAX_REACTION_TIME_MS) {
                s.multiAuraVL++;
                debugLog(attacker, "Combat[MultiAura] instant switch! reaction=" + (now - s.lastHitMs) + "ms"
                        + " vl=" + s.multiAuraVL + "/" + BAN_VL);
                if (s.multiAuraVL >= BAN_VL) {
                    flag(attacker, id, "Combat[MultiAura] instant target switching");
                    s.multiAuraVL = 0;
                }
            }
        }
        s.lastTargetUUID = victim.getUniqueId();

        if (s.recentTargetIdx >= 3) {
            s.multiAuraVL++;
            debugLog(attacker, "Combat[MultiAura] 3+ targets tracked! vl=" + s.multiAuraVL + "/" + BAN_VL);
            if (s.multiAuraVL >= BAN_VL) {
                flag(attacker, id, "Combat[MultiAura] 3+ targets in window");
                s.multiAuraVL = 0;
            }
            s.recentTargetIdx = 0;
        }

        if (s.multiHitCount >= 5) {
            double hitsPerSec = s.multiHitCount / ((now - s.multiHitWindowStart) / 1000.0 + 1) * 1000;
            if (hitsPerSec > 15) {
                s.multiAuraVL++;
                debugLog(attacker, "Combat[MultiAura] high frequency! " + fmt(hitsPerSec) + " hits/sec"
                        + " vl=" + s.multiAuraVL + "/" + BAN_VL);
                if (s.multiAuraVL >= BAN_VL) {
                    flag(attacker, id, "Combat[MultiAura] high frequency attacks");
                    s.multiAuraVL = 0;
                }
            }
            s.multiHitCount = 0;
            s.multiHitWindowStart = 0;
        }

        if (s.lastTargetUUID != null && s.prevYaw != Float.MIN_VALUE) {
            Location eye = attacker.getEyeLocation();
            double vYaw = victim.getLocation().getX() - eye.getX();
            double vZ = victim.getLocation().getZ() - eye.getZ();
            double angleToVictim = Math.toDegrees(Math.atan2(-vYaw, vZ));
            double yawDiff = Math.abs(angleToVictim - s.prevYaw);
            if (yawDiff > 180) yawDiff = 360 - yawDiff;

            if (yawDiff > 60 && now - s.lastHitMs < 100) {
                s.multiAuraVL++;
                debugLog(attacker, "Combat[MultiAura] wide angle hit! angle=" + fmt(yawDiff)
                        + " vl=" + s.multiAuraVL + "/" + BAN_VL);
                if (s.multiAuraVL >= BAN_VL) {
                    flag(attacker, id, "Combat[MultiAura] wide angle multi-hit");
                    s.multiAuraVL = 0;
                }
            }
        }
    }

    private void checkWebThroughHit(CombatState s, Player attacker, LivingEntity victim, Location eye, UUID id) {
        Vector direction = eye.getDirection();
        double distance = eye.distance(victim.getLocation().add(0, victim.getHeight() / 2, 0));

        RayTraceResult result = eye.getWorld().rayTraceBlocks(
                eye, direction, distance, FluidCollisionMode.NEVER, true);

        if (result != null && result.getHitBlock() != null
                && result.getHitBlock().getType() == Material.COBWEB) {
            s.webVL++;
            debugLog(attacker, "Combat[WebHit] hitting through cobweb! dist=" + fmt(distance)
                    + " vl=" + s.webVL + "/" + BAN_VL);
            if (s.webVL >= BAN_VL) {
                flag(attacker, id, "Combat[WebHit] attacking through cobwebs");
                s.webVL = 0;
            }
        }
    }

    private void checkWeaponSwitch(CombatState s, Player attacker, long now, UUID id) {
        ItemStack mainHand = attacker.getInventory().getItemInMainHand();
        Material current = mainHand != null ? mainHand.getType() : Material.AIR;

        if (s.lastMainHand != null && s.lastMainHand != current) {
            boolean wasAxe = s.lastMainHand.name().endsWith("_AXE");
            boolean isSword = current.name().endsWith("_SWORD");
            boolean isAxe = current.name().endsWith("_AXE");

            if (wasAxe && !isAxe) {
                s.axeToWeaponSwitches++;
                if (s.axeToWeaponSwitches >= 2 && now - s.lastWeaponSwitchMs < 200) {
                    s.shieldBreakVL++;
                    debugLog(attacker, "Combat[ShieldBreak] axe->weapon switch! "
                            + s.lastMainHand.name() + " -> " + current.name()
                            + " switches=" + s.axeToWeaponSwitches
                            + " vl=" + s.shieldBreakVL + "/" + BAN_VL);
                    if (s.shieldBreakVL >= BAN_VL) {
                        flag(attacker, id, "Combat[ShieldBreak] instant shield break + switch");
                        s.shieldBreakVL = 0;
                    }
                    s.axeToWeaponSwitches = 0;
                }
            } else {
                s.axeToWeaponSwitches = Math.max(0, s.axeToWeaponSwitches - 1);
            }
        }

        if (current.name().endsWith("_AXE") && s.shieldBrokenMs > 0 && now - s.shieldBrokenMs < 500) {
            s.shieldBreakVL++;
            debugLog(attacker, "Combat[ShieldBreak] picked axe after shield break! vl=" + s.shieldBreakVL + "/" + BAN_VL);
            if (s.shieldBreakVL >= BAN_VL) {
                flag(attacker, id, "Combat[ShieldBreak] shield break combo detected");
                s.shieldBreakVL = 0;
            }
        }

        s.lastMainHand = current;
        s.lastWeaponSwitchMs = now;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onShieldDisable(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!victim.isBlocking()) return;

        CombatState s = state.get(victim.getUniqueId());
        if (s != null) {
            s.shieldBrokenMs = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBowAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof org.bukkit.entity.AbstractArrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player shooter)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        UUID id = shooter.getUniqueId();
        if (isExempt(shooter)) return;

        CombatState s = state.computeIfAbsent(id, k -> new CombatState());

        double damage = event.getDamage();

        org.bukkit.inventory.ItemStack bow = shooter.getInventory().getItemInMainHand();
        if (bow.getType() == Material.BOW || bow.getType() == Material.CROSSBOW) {
            int power = bow.getEnchantmentLevel(Enchantment.POWER);
            boolean hasFlame = bow.getEnchantmentLevel(Enchantment.FLAME) > 0;
            boolean isCritical = arrow.isCritical();

            double maxBase = 6.0 + (power * 0.5);
            if (isCritical) maxBase *= 1.5;

            var strength = shooter.getPotionEffect(org.bukkit.potion.PotionEffectType.STRENGTH);
            if (strength != null) {
                maxBase += (strength.getAmplifier() + 1) * 3.0;
            }

            if (maxBase > 500) return;

            if (damage > maxBase + 1.0) {
                s.bowVL++;
                debugLog(shooter, "Combat[BowDamage] damage=" + fmt(damage) + " maxPossible=" + fmt(maxBase)
                        + " power=" + power + " crit=" + isCritical
                        + " vl=" + s.bowVL + "/" + BAN_VL);
                if (s.bowVL >= BAN_VL) {
                    flag(shooter, id, "Combat[BowDamage] modified bow damage (" + fmt(damage) + " > " + fmt(maxBase) + ")");
                    s.bowVL = 0;
                }
            }
        }

        Location eye = shooter.getEyeLocation();
        float yaw = eye.getYaw();
        float pitch = eye.getPitch();

        if (s.prevBowYaw != Float.MIN_VALUE) {
            double yawDiff = Math.abs(yaw - s.prevBowYaw);
            if (yawDiff > 180) yawDiff = 360 - yawDiff;
            double pitchDiff = Math.abs(pitch - s.prevBowPitch);

            double dx = victim.getLocation().getX() - eye.getX();
            double dy = victim.getLocation().getY() + victim.getHeight() * 0.85 - eye.getY();
            double dz = victim.getLocation().getZ() - eye.getZ();
            double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
            double targetPitch = Math.toDegrees(Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));
            double yawToTarget = Math.abs(targetYaw - yaw);
            if (yawToTarget > 180) yawToTarget = 360 - yawToTarget;
            double pitchToTarget = Math.abs(targetPitch - pitch);

            double prevYawToTarget = Math.abs(targetYaw - s.prevBowYaw);
            if (prevYawToTarget > 180) prevYawToTarget = 360 - prevYawToTarget;
            boolean reducing = yawToTarget < prevYawToTarget && yawDiff > 0.3;

            if (reducing && yawDiff > 0.3 && yawToTarget < 25) {
                s.smoothTrackTicks++;
                if (s.smoothTrackTicks >= 3) {
                    s.bowVL++;
                    debugLog(shooter, "Combat[BowAimbot] reducing=" + fmt(prevYawToTarget) + "->" + fmt(yawToTarget)
                            + " ticks=" + s.smoothTrackTicks + " vl=" + s.bowVL + "/" + BAN_VL);
                    if (s.bowVL >= BAN_VL) {
                        flag(shooter, id, "Combat[BowAimbot] bow aimbot detected");
                        s.bowVL = 0;
                    }
                    s.smoothTrackTicks = 0;
                }
            } else {
                s.smoothTrackTicks = Math.max(0, s.smoothTrackTicks - 1);
            }

            boolean snapToTarget = (yawDiff > 5 || pitchDiff > 4) && reducing
                    && yawToTarget < 20 && pitchToTarget < 15;
            if (snapToTarget) {
                s.snapAimCount++;
                if (s.snapAimCount >= 2) {
                    s.bowVL++;
                    debugLog(shooter, "Combat[BowSnap] yaw=" + fmt(yawDiff) + " pitch=" + fmt(pitchDiff)
                            + " toTarget=" + fmt(yawToTarget) + " vl=" + s.bowVL + "/" + BAN_VL);
                    if (s.bowVL >= BAN_VL) {
                        flag(shooter, id, "Combat[BowSnap] bow snap aim");
                        s.bowVL = 0;
                    }
                    s.snapAimCount = 0;
                }
            } else {
                s.snapAimCount = Math.max(0, s.snapAimCount - 1);
            }

            if (yawToTarget < 10 && pitchToTarget < 10) {
                s.headLockTicks++;
                if (s.headLockTicks >= 4) {
                    s.bowVL++;
                    debugLog(shooter, "Combat[BowLock] locked=" + s.headLockTicks
                            + " yaw=" + fmt(yawToTarget) + " vl=" + s.bowVL + "/" + BAN_VL);
                    if (s.bowVL >= BAN_VL) {
                        flag(shooter, id, "Combat[BowLock] bow head lock");
                        s.bowVL = 0;
                    }
                    s.headLockTicks = 0;
                }
            } else {
                s.headLockTicks = Math.max(0, s.headLockTicks - 2);
            }
        }

        s.prevBowYaw = yaw;
        s.prevBowPitch = pitch;
        s.bowShots++;
    }

    private int getLungeLevel(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) held = p.getInventory().getItemInOffHand();
        if (held == null || held.getType().isAir()) return 0;
        String type = held.getType().name();
        if (!type.contains("SPEAR") && !type.contains("TRIDENT") && held.getType() != Material.MACE) {
            // Still allow custom lunge items on any type via enchant scan
        }
        // Try vanilla lunge registry
        try {
            Enchantment lunge = Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft("lunge"));
            if (lunge != null) {
                int lvl = held.getEnchantmentLevel(lunge);
                if (lvl > 0) return lvl;
            }
        } catch (Throwable ignored) {}
        try {
            Enchantment byName = Enchantment.getByName("LUNGE");
            if (byName != null) {
                int lvl = held.getEnchantmentLevel(byName);
                if (lvl > 0) return lvl;
            }
        } catch (Throwable ignored) {}
        try {
            for (var e : held.getEnchantments().entrySet()) {
                String key = e.getKey().getKey().toString().toLowerCase();
                if (key.contains("lunge")) return e.getValue();
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private double getLungeReachBonus(Player p) {
        int lvl = getLungeLevel(p);
        if (lvl <= 0) return 0;
        // Lunge 3 -> +11.5 blocks, Lunge 10 -> +29 blocks, 255 -> +766 blocks (covers WAY further than possible)
        // Formula: base 4 + lvl*2.5 + extra for high levels (255)
        double bonus = 4.0 + (lvl * 2.5);
        if (lvl >= 50) bonus += (lvl - 50) * 1.5; // extra scaling for stacked/255
        if (lvl > 100) bonus = Math.min(1200, bonus);
        return bonus;
    }

    private double getWindChargeBonus(Player p) {
        // If player has recent wind charge velocity (teleport grace from wind charge), allow extra reach
        // Check inventory count for 255 spam
        int count = 0;
        for (ItemStack is : p.getInventory().getContents()) if (is != null && is.getType() == Material.WIND_CHARGE) count += is.getAmount();
        if (count >= 16) return 6 + (count * 0.15); // 255 -> ~44 blocks
        if (vm.hasTeleportGrace(p.getUniqueId())) return 4.0; // recent wind burst
        return 0;
    }

    private double getBoatLauncherBonus(Player p) {
        if (p.isInsideVehicle() && p.getVehicle() instanceof org.bukkit.entity.Boat) {
            org.bukkit.util.Vector v = p.getVehicle().getVelocity();
            double s = Math.sqrt(v.getX()*v.getX()+v.getZ()*v.getZ()+v.getY()*v.getY());
            if (s > 1.0) return 5 + (s * 3); // boat launcher at speed 3 -> +14 blocks
        }
        if (vm.hasTeleportGrace(p.getUniqueId())) {
            // Recent boat launch leaves teleport grace — give generic bonus
            try {
                // Check if last vehicle was boat via grace map? Use teleport grace as proxy
                return 3.0;
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    private void checkReach(CombatState s, Player attacker, LivingEntity victim, Location eye, UUID id) {
        double dist = eye.distance(victim.getLocation().add(0, victim.getHeight() / 2, 0));

        AttributeInstance range = attacker.getAttribute(Attribute.PLAYER_ENTITY_INTERACTION_RANGE);
        double maxReach = range != null ? range.getValue() : 3.0;

        // --- Lunge / Wind charge / Boat launcher modifiers (combat simulation) ---
        double lungeBonus = getLungeReachBonus(attacker);
        double windBonus = getWindChargeBonus(attacker);
        double boatBonus = getBoatLauncherBonus(attacker);
        double totalBonus = lungeBonus + windBonus + boatBonus;
        if (totalBonus > 0) {
            maxReach += totalBonus;
            debugLog(attacker, "Combat[Reach] lungeBonus=" + fmt(lungeBonus) + " wind=" + fmt(windBonus) + " boat=" + fmt(boatBonus) + " newMax=" + fmt(maxReach));
        }

        if (dist > maxReach + 0.5) {
            s.behindHitVL++;
            debugLog(attacker, "Combat[Reach] dist=" + fmt(dist) + " max=" + fmt(maxReach)
                    + " vl=" + s.behindHitVL + "/" + BAN_VL);
            if (s.behindHitVL >= REACH_CHECK_TICKS) {
                flag(attacker, id, "Combat[Reach] distance=" + fmt(dist) + " > max=" + fmt(maxReach));
                s.behindHitVL = 0;
            }
        } else {
            s.behindHitVL = Math.max(0, s.behindHitVL - 1);
        }
    }

    private void checkCritSpoof(CombatState s, Player attacker, EntityDamageByEntityEvent event, UUID id) {
        boolean isCrit = event.getDamage() > event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.BASE) * 1.4;
        boolean wasFalling = attacker.getFallDistance() > 0.0;
        // Client claim OR server-seen ground, so a fake "airborne" claim doesn't excuse the crit.
        boolean onGround = attacker.isOnGround()
                || GroundUtil.touchingGround(attacker, attacker.getLocation());
        boolean inWater = attacker.isInWater();
        boolean blind = attacker.hasPotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS);
        boolean climb = attacker.getLocation().getBlock().getType().name().contains("LADDER")
                || attacker.getLocation().getBlock().getType() == org.bukkit.Material.VINE;

        if (isCrit && (onGround || inWater || blind || climb || !wasFalling)) {
            s.aimAssistVL++;
            debugLog(attacker, "Combat[CritSpoof] crit with invalid conditions! onGround=" + onGround
                    + " inWater=" + inWater + " blind=" + blind + " climb=" + climb
                    + " vl=" + s.aimAssistVL + "/" + BAN_VL);
            if (s.aimAssistVL >= BAN_VL) {
                flag(attacker, id, "Combat[CritSpoof] invalid critical hit");
                s.aimAssistVL = 0;
            }
        }
    }

    private void checkCPS(CombatState s, Player attacker, long now, UUID id) {
        if (s.lastHitMs == 0) return;
        long interval = now - s.lastHitMs;
        double tpsFactor = dev.anticheat.utils.TPSUtil.lagCompensationFactor();
        long cpsThreshold = (long) (90 * tpsFactor); // 90ms = ~11 CPS, scale with lag
        if (interval < cpsThreshold && interval > 10) {
            s.jitterTicks++;
            if (s.jitterTicks >= 6) {
                s.aimAssistVL++;
                debugLog(attacker, "Combat[CPS] interval=" + interval + "ms jitter=" + s.jitterTicks + " vl=" + s.aimAssistVL + "/" + BAN_VL);
                if (s.aimAssistVL >= BAN_VL) { flag(attacker, id, "Combat[CPS] impossible click rate " + interval + "ms"); s.aimAssistVL = 0; }
                s.jitterTicks = 0;
            }
        } else {
            s.jitterTicks = Math.max(0, s.jitterTicks - 1);
        }
        // Attack cooldown check via Bukkit API
        try {
            float cooldown = attacker.getAttackCooldown();
            if (cooldown < 0.85 && interval < 200) {
                s.jitterTicks++;
            }
        } catch (Throwable ignored) {}
    }

    private void checkAttackCooldown(CombatState s, Player attacker, EntityDamageByEntityEvent event, UUID id) {
        try {
            float cd = attacker.getAttackCooldown();
            double dmg = event.getDamage();
            // If attack cooldown not ready (<0.9) but damage is still full (no penalty), indicates NoCooldown cheat
            if (cd < 0.9) {
                double expectedPenalty = cd * dmg;
                if (dmg > expectedPenalty * 1.1 && dmg > 2.0) {
                    s.aimAssistVL++;
                    debugLog(attacker, "Combat[NoCooldown] cd=" + String.format("%.2f", cd) + " dmg=" + fmt(dmg) + " vl=" + s.aimAssistVL + "/" + BAN_VL);
                    if (s.aimAssistVL >= BAN_VL) { flag(attacker, id, "Combat[NoCooldown] attacking without cooldown"); s.aimAssistVL = 0; }
                }
            }
        } catch (Throwable ignored) {}
    }

    private void flag(Player player, UUID id, String reason) {
        CombatState s = state.get(id);
        if (s == null) return;
        s.vl++;
        debugLog(player, reason + " vl=" + s.vl + "/" + BAN_VL);
        if (s.vl >= BAN_VL) {
            state.remove(id);
            bm.banPlayer(player, reason);
        }
    }

    private boolean isExempt(Player player) {
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")
                || wm.isWhitelisted(player.getUniqueId())
                || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR
                || player.isDead() || player.isInsideVehicle()
                || vm.hasJoinGrace(player.getUniqueId());
    }

    public void removePlayer(UUID id) { state.remove(id); }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][CombatSim] " + player.getName() + " » " + msg);
        }
    }

    private String fmt(double d) { return String.format("%.1f", d); }
}
