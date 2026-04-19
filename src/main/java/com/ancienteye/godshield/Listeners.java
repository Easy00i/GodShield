package com.ancienteye.godshield;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
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

// ════════════════════════════════════════════════════════
//  PlayerListener  –  Detects off-hand changes
//
//  Whenever the item in a player's off-hand changes,
//  we check if it's the God Shield and activate/deactivate.
//  A 1-tick delay is scheduled so the inventory has settled.
// ════════════════════════════════════════════════════════
class PlayerListener implements Listener {

    private final GodShield plugin;
    PlayerListener(GodShield plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        scheduleCheck(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p) scheduleCheck(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        scheduleCheck(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDropItem(PlayerDropItemEvent event) {
        scheduleCheck(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        new BukkitRunnable() {
            @Override public void run() { checkOffhand(event.getPlayer()); }
        }.runTaskLater(plugin, 5L);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        plugin.getShieldManager().deactivate(event.getEntity());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        new BukkitRunnable() {
            @Override public void run() { checkOffhand(event.getPlayer()); }
        }.runTaskLater(plugin, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getShieldManager().deactivate(event.getPlayer());
    }

    private void scheduleCheck(Player player) {
        new BukkitRunnable() {
            @Override public void run() { checkOffhand(player); }
        }.runTaskLater(plugin, 1L);
    }

    private void checkOffhand(Player player) {
        if (!player.isOnline()) return;
        ItemStack offhand  = player.getInventory().getItemInOffHand();
        boolean   hasShield = GodShieldItem.isGodShield(offhand);
        boolean   isActive  = plugin.getShieldManager().isActive(player);

        if      ( hasShield && !isActive) plugin.getShieldManager().activate(player);
        else if (!hasShield &&  isActive) plugin.getShieldManager().deactivate(player);
    }
}

// ════════════════════════════════════════════════════════
//  DamageListener  –  Complete immunity for shield holder
//
//  HIGHEST priority so this cancels before armour/enchant
//  math runs. ignoreCancelled=false catches everything.
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
//  AbilityListener  –  Input → Ability routing
//
//  ┌─────────────────────────────────────────────────────┐
//  │  Shift + Right-Click  (any target)  →  Shockwave    │
//  │  Left-Click on entity               →  Prison       │
//  │  Shift + Left-Click on entity       →  Mind Control │
//  └─────────────────────────────────────────────────────┘
//
//  Bug fix (vs v1): Mind Control and Prison no longer both
//  fire in the same event. Sneaking routes ONLY to control.
// ════════════════════════════════════════════════════════
class AbilityListener implements Listener {

    private final GodShield plugin;
    AbilityListener(GodShield plugin) { this.plugin = plugin; }

    // ── Shockwave: Shift + Right-Click (air or block) ─────────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!plugin.getShieldManager().isActive(p)) return;
        if (event.getHand() != EquipmentSlot.HAND)  return; // prevent double-fire

        Action a = event.getAction();
        if ((a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) && p.isSneaking()) {
            event.setCancelled(true);
            plugin.getAbilityManager().useShockwave(p);
        }
    }

    // ── Shockwave: Shift + Right-Click on entity ──────────────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        Player p = event.getPlayer();
        if (!plugin.getShieldManager().isActive(p)) return;
        if (event.getHand() != EquipmentSlot.HAND)  return;

        if (p.isSneaking()) {
            event.setCancelled(true);
            plugin.getAbilityManager().useShockwave(p);
        }
    }

    // ── Prison / Mind Control: Left-Click on entity ───────────────
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p)) return;
        if (!plugin.getShieldManager().isActive(p))   return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        // Always cancel vanilla damage – abilities handle their own
        event.setCancelled(true);

        if (p.isSneaking()) {
            // ── Mind Control ──────────────────────────────────────
            MindControlAbility mc = plugin.getAbilityManager().getMindControl();
            if (mc.isControlled(target.getUniqueId())) {
                // Already controlled → just play hit effect + refresh
                plugin.getAbilityManager().useMindControl(p, target);
            } else {
                // First activation
                plugin.getAbilityManager().useMindControl(p, target);
            }
        } else {
            // ── Prison ────────────────────────────────────────────
            plugin.getAbilityManager().usePrison(p, target);
        }
    }
}

// ════════════════════════════════════════════════════════
//  CraftListener  –  Mace one-time craft enforcement
//
//  Once ANY player crafts a Mace the recipe is locked
//  server-wide (saved to config.yml → data.mace-crafted).
//  This prevents re-crafting the God Shield via a new Mace.
// ════════════════════════════════════════════════════════
class CraftListener implements Listener {

    private final GodShield plugin;
    CraftListener(GodShield plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(CraftItemEvent event) {
        if (event.getRecipe().getResult().getType() != Material.MACE) return;

        if (plugin.getCraftManager().isMaceCrafted()) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player p) {
                p.sendMessage(Component.text("⚠  The Mace has already been forged on this server!")
                    .color(NamedTextColor.DARK_RED));
                p.sendMessage(Component.text("   It can only be crafted once.")
                    .color(NamedTextColor.RED));
            }
            return;
        }
        // First time: allow + lock permanently
        plugin.getCraftManager().setMaceCrafted(true);
        if (event.getWhoClicked() instanceof Player p) {
            p.sendMessage(Component.text("⚔  You forged the only Mace on this server!")
                .color(NamedTextColor.GOLD));
            p.sendMessage(Component.text("   Use it to craft the God Shield.")
                .color(NamedTextColor.YELLOW));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() == null) return;
        if (event.getRecipe().getResult().getType() == Material.MACE
                && plugin.getCraftManager().isMaceCrafted()) {
            event.getInventory().setResult(null); // grey out recipe
        }
    }
}
