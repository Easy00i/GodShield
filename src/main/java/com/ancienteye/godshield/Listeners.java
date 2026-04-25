package com.ancienteye.godshield;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
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
                // Re-attach ritual boss bar if player was disconnected mid-ritual
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
//  Removed cancelVanillaAttack so player can hit normally.
// ════════════════════════════════════════════════════════
class AbilityListener implements Listener {
    private final GodShield plugin;
    AbilityListener(GodShield plugin) { this.plugin = plugin; }

    // ── Shockwave: Shift + Right-Click (air or block) ────────────
    //
    // FIX: When shield is in offhand, Paper fires OFF_HAND event first.
    // MAIN_HAND event NEVER fires when offhand item (shield) is present.
    // So shockwave MUST be triggered from the OFF_HAND event.
    // We also call p.setCooldown(Material.SHIELD, 1) to prevent the shield
    // from raising server-side even after we cancel the event.
    @EventHandler(priority = EventPriority.LOWEST) // LOWEST = catch before anything else
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!plugin.getShieldManager().isActive(p)) return;

        Action a = event.getAction();
        boolean isRight = (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK);
        if (!isRight) return;

        // Always cancel off-hand so shield never raises
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            event.setCancelled(true);
            p.setCooldown(Material.SHIELD, 1); // prevents vanilla shield-raise server-side

            // MAIN_HAND event never fires with shield in offhand — fire shockwave HERE
            if (p.isSneaking()) {
                plugin.getAbilityManager().useShockwave(p);
            }
            return;
        }

        // MAIN_HAND fallback (fires only if no offhand item takes priority)
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (p.isSneaking()) {
            event.setCancelled(true);
            plugin.getAbilityManager().useShockwave(p);
        }
    }

    // Shift + Right-Click on entity → Shockwave
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        Player p = event.getPlayer();
        if (!plugin.getShieldManager().isActive(p)) return;

        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            event.setCancelled(true);
            p.setCooldown(Material.SHIELD, 1);
            if (p.isSneaking()) {
                plugin.getAbilityManager().useShockwave(p);
            }
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (p.isSneaking()) {
            event.setCancelled(true);
            plugin.getAbilityManager().useShockwave(p);
        }
    }

    // Left-Click / Shift+Left-Click → Prison / Mind Control
    @EventHandler(priority = EventPriority.NORMAL)
    public void onArmSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = event.getPlayer();
        if (!plugin.getShieldManager().isActive(p)) return;
        LivingEntity target = getTargetEntity(p, 6.0);
        if (target == null) return;
        if (p.isSneaking()) {
            // Mind Control
            if (plugin.getAbilityManager().getMindControl().isControlled(target.getUniqueId())) {
                plugin.getAbilityManager().refreshMindControl(p, target);
            } else {
                plugin.getAbilityManager().useMindControl(p, target);
            }
        } else {
            // Prison
            plugin.getAbilityManager().usePrison(p, target);
        }
    }

    // FIX: player.rayTraceEntities(double) does not exist in Bukkit API.
    // Correct method: World.rayTraceEntities() — entity-only, ignores blocks.
    private LivingEntity getTargetEntity(Player player, double maxRange) {
        RayTraceResult result = player.getWorld().rayTraceEntities(
            player.getEyeLocation(),
            player.getEyeLocation().getDirection(),
            maxRange,
            e -> !e.equals(player) // exclude self
        );
        if (result == null) return null;
        Entity hit = result.getHitEntity();
        if (hit instanceof LivingEntity living) return living;
        return null;
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

            // Server mein sab players ko custom sound sunao
for (Player online : Bukkit.getOnlinePlayers()) {
    online.playSound(online.getLocation(),
        "godshield.mace_forged", // custom sound key
        SoundCategory.MASTER, 1.0f, 1.0f);
      }
            return;
        }

        // ── God Shield recipe: cancel normal give, start ritual ────
        if (!GodShieldItem.isGodShield(event.getRecipe().getResult())) return;

        // Cancel the normal item delivery — ritual will drop it after 10 min
        event.setCancelled(true);

        // Find crafting table location (the block the player clicked)
        // CraftItemEvent gives us the inventory; the view's top inventory
        // location is the crafting table block in the world.
        org.bukkit.Location tableLoc = p.getLocation(); // fallback: player feet
        if (event.getView().getTopInventory().getLocation() != null) {
            tableLoc = event.getView().getTopInventory().getLocation();
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
        // Grey out mace if already crafted
        if (event.getRecipe().getResult().getType() == Material.MACE
                && plugin.getCraftManager().isMaceCrafted()) {
            event.getInventory().setResult(null);
        }
    }
}
