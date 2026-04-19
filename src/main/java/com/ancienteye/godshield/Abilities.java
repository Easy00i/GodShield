package com.ancienteye.godshield;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

// ════════════════════════════════════════════════════════
//  ShockwaveAbility  –  Shift + Right-Click
//
//  Phase 1: Charge-up flash + electric sparks
//  Phase 2: Lightning tendrils crawl outward (animated per tick)
//  Phase 3: Detonation – blocks destroyed, entities one-shotted,
//           visual lightning strikes scatter across the arena
// ════════════════════════════════════════════════════════
class ShockwaveAbility {

    private final GodShield plugin;
    ShockwaveAbility(GodShield plugin) { this.plugin = plugin; }

    void execute(Player player) {
        double  radius    = plugin.getConfig().getDouble("abilities.shockwave.radius",        12.0);
        double  damage    = plugin.getConfig().getDouble("abilities.shockwave.damage",     10000.0);
        double  knockback = plugin.getConfig().getDouble("abilities.shockwave.knockback",      3.0);
        boolean breakBlk  = plugin.getConfig().getBoolean("abilities.shockwave.break-blocks", true);

        Location center = player.getEyeLocation();
        World    world  = player.getWorld();

        player.sendActionBar(Component.text("⚡⚡  SHOCKWAVE  ⚡⚡").color(NamedTextColor.YELLOW));

        // ── Phase 1: instant charge flash ─────────────────────────
        world.spawnParticle(Particle.FLASH,            center, 3);
        world.spawnParticle(Particle.END_ROD,          center, 120, 0.15, 0.15, 0.15, 0.4);
        world.spawnParticle(Particle.ELECTRIC_SPARK,   center,  80, 0.30, 0.30, 0.30, 0.3);
        world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.5f, 0.4f);
        world.playSound(center, Sound.ENTITY_WARDEN_SONIC_CHARGE,    1.5f, 0.6f);

        // ── Phase 2: animated tendrils crawl outward ──────────────
        new BukkitRunnable() {
            private int step = 0;
            private final int MAX = 18;

            @Override
            public void run() {
                if (step > MAX) {
                    cancel();
                    doDetonation(player, center, radius, damage, knockback, breakBlk);
                    return;
                }
                double progress = (double) step / MAX;
                double curR     = radius * progress;

                // Inner white glow
                world.spawnParticle(Particle.END_ROD, center, 40,
                    curR * 0.25, curR * 0.15, curR * 0.25, 0.15);
                world.spawnParticle(Particle.FLASH, center, 1);

                // Jagged tendrils
                drawLightningTendrils(world, center, curR, step);

                // Blue wavefront ring
                drawOuterRing(world, center, curR);

                if (step % 3 == 0) {
                    float pitch = 0.3f + (step / (float) MAX) * 1.4f;
                    world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.0f, pitch);
                }
                step++;
            }
        }.runTaskTimer(plugin, 2L, 1L);
    }

    // ── Jagged lightning tendrils radiating from center ───────────
    private void drawLightningTendrils(World world, Location center, double radius, int frame) {
        final int TENDRILS = 20;
        Random rng = new Random(frame * 7919L); // deterministic per frame

        Particle.DustOptions blue      = new Particle.DustOptions(Color.fromRGB( 80, 130, 255), 1.2f);
        Particle.DustOptions lightBlue = new Particle.DustOptions(Color.fromRGB(160, 200, 255), 0.8f);

        for (int t = 0; t < TENDRILS; t++) {
            double baseAngle  = (2.0 * Math.PI / TENDRILS) * t;
            double jitter     = (rng.nextDouble() - 0.5) * 0.35;
            double angle      = baseAngle + jitter;
            double vertBias   = (rng.nextDouble() - 0.5) * 0.4;

            double prevX = center.getX(), prevY = center.getY(), prevZ = center.getZ();
            final int SEGS = 10;

            for (int s = 1; s <= SEGS; s++) {
                double segProg = (double) s / SEGS;
                double segR    = radius * segProg;
                double jagX    = (rng.nextDouble() - 0.5) * 0.6 * (1 - segProg);
                double jagY    = (rng.nextDouble() - 0.5) * 0.4 * (1 - segProg) + vertBias * segProg;
                double jagZ    = (rng.nextDouble() - 0.5) * 0.6 * (1 - segProg);

                double nx = center.getX() + segR * Math.cos(angle) + jagX;
                double ny = center.getY() + jagY;
                double nz = center.getZ() + segR * Math.sin(angle) + jagZ;

                // Fill between prev and current
                double dx = nx - prevX, dy = ny - prevY, dz = nz - prevZ;
                double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                int fill = Math.max(2, (int)(dist * 4));
                for (int f = 0; f <= fill; f++) {
                    double ft = (double) f / fill;
                    Location L = new Location(world,
                        prevX + dx*ft, prevY + dy*ft, prevZ + dz*ft);
                    world.spawnParticle(Particle.END_ROD,        L, 1, 0.02, 0.02, 0.02, 0);
                    if (f % 2 == 0)
                        world.spawnParticle(Particle.ELECTRIC_SPARK, L, 1, 0.05, 0.05, 0.05, 0);
                }
                Location segLoc = new Location(world, nx, ny, nz);
                world.spawnParticle(Particle.DUST, segLoc, 3, 0.08, 0.08, 0.08, 0, blue);
                world.spawnParticle(Particle.DUST, segLoc, 2, 0.12, 0.12, 0.12, 0, lightBlue);

                prevX = nx; prevY = ny; prevZ = nz;
            }
        }
    }

    // ── Blue wavefront ring ────────────────────────────────────────
    private void drawOuterRing(World world, Location center, double radius) {
        Particle.DustOptions outerDust = new Particle.DustOptions(Color.fromRGB(60, 100, 240), 1.5f);
        final int PTS = 48;
        for (int i = 0; i < PTS; i++) {
            double angle = (2.0 * Math.PI / PTS) * i;
            double yOff  = Math.sin(angle * 3) * 0.25;
            Location L = new Location(world,
                center.getX() + radius * Math.cos(angle),
                center.getY() + yOff,
                center.getZ() + radius * Math.sin(angle));
            world.spawnParticle(Particle.DUST, L, 1, 0, 0, 0, 0, outerDust);
            if (i % 4 == 0)
                world.spawnParticle(Particle.END_ROD, L, 1, 0.05, 0.05, 0.05, 0.05);
        }
    }

    // ── Final detonation ──────────────────────────────────────────
    private void doDetonation(Player player, Location center, double radius,
                               double damage, double knockback, boolean breakBlk) {
        World world = center.getWorld();

        Particle.DustOptions bigBlue   = new Particle.DustOptions(Color.fromRGB( 80, 130, 255), 3.0f);
        Particle.DustOptions whiteCore = new Particle.DustOptions(Color.WHITE, 2.5f);

        world.spawnParticle(Particle.FLASH,          center, 12);
        world.spawnParticle(Particle.END_ROD,        center, 600, radius/2.5, 0.8, radius/2.5, 0.25);
        world.spawnParticle(Particle.ELECTRIC_SPARK, center, 400, radius/2.0, 0.6, radius/2.0, 0.5);
        world.spawnParticle(Particle.DUST,           center, 500, radius/2.2, 0.4, radius/2.2, 0, bigBlue);
        world.spawnParticle(Particle.DUST,           center, 200, 1.5,        0.3, 1.5,        0, whiteCore);

        world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 5.0f, 0.25f);
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE,        4.0f, 0.40f);
        world.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM,      3.0f, 0.50f);

        // Scatter visual lightning strikes
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                if (i++ >= 10) { cancel(); return; }
                double lx = center.getX() + (Math.random() - 0.5) * radius * 2.2;
                double lz = center.getZ() + (Math.random() - 0.5) * radius * 2.2;
                Location L = world.getHighestBlockAt((int) lx, (int) lz).getLocation().add(0, 1, 0);
                L.setX(lx); L.setZ(lz);
                world.strikeLightningEffect(L);
            }
        }.runTaskTimer(plugin, 0L, 2L);

        // Damage entities
        for (Entity e : world.getNearbyEntities(center, radius, radius * 0.7, radius)) {
            if (e.equals(player) || !(e instanceof LivingEntity living)) continue;
            living.damage(damage, player);
            Vector kb = e.getLocation().toVector().subtract(center.toVector()).setY(0);
            if (kb.lengthSquared() > 0.001)
                e.setVelocity(kb.normalize().multiply(knockback).setY(0.8));
        }

        // Destroy blocks in batches
        if (breakBlk) destroyBlocks(center, radius, world);
    }

    private void destroyBlocks(Location center, double radius, World world) {
        int r = (int) radius, r2 = r * r, yR = (int)(radius * 0.5);
        List<Block> toBreak = new ArrayList<>();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x*x + z*z > r2) continue;
                for (int y = -yR; y <= yR; y++) {
                    Block b = center.clone().add(x, y, z).getBlock();
                    Material m = b.getType();
                    if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR
                            || m == Material.BEDROCK || m == Material.BARRIER
                            || m == Material.END_PORTAL_FRAME || m.name().contains("PORTAL")) continue;
                    toBreak.add(b);
                }
            }
        }
        int batch = Math.max(1, toBreak.size() / 10);
        new BukkitRunnable() {
            int idx = 0;
            @Override public void run() {
                for (int i = 0; i < batch && idx < toBreak.size(); i++, idx++)
                    toBreak.get(idx).breakNaturally();
                if (idx >= toBreak.size()) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}

// ════════════════════════════════════════════════════════
//  PrisonAbility  –  Left-Click on Entity
//
//  ▸ Spinning particle cube traps target
//  ▸ Target velocity zeroed every tick (no escape)
//  ▸ 5 hearts damage per second for 10 seconds
//  ▸ Anime VFX: cyan cube edges + purple inner vortex
// ════════════════════════════════════════════════════════
class PrisonAbility {

    private final GodShield plugin;
    private final Map<UUID, BukkitRunnable> active = new HashMap<>();

    PrisonAbility(GodShield plugin) { this.plugin = plugin; }

    void execute(Player caster, LivingEntity target) {
        if (active.containsKey(target.getUniqueId())) return;

        int    duration  = plugin.getConfig().getInt(   "abilities.prison.duration",          10);
        double dmgPerSec = plugin.getConfig().getDouble("abilities.prison.damage-per-second", 10.0);
        double halfSize  = plugin.getConfig().getDouble("abilities.prison.size",               1.4);

        Location loc  = target.getLocation();
        World    world = loc.getWorld();

        // Activation burst
        world.spawnParticle(Particle.FLASH,    loc.clone().add(0,1,0), 2);
        world.spawnParticle(Particle.END_ROD,  loc.clone().add(0,1,0), 60, 0.5, 0.5, 0.5, 0.2);
        world.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT,   1.5f, 0.3f);
        world.playSound(loc, Sound.BLOCK_CHAIN_PLACE,          2.0f, 0.5f);
        world.playSound(loc, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.0f, 1.2f);

        if (caster != null)
            caster.sendActionBar(Component.text("🔒  PRISON ACTIVATED  🔒").color(NamedTextColor.DARK_AQUA));

        BukkitRunnable task = new BukkitRunnable() {
            int    ticks     = 0;
            int    dmgTimer  = 0;
            double spin      = 0;
            final  int total = duration * 20;

            @Override public void run() {
                if (ticks >= total || !target.isValid() || target.isDead()) {
                    cancel();
                    active.remove(target.getUniqueId());
                    releaseBurst(target.getLocation().clone().add(0,1,0));
                    return;
                }
                // Freeze
                target.setVelocity(new Vector(0, 0, 0));

                Location c = target.getLocation().clone().add(0, halfSize, 0);
                drawCube(c, halfSize, spin);
                drawVortex(c, spin);
                spin += 0.06;

                // Damage every second
                if (++dmgTimer >= 20) {
                    dmgTimer = 0;
                    target.damage(dmgPerSec, caster);
                    world.spawnParticle(Particle.DAMAGE_INDICATOR, c, 8, 0.4, 0.4, 0.4, 0);
                    world.playSound(c, Sound.ENTITY_PLAYER_HURT, 1f, 1.4f);
                }
                if (ticks % 40 == 0)
                    world.playSound(c, Sound.BLOCK_CHAIN_STEP, 0.8f, 0.7f);
                ticks++;
            }
        };
        active.put(target.getUniqueId(), task);
        task.runTaskTimer(plugin, 0L, 1L);
    }

    // ── Rotating cube edges ───────────────────────────────────────
    private void drawCube(Location center, double h, double angle) {
        World world = center.getWorld();
        double cos = Math.cos(angle), sin = Math.sin(angle);

        double[][] c = {
            {-h,-h,-h},{h,-h,-h},{h,-h,h},{-h,-h,h},
            {-h, h,-h},{h, h,-h},{h, h,h},{-h, h,h}
        };
        for (double[] v : c) {
            double nx =  v[0]*cos - v[2]*sin;
            double nz =  v[0]*sin + v[2]*cos;
            v[0] = nx; v[2] = nz;
        }
        int[][] edges = {
            {0,1},{1,2},{2,3},{3,0},
            {4,5},{5,6},{6,7},{7,4},
            {0,4},{1,5},{2,6},{3,7}
        };
        Particle.DustOptions cyan  = new Particle.DustOptions(Color.fromRGB(  0, 230, 230), 0.9f);
        Particle.DustOptions teal  = new Particle.DustOptions(Color.fromRGB(  0, 150, 255), 1.2f);
        Particle.DustOptions white = new Particle.DustOptions(Color.WHITE,                  0.6f);

        for (int[] edge : edges) {
            double[] a = c[edge[0]], b = c[edge[1]];
            for (int p = 0; p <= 10; p++) {
                double t  = p / 10.0;
                Location L = new Location(world,
                    center.getX() + a[0] + t*(b[0]-a[0]),
                    center.getY() + a[1] + t*(b[1]-a[1]),
                    center.getZ() + a[2] + t*(b[2]-a[2]));
                world.spawnParticle(Particle.DUST, L, 1, 0,0,0,0, cyan);
                if (p == 0 || p == 10 || p == 5) {
                    world.spawnParticle(Particle.END_ROD, L, 2, 0.03,0.03,0.03,0);
                    world.spawnParticle(Particle.DUST, L, 2, 0,0,0,0, teal);
                }
                if (Math.random() < 0.2)
                    world.spawnParticle(Particle.DUST, L, 1, 0,0,0,0, white);
            }
        }
    }

    // ── Inner purple/violet vortex ────────────────────────────────
    private void drawVortex(Location center, double angle) {
        World world = center.getWorld();
        Particle.DustOptions purple = new Particle.DustOptions(Color.fromRGB(160,   0, 255), 0.9f);
        Particle.DustOptions pink   = new Particle.DustOptions(Color.fromRGB(255,  80, 255), 0.7f);

        for (int i = 0; i < 24; i++) {
            double t     = i / 24.0;
            double r     = 0.9 * t;
            double theta = angle * 4 + t * 4.5 * Math.PI;
            Location L   = new Location(world,
                center.getX() + r * Math.cos(theta),
                center.getY() - 1.0 + t * 2.0,
                center.getZ() + r * Math.sin(theta));
            world.spawnParticle(Particle.DUST, L, 1, 0,0,0,0, purple);
            if (i % 4 == 0) {
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, L, 1, 0.05,0.05,0.05,0.01);
                world.spawnParticle(Particle.DUST, L, 1, 0,0,0,0, pink);
            }
        }
    }

    // ── Release burst when time expires ──────────────────────────
    private void releaseBurst(Location loc) {
        World world = loc.getWorld();
        world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 2, 0.3,0.3,0.3,0);
        world.spawnParticle(Particle.END_ROD, loc, 50, 0.7,0.7,0.7, 0.4);
        world.spawnParticle(Particle.DUST, loc, 60, 0.6,0.6,0.6,0,
            new Particle.DustOptions(Color.fromRGB(0,230,230), 2.0f));
        world.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.8f);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE,   1.2f, 1.5f);
    }

    boolean isImprisoned(UUID id) { return active.containsKey(id); }

    void release(UUID id) {
        BukkitRunnable t = active.remove(id);
        if (t != null) { try { t.cancel(); } catch (Exception ignored) {} }
    }
}

// ════════════════════════════════════════════════════════
//  MindControlAbility  –  Shift + Left-Click
//
//  ▸ Freezes target (velocity zeroed)
//  ▸ Disables mob AI
//  ▸ Re-applied / refreshed on every hit
//  ▸ Revolving purple crown aura above head
//  ▸ Re-enables AI and plays release burst on expiry
// ════════════════════════════════════════════════════════
class MindControlAbility {

    private final GodShield plugin;
    private final Map<UUID, UUID>           controlled = new HashMap<>(); // entity → controller
    private final Map<UUID, BukkitRunnable> tasks      = new HashMap<>();
    private final Map<UUID, Integer>        remaining  = new HashMap<>();

    MindControlAbility(GodShield plugin) { this.plugin = plugin; }

    void execute(Player controller, LivingEntity target) {
        UUID tid = target.getUniqueId();
        int  dur = plugin.getConfig().getInt("abilities.mind-control.duration", 15);

        if (tasks.containsKey(tid)) {
            // Refresh timer
            remaining.put(tid, dur * 20);
            spawnHitEffect(target.getLocation().clone().add(0, 1.2, 0));
            controller.sendActionBar(
                Component.text("👁  Control Refreshed  👁").color(NamedTextColor.LIGHT_PURPLE));
            return;
        }

        controlled.put(tid, controller.getUniqueId());
        remaining.put(tid, dur * 20);
        if (target instanceof Mob mob) mob.setAI(false);

        spawnActivationEffect(target.getLocation());
        controller.sendActionBar(
            Component.text("👁  Mind Control ACTIVE  👁").color(NamedTextColor.DARK_PURPLE));

        BukkitRunnable task = new BukkitRunnable() {
            double aura = 0;
            @Override public void run() {
                int rem = remaining.getOrDefault(tid, 0);
                if (rem <= 0 || !target.isValid() || target.isDead()) {
                    cancel();
                    releaseControl(tid);
                    return;
                }
                target.setVelocity(new Vector(0, 0, 0));

                // Face controller
                Player ctrl = plugin.getServer().getPlayer(
                    controlled.getOrDefault(tid, UUID.randomUUID()));
                if (ctrl != null && ctrl.isOnline()) {
                    Location from = target.getLocation();
                    Location look = from.clone().setDirection(
                        ctrl.getEyeLocation().toVector().subtract(from.toVector()).normalize());
                    target.teleport(look);
                }

                drawCrown(target.getLocation().clone().add(0, target.getHeight() + 0.6, 0), aura);
                aura += 0.14;

                if (rem % 30 == 0) {
                    drawPulse(target.getLocation().clone().add(0, 1.0, 0));
                    target.getWorld().playSound(target.getLocation(),
                        Sound.ENTITY_ENDERMAN_STARE, 0.4f, 2.0f);
                }
                remaining.put(tid, rem - 1);
            }
        };
        tasks.put(tid, task);
        task.runTaskTimer(plugin, 0L, 1L);
    }

    // Called by AbilityListener when caster hits an already-controlled entity
    void onHit(Player controller, LivingEntity target) {
        spawnHitEffect(target.getLocation().clone().add(0, 1.2, 0));
        controller.sendActionBar(
            Component.text("👁  Control Hit!  👁").color(NamedTextColor.LIGHT_PURPLE));
    }

    // ── Effects ───────────────────────────────────────────────────
    private void spawnActivationEffect(Location loc) {
        World w = loc.getWorld();
        Location c = loc.clone().add(0, 1, 0);
        Particle.DustOptions purple = new Particle.DustOptions(Color.fromRGB(160, 0, 255), 1.0f);
        for (int ring = 0; ring < 3; ring++) {
            double r = 1.0 + ring * 0.3, yOff = 1.5 - ring * 0.4;
            int pts = 28 + ring * 6;
            for (int i = 0; i < pts; i++) {
                double ang = (2.0 * Math.PI / pts) * i;
                w.spawnParticle(Particle.DUST,
                    new Location(w, c.getX() + r*Math.cos(ang), c.getY()+yOff, c.getZ() + r*Math.sin(ang)),
                    2, 0,0,0,0, purple);
            }
        }
        w.spawnParticle(Particle.FLASH,   c, 3);
        w.spawnParticle(Particle.END_ROD, c, 40, 0.4, 0.4, 0.4, 0.3);
        w.playSound(loc, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.0f, 1.8f);
        w.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT,   1.0f, 0.5f);
    }

    private void drawCrown(Location crown, double angle) {
        World w = crown.getWorld();
        Particle.DustOptions purp = new Particle.DustOptions(Color.fromRGB(180, 0, 255), 0.8f);
        for (int i = 0; i < 10; i++) {
            double theta = angle + (2.0 * Math.PI / 10) * i;
            w.spawnParticle(Particle.DUST,
                new Location(w, crown.getX() + 0.65*Math.cos(theta), crown.getY(), crown.getZ() + 0.65*Math.sin(theta)),
                1, 0,0,0,0, purp);
        }
        if (Math.random() < 0.25)
            w.spawnParticle(Particle.ENCHANT, crown, 2, 0.3, 0.1, 0.3, 1.2);
    }

    private void drawPulse(Location center) {
        World w = center.getWorld();
        Particle.DustOptions wh = new Particle.DustOptions(Color.WHITE, 1.2f);
        for (int i = 0; i < 24; i++) {
            double ang = (2.0 * Math.PI / 24) * i;
            w.spawnParticle(Particle.DUST,
                new Location(w, center.getX() + 0.9*Math.cos(ang), center.getY(), center.getZ() + 0.9*Math.sin(ang)),
                2, 0, 0.08, 0, 0, wh);
        }
        w.spawnParticle(Particle.END_ROD, center, 8, 0.2, 0.2, 0.2, 0.15);
    }

    private void spawnHitEffect(Location loc) {
        World w = loc.getWorld();
        Particle.DustOptions red = new Particle.DustOptions(Color.fromRGB(220, 0, 100), 1.3f);
        w.spawnParticle(Particle.DUST,    loc, 20, 0.4, 0.4, 0.4, 0, red);
        w.spawnParticle(Particle.CRIT,    loc, 12, 0.3, 0.3, 0.3, 0.5);
        w.spawnParticle(Particle.END_ROD, loc,  5, 0.2, 0.2, 0.2, 0.1);
        w.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.8f);
    }

    private void releaseControl(UUID tid) {
        controlled.remove(tid);
        tasks.remove(tid);
        remaining.remove(tid);
        Particle.DustOptions purp = new Particle.DustOptions(Color.fromRGB(160, 0, 255), 2.0f);
        plugin.getServer().getWorlds().stream()
            .flatMap(w -> w.getEntities().stream())
            .filter(e -> e.getUniqueId().equals(tid))
            .findFirst()
            .ifPresent(e -> {
                if (e instanceof Mob mob) mob.setAI(true);
                Location loc = e.getLocation().clone().add(0, 1, 0);
                loc.getWorld().spawnParticle(Particle.END_ROD, loc, 30, 0.5,0.5,0.5, 0.4);
                loc.getWorld().spawnParticle(Particle.DUST,    loc, 40, 0.5,0.5,0.5, 0, purp);
                loc.getWorld().playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 2.0f);
            });
    }

    boolean isControlled(UUID tid) { return controlled.containsKey(tid); }
}

// ════════════════════════════════════════════════════════
//  ParticleUtils  –  Shared helpers
// ════════════════════════════════════════════════════════
class ParticleUtils {

    private ParticleUtils() {}

    /** Horizontal DUST ring */
    static void ring(Location center, double radius, int points, Color color, float size) {
        World w = center.getWorld();
        Particle.DustOptions dust = new Particle.DustOptions(color, size);
        for (int i = 0; i < points; i++) {
            double ang = (2.0 * Math.PI / points) * i;
            w.spawnParticle(Particle.DUST,
                new Location(w, center.getX() + radius*Math.cos(ang), center.getY(),
                    center.getZ() + radius*Math.sin(ang)),
                1, 0,0,0,0, dust);
        }
    }

    /** Fibonacci-sphere END_ROD burst */
    static void burst(Location center, double radius, int count) {
        World w = center.getWorld();
        for (int i = 0; i < count; i++) {
            double phi   = Math.acos(1 - 2.0*i/count);
            double theta = Math.PI * (1 + Math.sqrt(5)) * i;
            w.spawnParticle(Particle.END_ROD,
                new Location(w,
                    center.getX() + radius*Math.sin(phi)*Math.cos(theta),
                    center.getY() + radius*Math.cos(phi),
                    center.getZ() + radius*Math.sin(phi)*Math.sin(theta)),
                1, 0,0,0,0);
        }
    }

    static Particle.DustOptions dust(int r, int g, int b, float size) {
        return new Particle.DustOptions(Color.fromRGB(r, g, b), size);
    }
}
