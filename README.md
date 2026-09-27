# AntiCheat — Bukkit 1.21.1

A lightweight, production-ready anti-cheat plugin focused on **fly detection** with automatic 30-day bans.

---

## Requirements

| Requirement | Version |
|-------------|---------|
| Minecraft   | 1.21.1  |
| Server      | Spigot / Paper 1.21.1 |
| Java        | 21 (LTS) |
| Gradle      | 8.x     |

> **Note on Java 25:** Java 25 is not yet released (expected Sept 2025). The build is configured for Java 21 LTS, which is fully supported. Change `JavaLanguageVersion.of(21)` → `JavaLanguageVersion.of(25)` in `build.gradle` once Java 25 is available and your JDK supports it.

---

## Building

```bash
# Clone / extract the project, then:
./gradlew jar

# Output: build/libs/AntiCheat-1.0.0.jar
```

---

## Installation

1. Drop `AntiCheat-1.0.0.jar` into your server's `plugins/` folder.
2. Start/restart the server.
3. Edit `plugins/AntiCheat/config.yml` to tune thresholds.
4. Run `/anticheat reload` to apply changes without a restart.

---

## How Fly Detection Works

| Check | Trigger | VL Added |
|-------|---------|----------|
| `Fly[UpVelocity]` | Player moves upward faster than vanilla allows | +1 |
| `Fly[Hover]`      | Player stays airborne with no Y-movement for 20+ ticks | +1 |
| `Fly[UpInAir]`    | Player moves upward while already airborne | +1 |
| `Fly[ToggleFlight]` | Client enables flight without server permission | +5 |

When VL reaches the threshold (default **10**), the player is:
- Kicked immediately
- Banned for **30 days** via Bukkit's ban list
- Ban screen reads: *"Using a modified client"*
- All online players see a broadcast

### False-Positive Mitigation

Grace periods are automatically applied for:
- **Jumping** (6 ticks)
- **Taking damage / knockback** (10 ticks)
- **Teleporting** (20 ticks)
- **Joining the server** (60 ticks = 3 seconds)
- **Levitation potion**
- **Elytra gliding**
- **Being in or near liquid**
- **Being on climbable blocks** (ladders, vines, scaffolding, etc.)
- **Creative / Spectator mode**
- **Server-granted flight** (`/fly`, etc.)

VL also **decays over time** (every 40 ticks by default) so legitimate players don't accumulate false flags.

---

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/anticheat reload` | Reload config | `anticheat.admin` |
| `/anticheat status` | Show plugin info | `anticheat.admin` |
| `/anticheat vl <player>` | Check a player's VL | `anticheat.admin` |
| `/anticheat whitelist add/remove <player>` | Manage bypass | `anticheat.admin` |

## Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `anticheat.admin` | OP | Access to all commands |
| `anticheat.bypass` | false | Exempt from all checks |

---

## Configuration

See `config.yml` for full options. Key values:

```yaml
settings:
  fly-vl-threshold: 10       # VL points before ban
  vl-decay-ticks: 40         # How often VL decreases
  max-upward-velocity: 0.42  # Max allowed Y velocity per tick

ban:
  duration-days: 30
  reason: "..."              # Supports & color codes and \n newlines
```

---

## Tuning Tips

- **Too many false positives?** Increase `fly-vl-threshold` to 15–20 and increase grace tick values.
- **Too lenient?** Lower `fly-vl-threshold` to 6–8.
- **Enable debug mode** (`debug.enabled: true`) to watch VL accumulate in real time.
- Grant `anticheat.bypass` to trusted staff via your permissions plugin (LuckPerms, etc.).
