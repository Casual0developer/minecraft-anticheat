package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AntiFastBreakListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final BanManager bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    private static class BreakState {
        int vl = 0;
        int packetVl = 0;
        long breakStartMs = 0;
        Material blockType = null;
        boolean hadDamage = false;
        long lastBreakMs = 0;
        Queue<Long> recentBreaks = new LinkedList<>();
    }

    private final Map<UUID, BreakState> state = new ConcurrentHashMap<>();

    private static final double SPEED_BUFFER = 0.4;
    private static final int BAN_VL = 3;
    private static final int PACKET_BAN_VL = 2;
    private static final long BREAK_WINDOW_MS = 1000L;
    private static final int MAX_BREAKS_PER_SECOND = 6;
    private static final long MIN_BREAK_GAP_MS = 80L;

    public AntiFastBreakListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm = plugin.getBanManager();
        this.vm = plugin.getViolationManager();
        this.wm = plugin.getWhitelistManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.getConfig().getBoolean("checks.fastbreak", true)) return;
        if (dev.anticheat.utils.TPSUtil.isSeverelyLagging()) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Block block = event.getBlock();

        if (isExempt(player)) return;

        Material blockType = block.getType();
        double hardness = blockType.getHardness();
        long now = System.currentTimeMillis();

        checkBreakRate(player, id, now, hardness);

        if (hardness <= 0 || isInstantBreakable(blockType)) {
            BreakState s = state.get(id);
            if (s != null) { s.breakStartMs = 0; s.blockType = null; s.hadDamage = false; }
            return;
        }

        BreakState s = state.computeIfAbsent(id, k -> new BreakState());

        if (s.hadDamage && s.breakStartMs > 0 && s.blockType == blockType) {
            long elapsed = now - s.breakStartMs;
            double expectedMs = calculateExpectedBreakMs(player, blockType, hardness);

            if (elapsed < expectedMs * SPEED_BUFFER) {
                s.vl++;
                debugLog(player, "FastBreak[Speed] " + blockType.name()
                        + " elapsed=" + elapsed + "ms expected>=" + (int)(expectedMs * SPEED_BUFFER) + "ms"
                        + " hard=" + hardness + " vl=" + s.vl + "/" + BAN_VL);
                if (s.vl >= BAN_VL) { s.vl = 0; bm.banPlayer(player, "FastBreak[Speed] " + blockType.name() + " " + elapsed + "ms"); }
            }
        } else {
            if (s.lastBreakMs > 0 && now - s.lastBreakMs < MIN_BREAK_GAP_MS && hardness > 0.5) {
                s.packetVl++;
                debugLog(player, "FastBreak[Packet] " + blockType.name()
                        + " gap=" + (now - s.lastBreakMs) + "ms vl=" + s.packetVl + "/" + PACKET_BAN_VL);
                if (s.packetVl >= PACKET_BAN_VL) { s.packetVl = 0; bm.banPlayer(player, "FastBreak[Packet] " + blockType.name()); }
            }
        }

        s.breakStartMs = 0; s.blockType = null; s.hadDamage = false;
        s.lastBreakMs = now;
    }

    private void checkBreakRate(Player player, UUID id, long now, double hardness) {
        BreakState s = state.computeIfAbsent(id, k -> new BreakState());
        while (!s.recentBreaks.isEmpty() && now - s.recentBreaks.peek() > BREAK_WINDOW_MS) s.recentBreaks.poll();
        s.recentBreaks.add(now);

        AttributeInstance blockRange = player.getAttribute(Attribute.PLAYER_BLOCK_INTERACTION_RANGE);
        int maxBreaks = MAX_BREAKS_PER_SECOND;
        if (blockRange != null) {
            double attrRange = blockRange.getValue();
            if (attrRange > 4.9) {
                maxBreaks = (int) (MAX_BREAKS_PER_SECOND * (attrRange / 4.9));
            }
        }

        if (s.recentBreaks.size() >= maxBreaks && hardness > 0.3) {
            s.packetVl++;
            debugLog(player, "FastBreak[Nuker] " + s.recentBreaks.size() + " breaks/1s vl=" + s.packetVl + "/" + PACKET_BAN_VL);
            if (s.packetVl >= PACKET_BAN_VL) { s.packetVl = 0; bm.banPlayer(player, "FastBreak[Nuker] " + s.recentBreaks.size() + " blocks/sec"); }
            s.recentBreaks.clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(org.bukkit.event.block.BlockDamageEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        Block block = event.getBlock();
        double hardness = block.getType().getHardness();
        if (hardness <= 0) return;

        BreakState s = state.computeIfAbsent(player.getUniqueId(), k -> new BreakState());
        s.breakStartMs = System.currentTimeMillis();
        s.blockType = block.getType();
        s.hadDamage = true;
    }

    private boolean isInstantBreakable(Material m) {
        String n = m.name();
        return n.contains("BED") || n.contains("LEAVES") || n.contains("WEB")
            || n.contains("FLOWER") || n.contains("SAPLING") || n.contains("MUSHROOM")
            || n == "COBWEB" || n == "GRASS" || n == "TALL_GRASS" || n == "FERN"
            || n == "DEAD_BUSH" || n == "SEAGRASS" || n == "KELP" || n == "LILY_PAD"
            || n == "BROWN_MUSHROOM" || n == "RED_MUSHROOM" || n == "POPPY"
            || n.contains("TORCH") || n == "REDSTONE_WIRE" || n == "TRIPWIRE"
            || n == "CARROTS" || n == "POTATOES" || n == "WHEAT"
            || n == "SUGAR_CANE" || n == "MELON_STEM" || n == "PUMPKIN_STEM"
            || n == "NETHER_WART" || n == "COCOA" || n == "SWEET_BERRY_BUSH";
    }

    private double calculateExpectedBreakMs(Player player, Material blockType, double hardness) {
        if (hardness <= 0) return 50.0;
        Material tool = player.getInventory().getItemInMainHand().getType();
        boolean correct = isCorrectTool(tool, blockType);
        double speed = correct ? getToolSpeed(tool) : 1.0;

        int eff = player.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.EFFICIENCY);
        if (eff > 0 && correct) speed += (eff * eff) + 1;

        var hasteEffect = player.getPotionEffect(PotionEffectType.HASTE);
        if (hasteEffect != null) speed *= 1.0 + (0.2 * (hasteEffect.getAmplifier() + 1));

        if (player.isInWater()) {
            var helmet = player.getInventory().getHelmet();
            if (helmet == null || !helmet.containsEnchantment(Enchantment.AQUA_AFFINITY)) speed /= 5.0;
        }

        double dmgPerTick = speed / hardness / 30.0;
        if (dmgPerTick >= 1.0) return 50.0;
        return (1.0 / dmgPerTick) * 50.0;
    }

    private boolean isCorrectTool(Material tool, Material block) {
        String tn = tool.name(), bn = block.name();
        if (tn.endsWith("_PICKAXE")) return bn.contains("STONE") || bn.contains("ORE") || bn.contains("DEEPSLATE")
            || bn.contains("GRANITE") || bn.contains("DIORITE") || bn.contains("ANDESITE")
            || bn.contains("COBBLESTONE") || bn.contains("BRICK") || bn.contains("OBSIDIAN")
            || bn.contains("BASALT") || bn.contains("BLACKSTONE") || bn.contains("NETHERRACK")
            || bn.contains("END_STONE") || bn.contains("QUARTZ") || bn.contains("IRON")
            || bn.contains("GOLD") || bn.contains("DIAMOND") || bn.contains("EMERALD")
            || bn.contains("COPPER") || bn.contains("NETHERITE") || bn.contains("DEBRIS")
            || bn.contains("RAIL") || bn.contains("ANVIL") || bn.contains("CAULDRON")
            || bn.contains("PISTON") || bn.contains("DISPENSER") || bn.contains("DROPPER")
            || bn.contains("FURNACE") || bn.contains("HOPPER") || bn.contains("CHEST")
            || bn == "STONE" || bn.contains("INFESTED");
        if (tn.endsWith("_AXE")) return bn.contains("WOOD") || bn.contains("LOG") || bn.contains("PLANKS")
            || bn.contains("CHEST") || bn.contains("CRAFTING") || bn.contains("LADDER")
            || bn.contains("DOOR") || bn.contains("TRAPDOOR") || bn.contains("FENCE")
            || bn.contains("GATE") || bn.contains("SIGN") || bn.contains("BOOKSHELF")
            || bn.contains("JUKEBOX") || bn.contains("LOOM") || bn.contains("BARREL")
            || bn.contains("CAMPFIRE") || bn.contains("COMPOSTER") || bn.contains("MUSHROOM")
            || bn.contains("BEE") || bn.contains("COCOA") || bn == "MELON" || bn == "PUMPKIN"
            || bn.contains("CRIMSON") || bn.contains("WARPED");
        if (tn.endsWith("_SHOVEL")) return bn.contains("DIRT") || bn.contains("GRASS_BLOCK")
            || bn.contains("SAND") || bn.contains("GRAVEL") || bn.contains("CLAY")
            || bn.contains("SOUL_SAND") || bn.contains("SOUL_SOIL") || bn == "PODZOL"
            || bn == "MYCELIUM" || bn == "COARSE_DIRT" || bn == "ROOTED_DIRT"
            || bn.contains("CONCRETE_POWDER") || bn == "FARMLAND" || bn == "PATH";
        if (tn.endsWith("_HOE")) return bn.contains("LEAVES") || bn.contains("SPONGE")
            || bn.contains("HAY") || bn.contains("SHROOMLIGHT") || bn.contains("NETHER_WART")
            || bn.contains("SCULK") || bn.contains("MOSS") || bn == "TARGET" || bn == "DRAGON_EGG"
            || bn == "CHORUS_PLANT" || bn == "CHORUS_FLOWER";
        return false;
    }

    private double getToolSpeed(Material tool) {
        String n = tool.name();
        if (n.contains("NETHERITE")) return 9.0;
        if (n.contains("DIAMOND")) return 8.0;
        if (n.contains("IRON")) return 6.0;
        if (n.contains("STONE")) return 4.0;
        if (n.contains("GOLD")) return 12.0;
        if (n.contains("WOOD") || n.contains("WOODEN")) return 2.0;
        return 1.0;
    }

    private boolean isExempt(Player player) {
        GameMode gm = player.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR
                || player.hasPermission("anticheat.bypass")
                || wm.isWhitelisted(player.getUniqueId())
                || vm.hasJoinGrace(player.getUniqueId());
    }

    public void removePlayer(UUID id) { state.remove(id); }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][FastBreak] " + player.getName() + " >> " + msg);
        }
    }
}
