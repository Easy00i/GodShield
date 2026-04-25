package com.ancienteye.godshield;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

// ════════════════════════════════════════════════════════
//  OrbitManager  –  6 Shields, OUTWARD Facing, Floating High
// ════════════════════════════════════════════════════════
class OrbitManager {

    private final GodShield plugin;
    private final Map<UUID, List<ItemDisplay>> orbitMap = new HashMap<>();
    private final Map<UUID, Double>            angleMap = new HashMap<>();
    private BukkitRunnable orbitTask;

    // ── FIXED CONSTANTS ───────────────────────────────────────────
    private static final int    SHIELD_COUNT  = 6;
    private static final double RADIUS        = 1.35;     // Player se perfect doori
    private static final double SPEED         = 0.022;    // Smooth orbit speed
    private static final double HEIGHT        = 1.5;      // Zameen se upar hawa mein (No ground clipping)
    private static final float  SCALE         = 1.15f;    // Shield size
    private static final double BOB_AMPLITUDE = 0.1;      // Floating animation
    private static final double BOB_SPEED     = 2.0;

    OrbitManager(GodShield plugin) {
        this.plugin = plugin;
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
        double angle  = angleMap.getOrDefault(player.getUniqueId(), 0.0);
        List<ItemDisplay> shields = orbitMap.get(player.getUniqueId());
        if (shields == null) return;

        org.bukkit.Location base = player.getLocation();
        double timeS = System.currentTimeMillis() / 1000.0;

        for (int i = 0; i < shields.size(); i++) {
            ItemDisplay display = shields.get(i);
            if (!display.isValid()) continue;

            double theta = angle + (2.0 * Math.PI / shields.size()) * i;
            double bob = BOB_AMPLITUDE * Math.sin(timeS * BOB_SPEED + i * (Math.PI / 3.0));

            double x = base.getX() + RADIUS * Math.cos(theta);
            double y = base.getY() + HEIGHT + bob;
            double z = base.getZ() + RADIUS * Math.sin(theta);

            display.teleport(new org.bukkit.Location(base.getWorld(), x, y, z));

            // ── OUTWARD FACING ROTATION FIXED ───────────────────
            // 180 degree flip kar diya gaya hai taaki shield ka front bahar rahe
            float faceYaw = (float) Math.toDegrees(theta + Math.PI / 2.0);
            
            display.setTransformation(new Transformation(
                new Vector3f(0f, 0f, 0f),                   
                new AxisAngle4f((float)Math.toRadians(-faceYaw), 0f, 1f, 0f), 
                new Vector3f(SCALE, SCALE, SCALE),           
                new AxisAngle4f(0f, 0f, 1f, 0f)             
            ));
        }
        angleMap.put(player.getUniqueId(), angle + SPEED);
    }

    void addOrbit(Player player) {
        if (orbitMap.containsKey(player.getUniqueId())) return;
        List<ItemDisplay> displays = new ArrayList<>();
        
        for (int i = 0; i < SHIELD_COUNT; i++) {
            org.bukkit.Location spawnLoc = player.getLocation().add(0, HEIGHT, 0);

            ItemDisplay display = player.getWorld().spawn(spawnLoc, ItemDisplay.class, d -> {
                d.setItemStack(GodShieldItem.create());
                d.setPersistent(false);
                d.setInvulnerable(true);
                d.setGravity(false);
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

    void removeOrbit(Player player)   { removeOrbit(player.getUniqueId()); }
    boolean hasOrbit(Player player)   { return orbitMap.containsKey(player.getUniqueId()); }

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
        final int DUR = 80;
        p.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST,    DUR, 3, true, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,    DUR, 3, true, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,        DUR, 3, true, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, DUR, 0, true, false, false));
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

    void usePrison(Player player, LivingEntity target) {
        if (prison.isImprisoned(target.getUniqueId())) {
            player.sendActionBar(Component.text("🔒 Target already imprisoned!").color(NamedTextColor.DARK_AQUA));
            return;
        }
        if (onCooldown(player, cdPrison, "prison", "🔒 Prison")) return;
        setCooldown(player, cdPrison, "prison");
        prison.execute(player, target);
    }

    void useMindControl(Player player, LivingEntity target) {
        if (onCooldown(player, cdMindControl, "mind-control", "👁 Mind Control")) return;
        setCooldown(player, cdMindControl, "mind-control");
        mindControl.execute(player, target);
    }

    void refreshMindControl(Player player, LivingEntity target) {
        mindControl.refreshOnHit(player, target);
    }

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

    private final GodShield     plugin;
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

    boolean       isMaceCrafted() { return maceCrafted; }
    NamespacedKey getRecipeKey()  { return recipeKey; }

    void setMaceCrafted(boolean crafted) {
        maceCrafted = crafted;
        plugin.getConfig().set("data.mace-crafted", crafted);
        plugin.saveConfig();
    }
}

// ════════════════════════════════════════════════════════
//  RitualManager  –  Tracks all active God Shield rituals
// ════════════════════════════════════════════════════════
class RitualManager {

    private final GodShield plugin;
    private final Map<String, ActiveRitual> rituals = new HashMap<>();

    RitualManager(GodShield plugin) { this.plugin = plugin; }

    void startRitual(Player crafter, Location tableLoc) {
        // FIX: Ensure exact block center so shield doesn't spawn on a corner
        Location centeredLoc = tableLoc.getBlock().getLocation();
        String key = makeKey(centeredLoc);
        
        if (rituals.containsKey(key)) {
            crafter.sendMessage(Component.text("⚠ A ritual is already active here!").color(NamedTextColor.RED));
            return;
        }
        
        ActiveRitual ritual = new ActiveRitual(plugin, this, crafter.getUniqueId(), centeredLoc, key);
        rituals.put(key, ritual);
        ritual.start();
    }

    void onPlayerJoin(Player player) {
        for (ActiveRitual r : rituals.values()) {
            if (r.crafterUUID.equals(player.getUniqueId())) {
                r.attachBossBar(player);
                player.sendMessage(Component.text("⚔ Your God Shield ritual is still active!").color(NamedTextColor.GOLD));
            }
        }
    }

    void removeRitual(String key) { rituals.remove(key); }
    boolean isActive(Location loc) { return rituals.containsKey(makeKey(loc.getBlock().getLocation())); }

    void cleanup() {
        new ArrayList<>(rituals.values()).forEach(ActiveRitual::forceEnd);
        rituals.clear();
    }

    static String makeKey(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}

// ════════════════════════════════════════════════════════
//  ActiveRitual  –  Full Cinematic Animation (CRASH PROOF)
// ════════════════════════════════════════════════════════
class ActiveRitual {

    final UUID crafterUUID;
    private final GodShield     plugin;
    private final RitualManager manager;
    private final Location       tableLoc;   
    private final String         key;

    private ItemDisplay shieldDisplay;
    private BukkitRunnable mainTask;
    private BukkitRunnable ghostTask;
    private BossBar bossBar;
    
    private boolean bossBarStarted   = false;
    private long    bossBarStartTime  = 0;
    private static final long RITUAL_MS = 10L * 60L * 1000L; // 10 minutes

    private int     tick        = 0;
    private boolean phase3Active = false;
    private boolean completed    = false;
    private double  shieldCurrentY;
    private double  cubeAngle    = 0;
    private boolean cubeVisible  = false;
    private float   shieldYaw    = 0f;

    private static final double SHIELD_RISE   = 20.0;  
    private static final float  SHIELD_SCALE  = 3.0f;  
    private static final int    RISE_TICKS    = 160;   

    ActiveRitual(GodShield plugin, RitualManager manager, UUID crafterUUID, Location tableLoc, String key) {
        this.plugin       = plugin;
        this.manager      = manager;
        this.crafterUUID  = crafterUUID;
        this.tableLoc     = tableLoc.clone();
        this.key          = key;
        // Exact center block + 1.5 height
        this.shieldCurrentY = tableLoc.getY() + 1.5;
    }

    void start() {
        Location center = tableLoc.clone().add(0.5, 1.5, 0.5);
        World world = tableLoc.getWorld();

        shieldDisplay = world.spawn(center, ItemDisplay.class, d -> {
            d.setItemStack(GodShieldItem.create());
            d.setGlowing(true);
            d.setPersistent(true);
            d.setInvulnerable(true);
            d.setGravity(false);
            d.setInterpolationDuration(1); // Ensures smooth motion
            d.setTransformation(new Transformation(
                new Vector3f(0f, 0f, 0f),
                new AxisAngle4f(0f, 0f, 1f, 0f),
                new Vector3f(SHIELD_SCALE, SHIELD_SCALE, SHIELD_SCALE),
                new AxisAngle4f(0f, 0f, 1f, 0f)
            ));
        });

        Player crafter = Bukkit.getPlayer(crafterUUID);
        if (crafter != null) {
            crafter.sendMessage(Component.text("⚔ The God Shield ritual has begun!").color(NamedTextColor.GOLD));
            crafter.sendMessage(Component.text("  The shield will be ready in 10 minutes.").color(NamedTextColor.YELLOW));
        }

        world.spawnParticle(Particle.FLASH, center, 3);
        world.spawnParticle(Particle.END_ROD, center, 80, 0.3, 0.3, 0.3, 0.3);
        world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 2.0f, 0.4f);
        world.playSound(center, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.0f, 0.3f);

        startMainTask();
    }
    
    private void startMainTask() {
        Location tableCenter = tableLoc.clone().add(0.5, 0.5, 0.5);
        World world = tableLoc.getWorld();
        double startY = tableLoc.getY() + 1.5;

        mainTask = new BukkitRunnable() {
            @Override public void run() {
                try { // CRASH-PROOF TRY CATCH
                    if (completed) { cancel(); return; }
                    if (shieldDisplay == null || !shieldDisplay.isValid()) respawnDisplay();

                    shieldYaw += 0.03f; // Smooth spin

                    // ── Phase 1: Beam + Shield rises (0→160 ticks = 8s) ──
                    if (tick <= RISE_TICKS) {
                        double prog = (double) tick / RISE_TICKS;
                        shieldCurrentY = startY + prog * SHIELD_RISE;

                        Location shieldLoc = new Location(world, tableCenter.getX(), shieldCurrentY, tableCenter.getZ());
                        moveDisplay(shieldLoc);
                        drawBeam(world, tableCenter, shieldLoc, tick);

                        if (tick % 20 == 0)
                            world.playSound(tableCenter, Sound.BLOCK_BEACON_AMBIENT, 0.6f, 0.4f + (float)tick / RISE_TICKS * 0.8f);
                    }

                    // ── Transition: Shield holds at peak (160→200) ────────
                    if (tick > RISE_TICKS && tick < 200) {
                        Location peak = new Location(world, tableCenter.getX(), startY + SHIELD_RISE, tableCenter.getZ());
                        moveDisplay(peak);
                        drawBeam(world, tableCenter, peak, tick);
                    }

                        // ── Phase 2: Cube forms at tick 200 (10s) ─────────────
                    if (tick == 200) {
                        cubeVisible = true;
                        world.spawnParticle(Particle.FLASH, shieldDisplay.getLocation(), 5);
                        world.spawnParticle(Particle.END_ROD, shieldDisplay.getLocation(), 100, 1.0, 1.0, 1.0, 0.3);
                        world.playSound(tableCenter, Sound.ENTITY_WARDEN_SONIC_CHARGE, 2.0f, 0.5f);
                        world.playSound(tableCenter, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, 0.6f);
                    }

                    if (cubeVisible) {
                        Location shieldLoc = new Location(world, tableCenter.getX(), startY + SHIELD_RISE, tableCenter.getZ());
                        moveDisplay(shieldLoc);
                        drawBeam(world, tableCenter, shieldLoc, tick);
                        drawCube(world, shieldLoc, cubeAngle, 1.0);
                        cubeAngle += 0.05;
                    }

                    // ── Phase 3: Ghost particles start at tick 220 (11s) ──
                    if (tick == 220) {
                        phase3Active = true;
                        startGhostParticles();
                        startBossBar();
                    }

                    // ── Update boss bar + check completion ────────────────
                    if (bossBarStarted) {
                        long elapsed   = System.currentTimeMillis() - bossBarStartTime;
                        long remaining = RITUAL_MS - elapsed;

                        if (remaining <= 0) {
                            cancel();
                            beginCompletion();
                            return;
                        }

                        double prog = (double) remaining / RITUAL_MS;
                        bossBar.setProgress(Math.max(0, Math.min(1, prog)));
                        long s = remaining / 1000, min = s / 60, sec = s % 60;
                        bossBar.setTitle("⚔ God Shield Ritual  ─  " + min + "m " + String.format("%02d", sec) + "s");
                    }

                    tick++;
                } catch (Exception e) {
                    // Agar koi particle version error de, animation cancel nahi hoga.
                    e.printStackTrace(); 
                    tick++;
                }
            }
        };
        mainTask.runTaskTimer(plugin, 0L, 1L);
    }

    private void drawBeam(World world, Location bottom, Location top, int t) {
        double dx = top.getX() - bottom.getX(), dy = top.getY() - bottom.getY(), dz = top.getZ() - bottom.getZ();
        double len = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (len < 0.01) return;

        Particle.DustOptions blackCore  = new Particle.DustOptions(Color.fromRGB(10, 10, 10),  2.5f);
        Particle.DustOptions blackOuter = new Particle.DustOptions(Color.fromRGB(30, 30, 40),  1.5f);
        int pts = (int)(len * 3);

        for (int p = 0; p < pts; p++) {
            double ft = (double) p / pts;
            double sx = Math.sin(t * 0.25 + p * 0.4) * 0.05, sz = Math.cos(t * 0.25 + p * 0.6) * 0.05;
            Location L = new Location(world, bottom.getX() + dx*ft + sx, bottom.getY() + dy*ft, bottom.getZ() + dz*ft + sz);
            world.spawnParticle(Particle.DUST, L, 1, 0, 0, 0, 0, blackCore);
            if (p % 2 == 0) world.spawnParticle(Particle.DUST, L, 1, 0.04, 0.04, 0.04, 0, blackOuter);
        }
        for (int p = 0; p < pts; p += 3) {
            double ft = (double) p / pts;
            world.spawnParticle(Particle.SQUID_INK, new Location(world, bottom.getX() + dx*ft, bottom.getY() + dy*ft, bottom.getZ() + dz*ft), 1, 0.03, 0.0, 0.03, 0);
        }
    }

    private void drawCube(World world, Location center, double angle, double sizeScale) {
        double h = 2.5 * sizeScale;
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double[][] c = {{-h,-h,-h},{h,-h,-h},{h,-h,h},{-h,-h,h},{-h, h,-h},{h, h,-h},{h, h,h},{-h, h,h}};
        
        for (double[] v : c) {
            double nx = v[0]*cos - v[2]*sin, nz = v[0]*sin + v[2]*cos;
            v[0] = nx; v[2] = nz;
        }
        int[][] edges = {{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}};
        
        float edgeSize = (float)(2.2 * sizeScale), glowSize = (float)(1.3 * sizeScale);
        Particle.DustOptions redEdge = new Particle.DustOptions(Color.fromRGB(220, 0, 0), edgeSize);
        Particle.DustOptions redGlow = new Particle.DustOptions(Color.fromRGB(255, 100, 100), glowSize);

        for (int[] edge : edges) {
            double[] a = c[edge[0]], b = c[edge[1]];
            for (int p = 0; p <= 16; p++) {
                double t = (double) p / 16.0;
                Location L = new Location(world, center.getX() + a[0] + t*(b[0]-a[0]), center.getY() + a[1] + t*(b[1]-a[1]), center.getZ() + a[2] + t*(b[2]-a[2]));
                world.spawnParticle(Particle.DUST, L, 2, 0.04, 0.04, 0.04, 0, redEdge);
                if (p % 4 == 0) world.spawnParticle(Particle.DUST, L, 1, 0.07, 0.07, 0.07, 0, redGlow);
            }
        }
    }

    private void startGhostParticles() {
        Location ground = tableLoc.clone().add(0.5, 0, 0.5);

        ghostTask = new BukkitRunnable() {
            int gt = 0;
            @Override public void run() {
                if (completed || !phase3Active) { cancel(); return; }
                if (gt % 20 == 0) {
                    int count = 4 + (int)(Math.random() * 2);
                    for (int i = 0; i < count; i++) launchGhost(ground, shieldDisplay.getLocation());
                }
                gt++;
            }
        };
        ghostTask.runTaskTimer(plugin, 0L, 1L);
    }

    private void launchGhost(Location groundCenter, Location target) {
        World world = groundCenter.getWorld();
        double sx = groundCenter.getX() + (Math.random() - 0.5) * 5.0;
        double sz = groundCenter.getZ() + (Math.random() - 0.5) * 5.0;
        double sy = groundCenter.getY();

        int numWP = 3 + (int)(Math.random() * 3);
        List<double[]> waypoints = new ArrayList<>();
        for (int w = 0; w < numWP; w++) {
            double prog = (double)(w + 1) / (numWP + 1);
            double wx = sx + (target.getX() - sx) * prog + (Math.random() - 0.5) * 5.0;
            double wy = sy + (target.getY() - sy) * prog * 0.65 + Math.random() * 3.5;
            double wz = sz + (target.getZ() - sz) * prog + (Math.random() - 0.5) * 5.0;
            waypoints.add(new double[]{wx, wy, wz});
        }
        waypoints.add(new double[]{target.getX(), target.getY(), target.getZ()});

        Particle.DustOptions ghostDust = new Particle.DustOptions(Color.fromRGB(190, 210, 255), 1.8f);

        new BukkitRunnable() {
            double x = sx, y = sy, z = sz;
            int wp = 0, age = 0;

            @Override public void run() {
                if (completed || age > 250) { cancel(); return; }

                double[] wpt = waypoints.get(wp);
                double dx = wpt[0]-x, dy = wpt[1]-y, dz = wpt[2]-z;
                double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);

                if (dist < 0.35) {
                    wp++;
                    if (wp >= waypoints.size()) {
                        world.spawnParticle(Particle.END_ROD, new Location(world, x, y, z), 6, 0.1, 0.1, 0.1, 0.08);
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME, new Location(world, x, y, z), 3, 0.05, 0.05, 0.05, 0.02);
                        cancel(); return;
                    }
                    wpt = waypoints.get(wp);
                    dx = wpt[0]-x; dy = wpt[1]-y; dz = wpt[2]-z;
                    dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                }

                double speed = 0.13;
                if (dist > 0.001) { x += dx/dist * speed; y += dy/dist * speed; z += dz/dist * speed; }

                Location L = new Location(world, x, y, z);
                world.spawnParticle(Particle.DUST, L, 2, 0.05, 0.05, 0.05, 0, ghostDust);
                world.spawnParticle(Particle.SOUL, L, 1, 0.04, 0.04, 0.04, 0.01);
                age++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void startBossBar() {
        bossBarStartTime = System.currentTimeMillis();
        bossBarStarted   = true;
        bossBar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SOLID);
        bossBar.setProgress(1.0);
        Player crafter = Bukkit.getPlayer(crafterUUID);
        if (crafter != null) bossBar.addPlayer(crafter);
        for (Player p : tableLoc.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(tableLoc) <= 900) bossBar.addPlayer(p);
        }
    }

    void attachBossBar(Player player) { if (bossBar != null && bossBarStarted) bossBar.addPlayer(player); }
    
    private void beginCompletion() {
        completed = true;
        phase3Active = false;
        if (ghostTask != null) { try { ghostTask.cancel(); } catch (Exception ignored) {} }
        if (bossBar   != null) bossBar.removeAll();

        World world = tableLoc.getWorld();
        Location tableCenter = tableLoc.clone().add(0.5, 0.5, 0.5);
        double peakY = tableLoc.getY() + 1.5 + SHIELD_RISE;
        double baseY = tableLoc.getY() + 1.5;

        new BukkitRunnable() {
            int ct = 0;
            double localCubeScale = 1.0;
            double completionCubeAngle = cubeAngle;

            @Override public void run() {
                try {
                    if (ct <= RISE_TICKS) {
                        double prog  = (double) ct / RISE_TICKS;
                        double newY  = peakY - (peakY - baseY) * prog;
                        localCubeScale = Math.max(0, 1.0 - prog * 1.2);

                        Location shieldLoc = new Location(world, tableCenter.getX(), newY, tableCenter.getZ());
                        if (shieldDisplay != null && shieldDisplay.isValid()) {
                            moveDisplay(shieldLoc);
                            drawBeam(world, tableCenter, shieldLoc, ct);
                        }
                        if (localCubeScale > 0.05) {
                            drawCube(world, new Location(world, tableCenter.getX(), newY, tableCenter.getZ()), completionCubeAngle, localCubeScale);
                            completionCubeAngle += 0.05;
                        }
                        if (ct % 25 == 0) world.playSound(tableCenter, Sound.BLOCK_BEACON_DEACTIVATE, 0.5f, 0.5f + (float)ct / RISE_TICKS * 0.8f);
                    }

                    if (ct == RISE_TICKS + 1) {
                        if (shieldDisplay != null && shieldDisplay.isValid()) shieldDisplay.remove();
                        Location landing = getLanding(tableCenter);
                        startLightningCircle(world, landing);

                        world.spawnParticle(Particle.EXPLOSION_EMITTER, landing, 5, 1.5, 0.3, 1.5, 0);
                        world.spawnParticle(Particle.END_ROD,           landing, 200, 2.0, 0.5, 2.0, 0.4);
                        world.spawnParticle(Particle.ELECTRIC_SPARK,    landing, 150, 1.5, 0.3, 1.5, 0.5);
                        world.playSound(landing, Sound.ENTITY_GENERIC_EXPLODE,    2.5f, 0.5f);
                        world.playSound(landing, Sound.ENTITY_WARDEN_SONIC_BOOM,  2.0f, 0.7f);
                        world.playSound(landing, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0f, 0.4f);

                        world.dropItem(landing.clone().add(0, 1, 0), GodShieldItem.create());

                        Player crafter = Bukkit.getPlayer(crafterUUID);
                        if (crafter != null) crafter.sendMessage(Component.text("⚔ God Shield ritual COMPLETE! Collect your shield.").color(NamedTextColor.GOLD));
                    }

                    if (ct >= RISE_TICKS + 120) {
                        cancel();
                        manager.removeRitual(key);
                        return;
                    }
                    shieldYaw += 0.03f;
                    ct++;
                } catch (Exception e) {
                    e.printStackTrace();
                    ct++;
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private Location getLanding(Location tableCenter) {
        Block b = tableCenter.clone().subtract(0, 0.6, 0).getBlock();
        if (b.getType() == Material.CRAFTING_TABLE) return tableCenter.clone().add(0, 0.5, 0);
        int hy = tableLoc.getWorld().getHighestBlockYAt(tableCenter.getBlockX(), tableCenter.getBlockZ());
        return new Location(tableLoc.getWorld(), tableCenter.getX(), hy + 0.5, tableCenter.getZ());
    }

    private void startLightningCircle(World world, Location center) {
        new BukkitRunnable() {
            int lt = 0;
            @Override public void run() {
                if (lt >= 100) { cancel(); return; } 
                if (lt % 2 == 0) { 
                    double angle = Math.random() * 2 * Math.PI, r = 1.0 + Math.random() * 1.5; 
                    double lx = center.getX() + r * Math.cos(angle), lz = center.getZ() + r * Math.sin(angle);
                    Location strike = world.getHighestBlockAt((int) lx, (int) lz).getLocation().add(0, 1, 0);
                    strike.setX(lx); strike.setZ(lz);
                    world.strikeLightningEffect(strike);
                }
                lt++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void moveDisplay(Location loc) {
        if (shieldDisplay == null || !shieldDisplay.isValid()) return;
        shieldDisplay.setInterpolationDuration(1);
        shieldDisplay.teleport(loc);
        shieldDisplay.setTransformation(new Transformation(
            new Vector3f(0f, 0f, 0f),
            new AxisAngle4f(shieldYaw, 0f, 1f, 0f),
            new Vector3f(SHIELD_SCALE, SHIELD_SCALE, SHIELD_SCALE),
            new AxisAngle4f(0f, 0f, 1f, 0f)
        ));
                            }

    private void respawnDisplay() {
        if (shieldDisplay != null && shieldDisplay.isValid()) shieldDisplay.remove();
        Location loc = new Location(tableLoc.getWorld(), tableLoc.getX() + 0.5, shieldCurrentY, tableLoc.getZ() + 0.5);
        shieldDisplay = tableLoc.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(GodShieldItem.create());
            d.setGlowing(true); d.setPersistent(true);
            d.setInvulnerable(true); d.setGravity(false);
            d.setInterpolationDuration(1);
            d.setTransformation(new Transformation(
                new Vector3f(0f, 0f, 0f),
                new AxisAngle4f(shieldYaw, 0f, 1f, 0f),
                new Vector3f(SHIELD_SCALE, SHIELD_SCALE, SHIELD_SCALE),
                new AxisAngle4f(0f, 0f, 1f, 0f)
            ));
        });
    }

    void forceEnd() {
        completed = true;
        if (mainTask  != null) { try { mainTask.cancel();  } catch (Exception ignored) {} }
        if (ghostTask != null) { try { ghostTask.cancel(); } catch (Exception ignored) {} }
        if (bossBar   != null) bossBar.removeAll();
        if (shieldDisplay != null && shieldDisplay.isValid()) shieldDisplay.remove();
    }
}
            
                                            
