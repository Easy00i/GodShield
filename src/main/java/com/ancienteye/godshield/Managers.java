package com.ancienteye.godshield;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

// ════════════════════════════════════════════════════════
//  OrbitManager  –  4 ItemDisplay shields orbit loop
// ════════════════════════════════════════════════════════
class OrbitManager {

    private final GodShield plugin;
    private final Map<UUID, List<ItemDisplay>> orbitMap = new HashMap<>();
    private final Map<UUID, Double>            angleMap = new HashMap<>();
    private BukkitRunnable orbitTask;

    private final double RADIUS;
    private final double SPEED;
    private final double HEIGHT;

    OrbitManager(GodShield plugin) {
        this.plugin = plugin;
        RADIUS = plugin.getConfig().getDouble("godshield.orbit-radius", 1.8);
        SPEED  = plugin.getConfig().getDouble("godshield.orbit-speed",  0.04);
        HEIGHT = plugin.getConfig().getDouble("godshield.orbit-height", 0.85);
    }

    void startTask() {
        orbitTask = new BukkitRunnable() {
            @Override public void run() {
                for (UUID id : new ArrayList<>(orbitMap.keySet())) {
                    Player p = plugin.getServer().getPlayer(id);
                    if (p == null || !p.isOnline()) { removeOrbit(id); continue; }
                    tickOrbit(p);
                }
            }
        };
        orbitTask.runTaskTimer(plugin, 0L, 1L);
    }

    private void tickOrbit(Player player) {
        double angle = angleMap.getOrDefault(player.getUniqueId(), 0.0);
        List<ItemDisplay> shields = orbitMap.get(player.getUniqueId());
        if (shields == null) return;

        org.bukkit.Location base = player.getLocation();

        for (int i = 0; i < shields.size(); i++) {
            ItemDisplay display = shields.get(i);
            if (!display.isValid()) { removeOrbit(player.getUniqueId()); return; }

            double theta = angle + (2.0 * Math.PI / shields.size()) * i;
            double x = base.getX() + RADIUS * Math.cos(theta);
            double y = base.getY() + HEIGHT;
            double z = base.getZ() + RADIUS * Math.sin(theta);

            org.bukkit.Location loc = new org.bukkit.Location(
                base.getWorld(), x, y, z, (float) Math.toDegrees(-theta), 0f);
            display.teleport(loc);

            display.setTransformation(new Transformation(
                new Vector3f(0, 0, 0),
                new AxisAngle4f((float)(angle * 2f), 0, 1, 0),
                new Vector3f(0.65f, 0.65f, 0.65f),
                new AxisAngle4f((float) Math.toRadians(25), 1, 0, 0)
            ));
        }
        angleMap.put(player.getUniqueId(), angle + SPEED);
    }

    void addOrbit(Player player) {
        if (orbitMap.containsKey(player.getUniqueId())) return;
        List<ItemDisplay> displays = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            double theta = (2.0 * Math.PI / 4.0) * i;
            double x = player.getLocation().getX() + RADIUS * Math.cos(theta);
            double y = player.getLocation().getY() + HEIGHT;
            double z = player.getLocation().getZ() + RADIUS * Math.sin(theta);
            org.bukkit.Location spawnLoc = new org.bukkit.Location(player.getWorld(), x, y, z);

            ItemDisplay display = player.getWorld().spawn(spawnLoc, ItemDisplay.class, d -> {
                d.setItemStack(GodShieldItem.create());
                d.setPersistent(false);
                d.setInvulnerable(true);
                d.setGravity(false);
                d.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new AxisAngle4f(0, 0, 1, 0),
                    new Vector3f(0.65f, 0.65f, 0.65f),
                    new AxisAngle4f((float) Math.toRadians(25), 1, 0, 0)
                ));
            });
            displays.add(display);
        }
        orbitMap.put(player.getUniqueId(), displays);
        angleMap.put(player.getUniqueId(), 0.0);
    }

    void removeOrbit(UUID id) {
        List<ItemDisplay> d = orbitMap.remove(id);
        if (d != null) d.forEach(e -> { if (e.isValid()) e.remove(); });
        angleMap.remove(id);
    }

    void removeOrbit(Player player) { removeOrbit(player.getUniqueId()); }
    boolean hasOrbit(Player player)  { return orbitMap.containsKey(player.getUniqueId()); }

    void cleanup() {
        if (orbitTask != null) { try { orbitTask.cancel(); } catch (Exception ignored) {} }
        orbitMap.values().forEach(list -> list.forEach(d -> { if (d.isValid()) d.remove(); }));
        orbitMap.clear();
        angleMap.clear();
    }
}

// ════════════════════════════════════════════════════════
//  ShieldManager  –  Active player tracking + passive effects
// ════════════════════════════════════════════════════════
class ShieldManager {

    private final GodShield plugin;
    private final Set<UUID> activePlayers = Collections.synchronizedSet(new HashSet<>());

    ShieldManager(GodShield plugin) {
        this.plugin = plugin;
        // Re-apply effects every 1.5 s so they appear infinite
        new BukkitRunnable() {
            @Override public void run() {
                for (UUID id : new HashSet<>(activePlayers)) {
                    Player p = plugin.getServer().getPlayer(id);
                    if (p == null || !p.isOnline()) { activePlayers.remove(id); continue; }
                    applyEffects(p);
                }
            }
        }.runTaskTimer(plugin, 0L, 30L);
    }

    void activate(Player player) {
        if (activePlayers.contains(player.getUniqueId())) return;
        activePlayers.add(player.getUniqueId());
        plugin.getOrbitManager().addOrbit(player);
        applyEffects(player);
        player.sendActionBar(Component.text("⚔ God Shield  ACTIVATED").color(NamedTextColor.GOLD));
    }

    void deactivate(Player player) {
        if (!activePlayers.contains(player.getUniqueId())) return;
        activePlayers.remove(player.getUniqueId());
        plugin.getOrbitManager().removeOrbit(player);
        removeEffects(player);
        if (player.isOnline())
            player.sendActionBar(Component.text("⚔ God Shield  deactivated").color(NamedTextColor.GRAY));
    }

    private void applyEffects(Player p) {
        final int DUR = 80; // 4s, refreshed every 1.5s = always active
        p.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST,   DUR, 3, true, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,   DUR, 3, true, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,       DUR, 3, true, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,DUR, 0, true, false, false));
    }

    private void removeEffects(Player p) {
        p.removePotionEffect(PotionEffectType.HEALTH_BOOST);
        p.removePotionEffect(PotionEffectType.REGENERATION);
        p.removePotionEffect(PotionEffectType.STRENGTH);
        p.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
    }

    boolean isActive(Player player) { return activePlayers.contains(player.getUniqueId()); }
    boolean isActive(UUID id)       { return activePlayers.contains(id); }
}

// ════════════════════════════════════════════════════════
//  AbilityManager  –  Cooldown enforcement + dispatch
// ════════════════════════════════════════════════════════
class AbilityManager {

    private final GodShield plugin;
    final ShockwaveAbility   shockwave;
    final PrisonAbility      prison;
    final MindControlAbility mindControl;

    private final Map<UUID, Long> cdShockwave   = new HashMap<>();
    private final Map<UUID, Long> cdPrison      = new HashMap<>();
    private final Map<UUID, Long> cdMindControl = new HashMap<>();

    AbilityManager(GodShield plugin) {
        this.plugin      = plugin;
        this.shockwave   = new ShockwaveAbility(plugin);
        this.prison      = new PrisonAbility(plugin);
        this.mindControl = new MindControlAbility(plugin);
    }

    void useShockwave(Player player) {
        if (onCooldown(player, cdShockwave, "shockwave", "⚡ Shockwave")) return;
        setCooldown(player, cdShockwave, "shockwave");
        shockwave.execute(player);
    }

    void usePrison(Player player, org.bukkit.entity.LivingEntity target) {
        if (prison.isImprisoned(target.getUniqueId())) {
            player.sendActionBar(Component.text("🔒 Target already imprisoned!").color(NamedTextColor.DARK_AQUA));
            return;
        }
        if (onCooldown(player, cdPrison, "prison", "🔒 Prison")) return;
        setCooldown(player, cdPrison, "prison");
        prison.execute(player, target);
    }

    void useMindControl(Player player, org.bukkit.entity.LivingEntity target) {
        if (onCooldown(player, cdMindControl, "mind-control", "👁 Mind Control")) return;
        setCooldown(player, cdMindControl, "mind-control");
        mindControl.execute(player, target);
    }

    /** Returns true if still on cooldown and notifies player */
    private boolean onCooldown(Player p, Map<UUID, Long> map, String key, String name) {
        long rem = (map.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis()) / 1000L;
        if (rem > 0) {
            p.sendActionBar(Component.text(name + " Cooldown: " + rem + "s  ⏳").color(NamedTextColor.RED));
            return true;
        }
        return false;
    }

    private void setCooldown(Player p, Map<UUID, Long> map, String key) {
        long ms = plugin.getConfig().getLong("abilities." + key + ".cooldown", 15L) * 1000L;
        map.put(p.getUniqueId(), System.currentTimeMillis() + ms);
    }

    ShockwaveAbility   getShockwave()   { return shockwave; }
    PrisonAbility      getPrison()      { return prison; }
    MindControlAbility getMindControl() { return mindControl; }
}

// ════════════════════════════════════════════════════════
//  CraftManager  –  Recipe & one-time Mace lock
// ════════════════════════════════════════════════════════
class CraftManager {

    private final GodShield    plugin;
    private       NamespacedKey recipeKey;
    private       boolean       maceCrafted;

    CraftManager(GodShield plugin) {
        this.plugin      = plugin;
        this.maceCrafted = plugin.getConfig().getBoolean("data.mace-crafted", false);
    }

    void registerRecipe() {
        recipeKey = new NamespacedKey(plugin, "god_shield_recipe");
        plugin.getServer().removeRecipe(recipeKey);

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, GodShieldItem.create());
        recipe.shape("DMD", "NSN", "WEW");

        Material wood    = parse("recipe.wood",            Material.OAK_LOG);
        Material neth    = parse("recipe.netherite-ingot", Material.NETHERITE_INGOT);
        Material diamond = parse("recipe.diamond-block",   Material.DIAMOND_BLOCK);

        recipe.setIngredient('D', diamond);
        recipe.setIngredient('M', Material.MACE);
        recipe.setIngredient('N', neth);
        recipe.setIngredient('S', Material.SHIELD);
        recipe.setIngredient('W', wood);
        recipe.setIngredient('E', Material.DRAGON_EGG);

        plugin.getServer().addRecipe(recipe);
    }

    private Material parse(String path, Material def) {
        String val = plugin.getConfig().getString(path);
        if (val == null) return def;
        Material m = Material.matchMaterial(val.toUpperCase());
        return (m != null) ? m : def;
    }

    boolean      isMaceCrafted()        { return maceCrafted; }
    NamespacedKey getRecipeKey()         { return recipeKey; }

    void setMaceCrafted(boolean crafted) {
        maceCrafted = crafted;
        plugin.getConfig().set("data.mace-crafted", crafted);
        plugin.saveConfig();
    }
                             }
