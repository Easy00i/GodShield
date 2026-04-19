package com.ancienteye.godshield;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

// ════════════════════════════════════════════════════════
//  GodShield  –  Main Plugin Entry Point
// ════════════════════════════════════════════════════════
public class GodShield extends JavaPlugin implements TabCompleter {

    private static GodShield instance;

    private OrbitManager    orbitManager;
    private ShieldManager   shieldManager;
    private AbilityManager  abilityManager;
    private CraftManager    craftManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        orbitManager   = new OrbitManager(this);
        shieldManager  = new ShieldManager(this);
        abilityManager = new AbilityManager(this);
        craftManager   = new CraftManager(this);

        getServer().getPluginManager().registerEvents(new PlayerListener(this),  this);
        getServer().getPluginManager().registerEvents(new DamageListener(this),  this);
        getServer().getPluginManager().registerEvents(new AbilityListener(this), this);
        getServer().getPluginManager().registerEvents(new CraftListener(this),   this);

        craftManager.registerRecipe();
        orbitManager.startTask();

        // Register tab completer for /god
        if (getCommand("god") != null) getCommand("god").setTabCompleter(this);

        // Re-activate for players already holding shield on reload (with 1 tick delay)
Bukkit.getScheduler().runTaskLater(this, () -> {
    for (Player p : getServer().getOnlinePlayers()) {
        if (GodShieldItem.isGodShield(p.getInventory().getItemInOffHand())) {
            shieldManager.activate(p);
        }
    }
}, 1L);

        getLogger().info("[GodShield] Plugin ENABLED.");
    }

    @Override
    public void onDisable() {
        if (orbitManager != null) orbitManager.cleanup();
        getLogger().info("[GodShield] Plugin disabled.");
    }

    // ─────────────────────────────────────────────────────────────
    //  Command: /god give shield [player]
    //
    //  /god give shield           → give shield to self
    //  /god give shield <player>  → give shield to target
    // ─────────────────────────────────────────────────────────────
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("god")) return false;

        if (!sender.hasPermission("godshield.admin")) {
            sender.sendMessage(Component.text("✘ You don't have permission.")
                .color(NamedTextColor.RED));
            return true;
        }

        // /god  or  /god give  or  /god give <not shield>  → usage
        if (args.length < 2
                || !args[0].equalsIgnoreCase("give")
                || !args[1].equalsIgnoreCase("shield")) {
            sendUsage(sender);
            return true;
        }

        // /god give shield  → give to self
        if (args.length == 2) {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(Component.text(
                    "✘ Console must specify a player: /god give shield <player>")
                    .color(NamedTextColor.RED));
                return true;
            }
            giveShield(sender, self);
            return true;
        }

        // /god give shield <player>  → give to target
        Player target = resolvePlayer(args[2]);
        if (target == null) {
            sender.sendMessage(Component.text("✘ Player not found: " + args[2])
                .color(NamedTextColor.RED));
            return true;
        }
        giveShield(sender, target);
        return true;
    }

    // ── Tab completion ────────────────────────────────────────────
    //   arg[0]  arg[1]   arg[2]
    //   give  → shield → <online players>
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd,
                                      String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("god")) return List.of();
        if (!sender.hasPermission("godshield.admin")) return List.of();

        List<String> out = new ArrayList<>();
        String typed = args[args.length - 1].toLowerCase();

        switch (args.length) {
            case 1 -> {
                if ("give".startsWith(typed)) out.add("give");
            }
            case 2 -> {
                if (args[0].equalsIgnoreCase("give") && "shield".startsWith(typed))
                    out.add("shield");
            }
            case 3 -> {
                if (args[0].equalsIgnoreCase("give") && args[1].equalsIgnoreCase("shield")) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getName().toLowerCase().startsWith(typed))
                            out.add(p.getName());
                    }
                }
            }
        }
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────
    /** Exact match first, then prefix match */
    private Player resolvePlayer(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(name.toLowerCase())) return p;
        }
        return null;
    }

    private void giveShield(CommandSender sender, Player target) {
        target.getInventory().addItem(GodShieldItem.create());
        target.sendMessage(
            Component.text("⚔ God Shield ")
                .color(NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
            .append(Component.text("has been given to you!")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false))
        );
        if (!sender.equals(target)) {
            sender.sendMessage(
                Component.text("⚔ God Shield given to ").color(NamedTextColor.GOLD)
                .append(Component.text(target.getName()).color(NamedTextColor.WHITE))
                .append(Component.text(" successfully.").color(NamedTextColor.GOLD))
            );
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text(""));
        sender.sendMessage(
            Component.text("  ⚔ God Shield — Commands")
                .color(NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
        sender.sendMessage(
            Component.text("  ─────────────────────────────────")
                .color(NamedTextColor.DARK_GRAY));
        sender.sendMessage(
            Component.text("  /god give shield")
                .color(NamedTextColor.YELLOW)
            .append(Component.text("  →  Give to yourself")
                .color(NamedTextColor.GRAY)));
        sender.sendMessage(
            Component.text("  /god give shield ")
                .color(NamedTextColor.YELLOW)
            .append(Component.text("<player>").color(NamedTextColor.AQUA))
            .append(Component.text("  →  Give to a player").color(NamedTextColor.GRAY)));
        sender.sendMessage(
            Component.text("  ─────────────────────────────────")
                .color(NamedTextColor.DARK_GRAY));
        sender.sendMessage(Component.text(""));
    }

    // ── Getters ───────────────────────────────────────────────────
    public static GodShield getInstance()       { return instance; }
    public OrbitManager    getOrbitManager()    { return orbitManager; }
    public ShieldManager   getShieldManager()   { return shieldManager; }
    public AbilityManager  getAbilityManager()  { return abilityManager; }
    public CraftManager    getCraftManager()    { return craftManager; }
}

// ════════════════════════════════════════════════════════
//  GodShieldItem  –  Item factory & identity check
// ════════════════════════════════════════════════════════
class GodShieldItem {

    private GodShieldItem() {}

    static int getCMD() {
        return GodShield.getInstance().getConfig().getInt("godshield.custom-model-data", 1001);
    }

    static ItemStack create() {
        ItemStack item = new ItemStack(Material.SHIELD);
        ItemMeta  meta = item.getItemMeta();

        meta.displayName(
            Component.text("⚔ God Shield ⚔")
                .color(NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD,   true)
                .decoration(TextDecoration.ITALIC, false)
        );

        meta.lore(List.of(
            Component.text(""),
            Component.text(" ✦ Divine Protection ✦")
                .color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false),
            Component.text(""),
            Component.text(" Passive  (Off-Hand):")
                .color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("  ► 4 Orbiting Shields")
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("  ► Complete Damage Immunity")
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("  ► Health / Regen / Strength / Fire Res IV")
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false),
            Component.text(""),
            Component.text(" ⚡ Shift + Right-Click  »  Shockwave")
                .color(NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("    Destroys blocks · One-shots nearby enemies")
                .color(NamedTextColor.DARK_PURPLE)
                .decoration(TextDecoration.ITALIC, false),
            Component.text(""),
            Component.text(" 🔒 Left-Click  »  Prison")
                .color(NamedTextColor.DARK_AQUA)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("    Particle cube · 5❤/s · 10 sec · No escape")
                .color(NamedTextColor.DARK_CYAN)
                .decoration(TextDecoration.ITALIC, false),
            Component.text(""),
            Component.text(" 👁 Shift + Left-Click  »  Mind Control")
                .color(NamedTextColor.DARK_RED)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("    Freeze & control enemy · Refreshes on hit")
                .color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("")
        ));

        meta.setCustomModelData(getCMD());
        meta.setUnbreakable(true);
        meta.addItemFlags(
            ItemFlag.HIDE_UNBREAKABLE,
            ItemFlag.HIDE_ATTRIBUTES,
            ItemFlag.HIDE_ENCHANTS,
            ItemFlag.HIDE_ADDITIONAL_TOOLTIP
        );
        item.setItemMeta(meta);
        return item;
    }

    static boolean isGodShield(ItemStack item) {
        if (item == null || item.getType() != Material.SHIELD) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasCustomModelData() && meta.getCustomModelData() == getCMD();
    }
          }
