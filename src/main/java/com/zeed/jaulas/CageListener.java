package com.zeed.jaulas;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CageListener implements Listener {

    private final ZeedJaulas plugin;
    private final CageManager manager;
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public CageListener(ZeedJaulas plugin, CageManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ------------------------------------------------ uso do item
    // ignoreCancelled = false: funciona mesmo no spawn, onde protecoes
    // (WorldGuard, spawn-protection, etc.) cancelam as interacoes.

    /** Clique direto em cima de um jogador. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onUseEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        Player user = e.getPlayer();
        CageType type = manager.getType(user.getInventory().getItemInMainHand());
        if (type == null) return;

        e.setCancelled(true);
        if (tooSoon(user)) return;

        if (selfMode()) {
            useCage(user, user, type);
        } else if (e.getRightClicked() instanceof Player target) {
            useCage(user, target, type);
        }
    }

    /** SI_MESMO (padrao): a jaula aparece ao redor de quem usa. MIRAR: prende quem estiver na mira. */
    private boolean selfMode() {
        return !"MIRAR".equalsIgnoreCase(plugin.getConfig().getString("modo-uso", "SI_MESMO"));
    }

    /** Clique no ar ou em bloco: prende o jogador que estiver na mira. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player user = e.getPlayer();
        CageType type = manager.getType(user.getInventory().getItemInMainHand());
        if (type == null) return;

        // O item e um painel: nunca deixa colocar como bloco
        e.setUseItemInHand(Event.Result.DENY);

        if (selfMode()) {
            // Sempre cria a jaula ao clicar (sem agachar). O clique nao abre bau, porta etc.
            e.setUseInteractedBlock(Event.Result.DENY);
            if (tooSoon(user)) return;
            useCage(user, user, type);
            return;
        }

        if (tooSoon(user)) return;

        Player target = findTarget(user);
        if (target == null) {
            plugin.send(user, "nenhum-alvo");
            return;
        }
        useCage(user, target, type);
    }

    private void useCage(Player user, Player target, CageType type) {
        if (!manager.apply(user, target, type)) return;

        // Todo uso gasta 1 item, para qualquer jogador (OP, sem OP, qualquer cargo/grupo, criativo ou nao)
        ItemStack cur = user.getInventory().getItemInMainHand();
        if (cur.getAmount() <= 1) {
            user.getInventory().setItemInMainHand(null);
        } else {
            ItemStack rest = cur.clone();
            rest.setAmount(cur.getAmount() - 1);
            user.getInventory().setItemInMainHand(rest);
        }
    }

    /** Evita usar duas vezes no mesmo clique (o jogo pode disparar os dois eventos). */
    private boolean tooSoon(Player p) {
        long now = System.currentTimeMillis();
        Long prev = lastUse.get(p.getUniqueId());
        if (prev != null && now - prev < 250) return true;
        lastUse.put(p.getUniqueId(), now);
        return false;
    }

    private Player findTarget(Player user) {
        double range = Math.max(3.0, Math.min(100.0, plugin.getConfig().getDouble("alcance", 30.0)));
        Location eye = user.getEyeLocation();
        Vector dir = eye.getDirection();
        World w = user.getWorld();

        RayTraceResult er = w.rayTraceEntities(eye, dir, range, 0.4,
                en -> en instanceof Player p
                        && !p.getUniqueId().equals(user.getUniqueId())
                        && p.getGameMode() != GameMode.SPECTATOR
                        && user.canSee(p));
        if (er == null || !(er.getHitEntity() instanceof Player target)) return null;

        if (plugin.getConfig().getBoolean("linha-de-visao", true)) {
            RayTraceResult br = w.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
            if (br != null) {
                double blockDist = br.getHitPosition().distanceSquared(eye.toVector());
                double entityDist = er.getHitPosition().distanceSquared(eye.toVector());
                if (blockDist < entityDist) return null;
            }
        }
        return target;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastUse.remove(e.getPlayer().getUniqueId());
    }

    // O item da jaula nao pode ser colocado como bloco
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlaceItem(BlockPlaceEvent e) {
        if (manager.getType(e.getItemInHand()) != null) {
            e.setCancelled(true);
        }
    }

    // ... nem usado em receitas de craft
    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (manager.getType(it) != null) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    // ------------------------------------------------ protecao dos blocos

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        // So a parede/chao/teto da jaula e protegido (teia, la etc. dentro dela podem ser quebrados)
        if (manager.isProtected(b)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> manager.isProtected(b));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> manager.isProtected(b));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (manager.isProtected(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (manager.isProtected(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (manager.isProtected(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // ------------------------------------------------ jogador preso

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!plugin.getConfig().getBoolean("bloquear-teleporte", true)) return;
        Player p = e.getPlayer();
        if (!manager.isCaged(p)) return;

        Location to = e.getTo();
        if (to != null && to.getWorld() == e.getFrom().getWorld()
                && to.distanceSquared(e.getFrom()) < 1.0) {
            return;
        }
        e.setCancelled(true);
        plugin.send(p, "teleporte-bloqueado");
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (plugin.getConfig().getBoolean("remover-ao-morrer", true)) {
            manager.end(e.getEntity().getUniqueId(), false);
        }
    }
}
