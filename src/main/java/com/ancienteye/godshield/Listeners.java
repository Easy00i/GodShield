package com.ancienteye.godshield;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.SoundCategory;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// ════════════════════════════════════════════════════════
//  PlayerListener  –  Off-hand changes
// ════════════════════════════════════════════════════════
class PlayerListener implements Listener {
    private final GodShield plugin;
    PlayerListener(GodShield plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent event) { scheduleCheck(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p) scheduleCheck(p);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeldItemChange(PlayerItemHeldEvent event) { scheduleCheck(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDropItem(PlayerDropItemEvent event) { scheduleCheck(event.getPlayer()); }
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        new BukkitRunnable() {
            @Override public void run() {
                checkOffhand(event.getPlayer());
                plugin.getRitualManager().onPlayerJoin(event.getPlayer());
            }
        }.runTaskLater(plugin, 5L);
    }
    @EventHandler
    public void onDeath(PlayerDeathEvent event) { plugin.getShieldManager().deactivate(event.getEntity()); }
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        new BukkitRunnable() {
            @Override public void run() { checkOffhand(event.getPlayer()); }
        }.runTaskLater(plugin, 5L);
    }
    @EventHandler
    public void onQuit(PlayerQuitEvent event) { plugin.getShieldManager().deactivate(event.getPlayer()); }

    private void scheduleCheck(Player player) {
        new BukkitRunnable() {
            @Override public void run() { checkOffhand(player); }
        }.runTaskLater(plugin, 1L);
    }
    private void checkOffhand(Player player) {
        if (!player.isOnline()) return;
        ItemStack offhand = player.getInventory().getItemInOffHand();
        boolean hasShield = GodShieldItem.isGodShield(offhand);
        boolean isActive  = plugin.getShieldManager().isActive(player);
        if (hasShield && !isActive) plugin.getShieldManager().activate(player);
        else if (!hasShield && isActive) plugin.getShieldManager().deactivate(player);
    }
}

// ════════════════════════════════════════════════════════
//  DamageListener  –  Immunity for shield holder (only incoming)
// ════════════════════════════════════════════════════════
class DamageListener implements Listener {
    private final GodShield plugin;
    DamageListener(GodShield plugin) { this.plugin = plugin; }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (!plugin.getShieldManager().isActive(p)) return;
        event.setCancelled(true);
        event.setDamage(0);
    }
}

// ════════════════════════════════════════════════════════
//  AbilityListener  –  Input → Abilities
//
//  FIX: Paper fires PlayerInteractEvent TWICE per click —
//  once for HAND, once for OFF_HAND. Without a guard, every
//  ability fires 2x per click. We use per-tick HashSets to
//  ensure each ability fires at most once per player per tick.
// ════════════════════════════════════════════════════════
class AbilityListener implements Listener {
    private final GodShield plugin;

    // Per-tick guards — cleared every tick so abilities can fire again next tick
    private final Set<UUID> swGuard = new HashSet<>();   // shockwave
    private final Set<UUID> lcGuard = new HashSet<>();   // left-click (prison/mind)

    AbilityListener(GodShield plugin) {
        this.plugin = plugin;
        // Clear guards every tick
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                swGuard.clear();
                lcGuard.clear();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ── SHOCKWAVE (Shift + Right Click) ─────────────────────────
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!plugin.getShieldManager().isActive(p)) return;

        Action a = event.getAction();

        // ── RIGHT CLICK → Shockwave ───────────────────────────
        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {

            // Always cancel OFF_HAND to stop shield raise
            if (event.getHand() == EquipmentSlot.OFF_HAND) {
                event.setCancelled(true);
                p.setCooldown(Material.SHIELD, 20);
                // Fire shockwave here (OFF_HAND fires first in Paper)
                if (p.isSneaking() && swGuard.add(p.getUniqueId())) {
                    plugin.getAbilityManager().useShockwave(p);
                }
                return;
            }

            // MAIN_HAND fallback — only fires if OFF_HAND didn't already fire it
            if (event.getHand() == EquipmentSlot.HAND) {
                if (p.isSneaking() && swGuard.add(p.getUniqueId())) {
                    event.setCancelled(true);
                    plugin.getAbilityManager().useShockwave(p);
                }
            }
            return;
        }

        // ── LEFT CLICK (air/block) → Prison / Mind Control via raytrace ──
        if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            if (event.getHand() != EquipmentSlot.HAND) return; // prevent double

            // Guard prevents double-fire with onEntityHit
            if (!lcGuard.add(p.getUniqueId())) return;

            LivingEntity target = getTargetEntity(p, 10.0);
            if (target == null) return;

            if (p.isSneaking()) {
                if (plugin.getAbilityManager().getMindControl().isControlled(target.getUniqueId())) {
                    plugin.getAbilityManager().refreshMindControl(p, target);
                } else {
                    plugin.getAbilityManager().useMindControl(p, target);
                }
            } else {
                plugin.getAbilityManager().usePrison(p, target);
            }
        }
    }

    // ── SHOCKWAVE (Shift + Right Click on Entity) ────────────────
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        Player p = event.getPlayer();
        if (!plugin.getShieldManager().isActive(p)) return;

        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            event.setCancelled(true);
            p.setCooldown(Material.SHIELD, 20);
            if (p.isSneaking() && swGuard.add(p.getUniqueId())) {
                plugin.getAbilityManager().useShockwave(p);
            }
            return;
        }
        if (event.getHand() == EquipmentSlot.HAND) {
            if (p.isSneaking() && swGuard.add(p.getUniqueId())) {
                event.setCancelled(true);
                plugin.getAbilityManager().useShockwave(p);
            }
        }
    }

    // ── PRISON / MIND CONTROL (direct entity hit) ────────────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (!plugin.getShieldManager().isActive(p)) return;

        // Guard prevents double-fire with onInteract LEFT_CLICK path
        if (!lcGuard.add(p.getUniqueId())) return;

        if (p.isSneaking()) {
            event.setCancelled(true);
            if (plugin.getAbilityManager().getMindControl().isControlled(target.getUniqueId())) {
                plugin.getAbilityManager().refreshMindControl(p, target);
            } else {
                plugin.getAbilityManager().useMindControl(p, target);
            }
        } else {
            event.setCancelled(true); // cancel vanilla damage — prison handles damage
            plugin.getAbilityManager().usePrison(p, target);
        }
    }

    // ── Vanilla attack cancel (shield holder does 0 vanilla damage) ──
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void cancelVanillaDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p)) return;
        if (!plugin.getShieldManager().isActive(p)) return;
        // Already cancelled in onEntityHit — this is a safety net
        event.setCancelled(true);
        event.setDamage(0);
    }

    // ── RayTrace helper ──────────────────────────────────────────
    private LivingEntity getTargetEntity(Player player, double maxRange) {
        RayTraceResult result = player.getWorld().rayTraceEntities(
            player.getEyeLocation(),
            player.getEyeLocation().getDirection(),
            maxRange,
            e -> !e.equals(player) && e instanceof LivingEntity
        );
        if (result == null) return null;
        Entity hit = result.getHitEntity();
        if (hit instanceof LivingEntity living) return living;
        return null;
    }


@EventHandler
public void onPickupDragonEgg(EntityPickupItemEvent event) {
    if (!(event.getEntity() instanceof Player p)) return;
    if (event.getItem().getItemStack().getType() != Material.DRAGON_EGG) return;

    String playerName = p.getName();
    for (Player online : Bukkit.getOnlinePlayers()) {
        online.showTitle(Title.title(
            Component.text("Dragon Egg Obtained!").color(NamedTextColor.DARK_PURPLE),
            Component.text("Obtained by " + playerName).color(NamedTextColor.LIGHT_PURPLE),
            Title.Times.times(
                java.time.Duration.ofMillis(500),
                java.time.Duration.ofMillis(3500),
                java.time.Duration.ofMillis(1000)
            )
        ));
    }
  }
}

// ════════════════════════════════════════════════════════
//  CraftListener  –  Mace one-time craft + God Shield ritual trigger
// ════════════════════════════════════════════════════════
class CraftListener implements Listener {
    private final GodShield plugin;
    CraftListener(GodShield plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        Material result = event.getRecipe().getResult().getType();

        // ── Mace: one-time craft lock ──────────────────────────────
        if (result == Material.MACE) {
            if (plugin.getCraftManager().isMaceCrafted()) {
                event.setCancelled(true);
                p.sendMessage(Component.text("⚠  The Mace has already been forged on this server!")
                    .color(NamedTextColor.DARK_RED));
                p.sendMessage(Component.text("   It can only be crafted once.")
                    .color(NamedTextColor.RED));
                return;
            }
            plugin.getCraftManager().setMaceCrafted(true);
            p.sendMessage(Component.text("⚔  You forged the only Mace on this server!")
                .color(NamedTextColor.GOLD));
            p.sendMessage(Component.text("   Use it to craft the God Shield.")
                .color(NamedTextColor.YELLOW));

            // Title announcement to all players
     String crafterName = p.getName();
       for (Player online : Bukkit.getOnlinePlayers()) {
            online.showTitle(Title.title(
             Component.text("⚔ Mace Forged!").color(NamedTextColor.GOLD),
             Component.text("Crafted by " + crafterName).color(NamedTextColor.YELLOW),
             Title.Times.times(
              java.time.Duration.ofMillis(500),   // fade in
               java.time.Duration.ofMillis(3500),  // stay
               java.time.Duration.ofMillis(1000)   // fade out
             )
          ));
        }

            for (Player online : Bukkit.getOnlinePlayers()) {
                online.playSound(online.getLocation(),
                    "godshield.mace_forged", 
                    SoundCategory.MASTER, 1.0f, 1.0f);
            }
            return;
        }

        // ── God Shield recipe: cancel normal give, start ritual ────
        if (!GodShieldItem.isGodShield(event.getInventory().getResult())) return;

        event.setCancelled(true);

        org.bukkit.Location tableLoc = p.getLocation(); 
        if (event.getInventory().getLocation() != null) {
         tableLoc = event.getInventory().getLocation();
      }

        for (Player online : Bukkit.getOnlinePlayers()) {
            online.playSound(online.getLocation(),
                "godshield.shield_forged",
                SoundCategory.MASTER, 1.0f, 1.0f);
        }

        plugin.getRitualManager().startRitual(p, tableLoc);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() == null) return;
        if (event.getRecipe().getResult().getType() == Material.MACE
                && plugin.getCraftManager().isMaceCrafted()) {
            event.getInventory().setResult(null);
        }
    }
}
