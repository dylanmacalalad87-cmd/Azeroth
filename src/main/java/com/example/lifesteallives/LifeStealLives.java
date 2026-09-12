package com.example.lifesteallives;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LifeStealLives - simplified rebuild.
 *
 * What this version does:
 *  - Tracks lives per player, bans at 0 (same as before).
 *  - On eating a golden apple / enchanted golden apple (or gaining Absorption
 *    from anything else), the resulting absorption hearts are snapped
 *    IMMEDIATELY to whatever "absorption-points" says for the player's
 *    CURRENT life count. No vanilla amount is ever shown first - we cancel
 *    the vanilla effect application and set the final value in the same
 *    tick, so there is no "2 hearts then drops to 1" flash.
 *  - Displays the player's lives as heart glyphs (using the
 *    "lifesteallives:hearts" resource-pack font) via a SINGLE mechanism:
 *    a scoreboard team suffix. Team suffix drives BOTH the nametag above
 *    the player's head AND their tab-list entry, so the two can never be
 *    out of sync or show different things.
 *  - The potion-duration-shortening feature has been removed entirely.
 *    There is no code left anywhere that touches potion durations based on
 *    lives. That's a clean slate to add back later, differently, if wanted.
 */
public class LifeStealLives extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Map<UUID, Integer> lives = new ConcurrentHashMap<>();
    private File livesFile;

    // Namespace/key of the resource-pack font. Must match the pack's
    // assets/lifesteallives/font/hearts.json exactly.
    private static final Key HEARTS_FONT = Key.key("lifesteallives", "hearts");
    private static final char GLYPH_FULL = '\uE000';
    private static final char GLYPH_HALF = '\uE001'; // unused for lives (whole numbers) but kept available
    private static final char GLYPH_EMPTY = '\uE002';

    @Override
    public void onEnable() {
        saveDefaultConfig();

        livesFile = new File(getDataFolder(), "lives.yml");
        if (!livesFile.getParentFile().exists()) {
            livesFile.getParentFile().mkdirs();
        }
        if (!livesFile.exists()) {
            try {
                livesFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("Could not create lives.yml: " + e.getMessage());
            }
        }
        loadLives();

        Bukkit.getPluginManager().registerEvents(this, this);

        for (String cmd : List.of("lives", "setlives", "addlife", "removelife", "revive")) {
            var pluginCommand = getCommand(cmd);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(this);
                pluginCommand.setTabCompleter(this);
            }
        }

        // Keep displays correct even if a team gets wiped by another plugin.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                updateDisplay(p);
            }
        }, 20L, 20L);

        getLogger().info("LifeStealLives (simplified) enabled");
    }

    @Override
    public void onDisable() {
        saveLives();
    }

    // ---------------------------------------------------------------
    // Lives persistence
    // ---------------------------------------------------------------

    private void loadLives() {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(livesFile);
            for (String key : yaml.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    lives.put(id, yaml.getInt(key));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed entries rather than crash
                }
            }
        } catch (Exception e) {
            getLogger().warning("Could not load lives.yml: " + e.getMessage());
        }
    }

    private void saveLives() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            lives.forEach((id, count) -> yaml.set(id.toString(), count));
            yaml.save(livesFile);
        } catch (IOException e) {
            getLogger().warning("Could not save lives: " + e.getMessage());
        }
    }

    private int getDefaultLives() {
        return getConfig().getInt("default-lives", 3);
    }

    public int getLives(Player p) {
        return lives.getOrDefault(p.getUniqueId(), getDefaultLives());
    }

    public int getOfflineLives(UUID id) {
        return lives.getOrDefault(id, getDefaultLives());
    }

    private void setLives(Player p, int amount) {
        lives.put(p.getUniqueId(), Math.max(0, amount));
        updateDisplay(p);
        applyAbsorptionCap(p);
        saveLives();
    }

    // ---------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------

    @EventHandler
    public void join(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        lives.putIfAbsent(p.getUniqueId(), getDefaultLives());
        updateDisplay(p);
        applyAbsorptionCap(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void death(PlayerDeathEvent event) {
        Player p = event.getEntity();
        int newLives = Math.max(0, getLives(p) - 1);
        lives.put(p.getUniqueId(), newLives);
        saveLives();

        p.sendMessage(color("&cYou lost a life! &7Lives remaining: &f" + newLives));
        updateDisplay(p);

        if (newLives <= 0) {
            eliminate(p);
        }
    }

    /**
     * Catches absorption being granted from ANY source (golden apples,
     * enchanted golden apples, totems, other plugins/effects) and replaces
     * it in the same tick with the exact amount the player's current lives
     * allow. Nothing is shown before this runs, so there is no flash.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void potion(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getNewEffect() == null) return;
        if (event.getNewEffect().getType() != PotionEffectType.ABSORPTION) return;

        EntityPotionEffectEvent.Action action = event.getAction();
        if (action != EntityPotionEffectEvent.Action.ADDED
                && action != EntityPotionEffectEvent.Action.CHANGED) {
            return;
        }

        event.setCancelled(true);
        applyAbsorptionCap(player);
    }

    // ---------------------------------------------------------------
    // Absorption handling (no potion-effect object involved at all -
    // we just set the number directly)
    // ---------------------------------------------------------------

    private double getAbsorptionPointsFor(int currentLives) {
        return getConfig().getDouble("absorption-points." + currentLives, 0.0);
    }

    public void applyAbsorptionCap(Player p) {
        double points = getAbsorptionPointsFor(getLives(p));
        p.setAbsorptionAmount(Math.max(0.0, points));
    }

    // ---------------------------------------------------------------
    // Elimination
    // ---------------------------------------------------------------

    private void eliminate(Player p) {
        String reason = color(getConfig().getString("ban-reason", "&cYou have lost all your lives!"));
        Bukkit.getBanList(org.bukkit.BanList.Type.NAME).addBan(p.getName(), reason, null, "LifeStealLives");
        p.kickPlayer(reason);
    }

    // ---------------------------------------------------------------
    // Display: ONE method, drives both the nametag and the tab list,
    // via a scoreboard team suffix. This is the only place that touches
    // player display, so tab and nametag can never disagree.
    // ---------------------------------------------------------------

    private void updateDisplay(Player p) {
        int current = getLives(p);
        int max = getDefaultLives();

        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = "ls_" + p.getUniqueId().toString().substring(0, 14);

        Team team = board.getTeam(teamName);
        if (team == null) {
            try {
                team = board.registerNewTeam(teamName);
            } catch (IllegalArgumentException e) {
                team = board.getTeam(teamName);
            }
        }
        if (team == null) return;

        if (!team.hasEntry(p.getName())) {
            team.addEntry(p.getName());
        }

        team.suffix(heartBar(current, max));
    }

    private void updateAllDisplays() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            updateDisplay(p);
        }
    }

    private Component heartBar(int current, int max) {
        StringBuilder raw = new StringBuilder(" ");
        for (int i = 1; i <= max; i++) {
            raw.append(i <= current ? GLYPH_FULL : GLYPH_EMPTY);
        }
        return Component.text(raw.toString())
                .color(NamedTextColor.WHITE)
                .font(HEARTS_FONT);
    }

    // ---------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "lives" -> {
                Player target;
                if (args.length >= 1) {
                    OfflinePlayer off = Bukkit.getOfflinePlayer(args[0]);
                    if (off.isOnline()) {
                        target = (Player) off;
                        sender.sendMessage(color("&f" + target.getName() + " has &e" + getLives(target) + "&f lives."));
                    } else {
                        sender.sendMessage(color("&f" + args[0] + " has &e" + getOfflineLives(off.getUniqueId()) + "&f lives."));
                    }
                } else if (sender instanceof Player self) {
                    sender.sendMessage(color("&fYou have &e" + getLives(self) + "&f lives."));
                } else {
                    sender.sendMessage(color("&cUsage: /lives [player]"));
                }
                return true;
            }
            case "setlives", "addlife", "removelife" -> {
                if (!sender.hasPermission("lifesteal.admin")) {
                    sender.sendMessage(color("&cNo permission."));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(color("&cUsage: /" + label + " <player> <amount>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    sender.sendMessage(color("&cPlayer not found or offline."));
                    return true;
                }
                int amount;
                try {
                    amount = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(color("&cAmount must be a whole number."));
                    return true;
                }
                if (amount < 0 && command.getName().equalsIgnoreCase("setlives")) {
                    sender.sendMessage(color("&cAmount must be a non-negative whole number."));
                    return true;
                }

                int current = getLives(target);
                int result = switch (command.getName().toLowerCase(Locale.ROOT)) {
                    case "addlife" -> current + Math.max(0, amount);
                    case "removelife" -> Math.max(0, current - Math.max(0, amount));
                    default -> Math.max(0, amount);
                };
                setLives(target, result);
                sender.sendMessage(color("&f" + target.getName() + " now has &e" + result + "&f lives."));
                if (result <= 0) eliminate(target);
                return true;
            }
            case "revive" -> {
                if (!sender.hasPermission("lifesteal.admin")) {
                    sender.sendMessage(color("&cNo permission."));
                    return true;
                }
                if (args.length < 1) {
                    sender.sendMessage(color("&cUsage: /revive <player>"));
                    return true;
                }
                String name = args[0];
                Bukkit.getBanList(org.bukkit.BanList.Type.NAME).pardon(name);
                OfflinePlayer off = Bukkit.getOfflinePlayer(name);
                lives.put(off.getUniqueId(), getDefaultLives());
                saveLives();
                sender.sendMessage(color("&f" + name + " has been revived with &e" + getDefaultLives() + "&f lives."));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && !command.getName().equalsIgnoreCase("lives") || args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                    names.add(p.getName());
                }
            }
            return names;
        }
        return Collections.emptyList();
    }

    // ---------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
