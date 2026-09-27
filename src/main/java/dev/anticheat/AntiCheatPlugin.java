package dev.anticheat;

import dev.anticheat.commands.AntiCheatCommand;
import dev.anticheat.listeners.*;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.DiscordWebhookManager;
import dev.anticheat.managers.HistoryManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import dev.anticheat.utils.ColorUtil;
import dev.anticheat.utils.TPSUtil;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class AntiCheatPlugin extends JavaPlugin {

    private static AntiCheatPlugin instance;

    private BanManager            banManager;
    private ViolationManager      violationManager;
    private WhitelistManager      whitelistManager;
    private HistoryManager        historyManager;
    private DiscordWebhookManager discordWebhookManager;

    // Listener references needed for per-listener cleanup in PlayerStateListener
    private FlyListener              flyListener;
    private ReachListener            reachListener;
    private SpeedListener            speedListener;
    private AirPlaceListener         airPlaceListener;
    private InventoryAttackListener  inventoryAttackListener;
    private MiscCheatListener        miscCheatListener;
    private AntiKnockbackListener    antiKnockbackListener;
    private NoFallListener           noFallListener;
    private InventoryWalkListener    inventoryWalkListener;
    private FastLadderListener       fastLadderListener;
    private SnowWalkListener         snowWalkListener;
    private ScaffoldListener         scaffoldListener;
    private ClickTpListener          clickTpListener;
    private VClipListener            vclipListener;
    private CritListener             critListener;
    private ImpossibleSprintListener impossibleSprintListener;

    private NoWebListener            noWebListener;
    private BoatFlyListener          boatFlyListener;
    private JetpackListener          jetpackListener;
    private CombatSimulationListener combatSimulationListener;
    private AntiMaceDamageListener   maceDamageListener;
    private ElytraFlightListener     elytraFlightListener;
    private TriggerBotListener       triggerBotListener;
    private MovementSimulationListener moveSimListener;
    private AntiTimerListener        antiTimerListener;

    private AntiFastBreakListener    antiFastBreakListener;
    private AirJumpListener          airJumpListener;
    private PhysicsSimulationListener physicsSimListener;
    private LevitationSimulationListener levitationSimListener;


    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        reloadConfig();

        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        TPSUtil.start(this);

        // Managers — whitelist before ban, both before violation, history last
        this.whitelistManager      = new WhitelistManager(this);
        this.banManager            = new BanManager(this);
        this.violationManager      = new ViolationManager(this);
        this.historyManager        = new HistoryManager(this);
        this.discordWebhookManager = new DiscordWebhookManager(this);

        // Listeners
        this.flyListener              = new FlyListener(this);
        this.reachListener            = new ReachListener(this);
        this.speedListener            = new SpeedListener(this);
        this.airPlaceListener         = new AirPlaceListener(this);
        this.inventoryAttackListener  = new InventoryAttackListener(this);
        this.miscCheatListener        = new MiscCheatListener(this);
        this.antiKnockbackListener    = new AntiKnockbackListener(this);
        this.noFallListener           = new NoFallListener(this);
        this.inventoryWalkListener    = new InventoryWalkListener(this);
        this.fastLadderListener       = new FastLadderListener(this);
        this.snowWalkListener         = new SnowWalkListener(this);
        this.scaffoldListener         = new ScaffoldListener(this);
        this.clickTpListener          = new ClickTpListener(this);
        this.vclipListener            = new VClipListener(this);
        this.critListener             = new CritListener(this);
        this.impossibleSprintListener = new ImpossibleSprintListener(this);

        this.noWebListener            = new NoWebListener(this);
        this.boatFlyListener          = new BoatFlyListener(this);
        this.jetpackListener          = new JetpackListener(this);
        this.combatSimulationListener = new CombatSimulationListener(this);
        this.maceDamageListener       = new AntiMaceDamageListener(this);
        this.elytraFlightListener     = new ElytraFlightListener(this);
        this.triggerBotListener       = new TriggerBotListener(this);
        this.moveSimListener          = new MovementSimulationListener(this);
        this.antiTimerListener        = new AntiTimerListener(this);

        this.antiFastBreakListener    = new AntiFastBreakListener(this);
        this.airJumpListener          = new AirJumpListener(this);
        this.physicsSimListener       = new PhysicsSimulationListener(this);
        this.levitationSimListener    = new LevitationSimulationListener(this);


        getServer().getPluginManager().registerEvents(flyListener,                   this);
        getServer().getPluginManager().registerEvents(reachListener,                 this);
        getServer().getPluginManager().registerEvents(speedListener,                 this);
        getServer().getPluginManager().registerEvents(airPlaceListener,              this);
        getServer().getPluginManager().registerEvents(inventoryAttackListener,       this);
        getServer().getPluginManager().registerEvents(miscCheatListener,             this);
        getServer().getPluginManager().registerEvents(antiKnockbackListener,         this);
        getServer().getPluginManager().registerEvents(noFallListener,                this);
        getServer().getPluginManager().registerEvents(inventoryWalkListener,         this);
        getServer().getPluginManager().registerEvents(fastLadderListener,            this);
        getServer().getPluginManager().registerEvents(snowWalkListener,              this);
        getServer().getPluginManager().registerEvents(scaffoldListener,              this);
        getServer().getPluginManager().registerEvents(clickTpListener,               this);
        getServer().getPluginManager().registerEvents(vclipListener,                 this);
        getServer().getPluginManager().registerEvents(critListener,                  this);
        getServer().getPluginManager().registerEvents(impossibleSprintListener,       this);

        getServer().getPluginManager().registerEvents(noWebListener,                 this);
        getServer().getPluginManager().registerEvents(boatFlyListener,               this);
        getServer().getPluginManager().registerEvents(jetpackListener,               this);
        getServer().getPluginManager().registerEvents(combatSimulationListener,    this);
        getServer().getPluginManager().registerEvents(maceDamageListener,            this);
        getServer().getPluginManager().registerEvents(elytraFlightListener,          this);
        getServer().getPluginManager().registerEvents(triggerBotListener,            this);
        getServer().getPluginManager().registerEvents(moveSimListener,               this);
        getServer().getPluginManager().registerEvents(antiTimerListener,             this);

        getServer().getPluginManager().registerEvents(antiFastBreakListener,         this);
        getServer().getPluginManager().registerEvents(airJumpListener,               this);
        getServer().getPluginManager().registerEvents(physicsSimListener,            this);
        getServer().getPluginManager().registerEvents(levitationSimListener,         this);
        getServer().getPluginManager().registerEvents(new PlayerStateListener(this), this);

        // Commands — /anticheat is primary, /anticheat discord webhook is the moved webhook command
        AntiCheatCommand acCmd = new AntiCheatCommand(this);
        getCommand("anticheat").setExecutor(acCmd);
        getCommand("anticheat").setTabCompleter(acCmd);

        // Keep legacy /discord-webhook for backward compatibility if still defined in older plugin.yml
        try {
            if (getCommand("discord-webhook") != null) {
                dev.anticheat.commands.DiscordWebhookCommand dwCmd = new dev.anticheat.commands.DiscordWebhookCommand(this);
                getCommand("discord-webhook").setExecutor(dwCmd);
                getCommand("discord-webhook").setTabCompleter(dwCmd);
            }
        } catch (Exception ignored) {}

        String mode = getConfig().getString("punishment.mode", "temp");
        String modeDisplay = switch (mode) {
            case "kick"       -> "Kick only";
            case "permanent"  -> "Permanent ban";
            case "alert"      -> "Alerts only (no action)";
            case "rubberband" -> "Rubberband (lagback)";
            default           -> "Temp ban (" + getConfig().getInt("ban.duration-days", 30) + "d)";
        };

        String branding = getDisplayName();
        getLogger().info(ColorUtil.strip("&a[" + branding + "] Enabled — Bukkit 1.21.1"));
        if (!branding.equals("AntiCheat")) {
            getLogger().info(ColorUtil.strip("&a[" + branding + "] Custom nickname active"));
        }
        getLogger().info(ColorUtil.strip("&a[" + branding + "] Punishment mode: " + modeDisplay));
        getLogger().info(ColorUtil.strip("&a[" + branding + "] Checks active: Fly, Speed, Reach, RangedReach, AirPlace, NoFall, "
                + "KillauraInventory, ZeroHungerSprint, SleepMovement, AntiKnockback, "
                + "FastLadder, SnowWalk, InventoryWalk, Scaffold, ClickTp, AirJump, "
                + "VClip, Crit, ImpossibleSprint, NoWeb, BoatFly, Jetpack, Combat, FastBreak, MaceCheat, ElytraFlight, TriggerBot, MoveSim(+Spider/Glide), Timer, PhysicsSim(Speed/Flight/Jesus/BoatFly+Ice/Attributes), LevitationSim"));
        if (discordWebhookManager.isEnabled()) {
            getLogger().info(ColorUtil.strip("&a[" + branding + "] Discord webhook: &f" + discordWebhookManager.maskUrl(discordWebhookManager.getWebhookUrl())));
        } else {
            getLogger().info(ColorUtil.strip("&7[" + branding + "] Discord webhook: not configured (use /anticheat discord webhook <url>)"));
        }
    }

    @Override
    public void onDisable() {
        if (violationManager != null) violationManager.cleanup();
        if (historyManager != null) historyManager.flush();
        TPSUtil.stop();
        getLogger().info("[" + getDisplayName() + "] Disabled.");
    }

    // ── Branding / nickname ────────────────────────────────────────────────

    /**
     * Returns the custom anticheat display name set via /anticheat nickname.
     * Checks branding.nickname (primary), settings.nickname (legacy), and
     * nickname (root). Falls back to "AntiCheat". Strips excessive length.
     */
    public String getDisplayName() {
        String nick = getConfig().getString("branding.nickname", null);
        if (nick == null || nick.trim().isEmpty()) nick = getConfig().getString("settings.nickname", null);
        if (nick == null || nick.trim().isEmpty()) nick = getConfig().getString("nickname", null);
        if (nick == null || nick.trim().isEmpty()) return "AntiCheat";
        nick = nick.trim();
        // Limit to 32 chars visible (strip colors length)
        if (ColorUtil.strip(nick).length() > 32) nick = nick.substring(0, 32);
        return nick;
    }

    /** Returns the colorized bracketed prefix using the current nickname, e.g. [MyAC] */
    public String getPrefix() {
        String display = getDisplayName();
        // If nickname contains color codes, preserve them, otherwise wrap with &c
        if (display.contains("&")) return ColorUtil.colorize("&8[" + display + "&8] ");
        return ColorUtil.colorize("&8[&c" + display + "&8] ");
    }

    /** Raw color-stripped prefix for console logging */
    public String getConsolePrefix() {
        return "[" + ColorUtil.strip(getDisplayName()) + "]";
    }

    // ── Flag alert — called by BanManager when a punishment fires ────────────

    /**
     * Sends a short flag alert to all online players with anticheat.admin.
     * The check name is simplified to plain English (e.g. "Flight" not
     * "Fly[ConsecUpTicks] upTicks=4/4") so admins get a clean summary.
     *
     * Can be disabled via flags.alerts-enabled: false in config.yml.
     */
    public void alertFlag(Player player, String rawCheck) {
        String simplified = simplifyCheck(rawCheck);
        String display = getDisplayName();
        String msg = ColorUtil.colorize(
                "&8[&c" + display + "&8] &c⚑ &f" + player.getName()
                + " &7flagged for &c" + simplified);

        String recipient = getConfig().getString("flags.alerts-recipient", "staff");
        for (Player online : getServer().getOnlinePlayers()) {
            boolean send = switch (recipient) {
                case "all"  -> true;
                case "ops"  -> online.isOp();
                default     -> online.hasPermission("anticheat.admin"); // "staff"
            };
            if (send) online.sendMessage(msg);
        }
        getLogger().info(getConsolePrefix() + " FLAG: " + player.getName() + " | " + simplified
                + " | (raw: " + rawCheck + ")");

        // ── Discord webhook ───────────────────────────────────────────────
        // Sends raw reason + IP for precise forensics.
        if (getConfig().getBoolean("discord.send-on-flag", true)) {
            try {
                String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                        ? player.getAddress().getAddress().getHostAddress()
                        : "unknown";
                if (discordWebhookManager != null && discordWebhookManager.isEnabled()) {
                    discordWebhookManager.sendAlert(player, rawCheck, ip);
                }
            } catch (Exception e) {
                getLogger().warning("[AntiCheat] Failed to dispatch Discord webhook: " + e.getMessage());
            }
        }
    }

    /**
     * Maps raw internal check names to plain-English summaries.
     * Anything with technical detail appended (e.g. "upTicks=4/4") is stripped.
     */
    private String simplifyCheck(String check) {
        if (check.startsWith("Fly"))             return "Flight";
        if (check.startsWith("Speed"))           return "Speed";
        if (check.startsWith("Reach"))           return "Reach";
        if (check.startsWith("NoFall"))          return "No Fall";
        if (check.startsWith("InventoryWalk"))   return "Inventory Walk";
        if (check.startsWith("InventoryAttack")
         || check.startsWith("KillAura"))        return "Killaura";
        if (check.startsWith("AntiKnockback"))   return "Anti-Knockback";
        if (check.startsWith("AirPlace"))        return "Air Place";
        if (check.startsWith("FastLadder"))      return "Fast Ladder";
        if (check.startsWith("SnowWalk"))        return "Snow Walk";
        if (check.startsWith("ZeroHunger"))      return "Zero Hunger Sprint";
        if (check.startsWith("Sleep"))           return "Sleep Movement";
        if (check.startsWith("Scaffold"))        return "Scaffold";
        if (check.startsWith("ClickTp"))         return "Click Teleport";
        if (check.startsWith("VClip"))           return "Vertical Clip";
        if (check.startsWith("HFlight"))         return "Flight";
        if (check.startsWith("Crit"))            return "Criticals";
        if (check.startsWith("ImpossibleSprint"))return "Impossible Sprint";
        if (check.startsWith("RangedReach"))     return "Ranged Reach";
        if (check.startsWith("NoWeb"))           return "No Web";
        if (check.startsWith("BoatFly"))         return "Boat Fly";
        if (check.startsWith("BoatSpeed"))       return "Boat Speed";
        if (check.startsWith("Jetpack"))         return "Jetpack";
        if (check.startsWith("CombatSim"))       return "Combat";
        if (check.startsWith("MaceDamage"))      return "Mace Cheat";
        if (check.startsWith("ElytraFlight"))    return "Elytra Flight";
        if (check.startsWith("TriggerBot"))      return "Trigger Bot";
        if (check.startsWith("Step"))             return "Step";
        if (check.startsWith("Spider"))          return "Spider";
        if (check.startsWith("Timer"))           return "Timer";

        if (check.startsWith("FastBreak"))       return "Fast Break";
        if (check.startsWith("AirJump"))         return "Air Jump";
        if (check.startsWith("Jesus"))           return "Jesus";
        if (check.startsWith("BoatFly[Sim]"))    return "Boat Fly";
        if (check.startsWith("Speed[Sim]"))      return "Speed";
        if (check.startsWith("Flight[Sim]"))     return "Flight";
        if (check.startsWith("Levitation"))      return "Levitation";
        if (check.startsWith("Glide"))           return "Glide";

        return check; // fallback — return raw name if not mapped
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public static AntiCheatPlugin getInstance() { return instance; }

    public BanManager            getBanManager()            { return banManager; }
    public ViolationManager      getViolationManager()      { return violationManager; }
    public WhitelistManager      getWhitelistManager()      { return whitelistManager; }
    public HistoryManager        getHistoryManager()        { return historyManager; }
    public DiscordWebhookManager getDiscordWebhookManager() { return discordWebhookManager; }

    public FlyListener              getFlyListener()             { return flyListener; }
    public ReachListener            getReachListener()           { return reachListener; }
    public SpeedListener            getSpeedListener()           { return speedListener; }
    public AirPlaceListener         getAirPlaceListener()        { return airPlaceListener; }
    public InventoryAttackListener  getInventoryAttackListener() { return inventoryAttackListener; }
    public MiscCheatListener        getMiscCheatListener()       { return miscCheatListener; }
    public AntiKnockbackListener    getAntiKnockbackListener()   { return antiKnockbackListener; }
    public NoFallListener           getNoFallListener()          { return noFallListener; }
    public InventoryWalkListener    getInventoryWalkListener()   { return inventoryWalkListener; }
    public FastLadderListener       getFastLadderListener()      { return fastLadderListener; }
    public SnowWalkListener         getSnowWalkListener()        { return snowWalkListener; }
    public ScaffoldListener         getScaffoldListener()        { return scaffoldListener; }
    public ClickTpListener          getClickTpListener()         { return clickTpListener; }
    public VClipListener             getVClipListener()           { return vclipListener; }
    public CritListener              getCritListener()            { return critListener; }
    public ImpossibleSprintListener  getImpossibleSprintListener(){ return impossibleSprintListener; }

    public NoWebListener             getNoWebListener()           { return noWebListener; }
    public BoatFlyListener           getBoatFlyListener()         { return boatFlyListener; }
    public JetpackListener           getJetpackListener()         { return jetpackListener; }
    public CombatSimulationListener  getCombatSimulationListener() { return combatSimulationListener; }
    public AntiMaceDamageListener    getMaceDamageListener()      { return maceDamageListener; }
    public ElytraFlightListener      getElytraFlightListener()    { return elytraFlightListener; }
    public TriggerBotListener        getTriggerBotListener()      { return triggerBotListener; }
    public MovementSimulationListener getMoveSimListener()         { return moveSimListener; }
    public AntiTimerListener         getAntiTimerListener()       { return antiTimerListener; }

    public AntiFastBreakListener     getAntiFastBreakListener()   { return antiFastBreakListener; }
    public AirJumpListener           getAirJumpListener()         { return airJumpListener; }
    public PhysicsSimulationListener getPhysicsSimListener()      { return physicsSimListener; }
    public LevitationSimulationListener getLevitationSimListener(){ return levitationSimListener; }

}
