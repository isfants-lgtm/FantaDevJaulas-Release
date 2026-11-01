package com.zeed.jaulas;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CageManager {

    private record BlockKey(UUID world, int x, int y, int z) {}

    private record SavedBlock(int x, int y, int z, BlockData original) {}

    private static class ActiveCage {
        World world;
        CageType type;
        int bx, by, bz;
        int r, h;
        Material material;
        List<SavedBlock> blocks = new ArrayList<>();
        BukkitTask task;
        File file;
    }

    private final ZeedJaulas plugin;
    private final NamespacedKey itemKey;
    private final File cagesDir;
    private final Map<UUID, ActiveCage> active = new HashMap<>();
    private final Set<BlockKey> protectedBlocks = new HashSet<>();
    private List<String> insideNames = new ArrayList<>();
    private boolean particleWarned = false;

    public CageManager(ZeedJaulas plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "tipo_jaula");
        this.cagesDir = new File(plugin.getDataFolder(), "cages");
        if (!cagesDir.exists()) cagesDir.mkdirs();
        reloadSettings();
    }

    public void reloadSettings() {
        List<String> names = new ArrayList<>();
        for (String s : plugin.getConfig().getStringList("protegidos-dentro")) {
            names.add(s.toUpperCase());
        }
        insideNames = names;
    }

    // ------------------------------------------------------------ itens

    public Material blockMaterial(CageType type) {
        Material def = type == CageType.QUADRADA ? Material.RED_STAINED_GLASS : Material.GLASS;
        String name = plugin.getConfig().getString("jaulas." + type.key + ".bloco", def.name());
        Material m = Material.matchMaterial(name);
        if (m == null || !m.isBlock()) {
            plugin.getLogger().warning("Bloco invalido '" + name + "' para a jaula " + type.key + ". Usando " + def.name());
            return def;
        }
        return m;
    }

    /** Material do item segurado: o mesmo vidro da jaula, mas em painel. */
    public Material itemMaterial(CageType type) {
        String override = plugin.getConfig().getString("jaulas." + type.key + ".item", "");
        if (override != null && !override.isEmpty()) {
            Material m = Material.matchMaterial(override);
            if (m != null && m.isItem()) return m;
        }
        Material block = blockMaterial(type);
        Material pane = Material.matchMaterial(block.name() + "_PANE");
        return (pane != null && pane.isItem()) ? pane : block;
    }

    public ItemStack createItem(CageType type) {
        ItemStack item = new ItemStack(itemMaterial(type));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ZeedJaulas.color(
                    plugin.getConfig().getString("jaulas." + type.key + ".nome-item", "&fJaula " + type.key)));
            List<String> lore = new ArrayList<>();
            for (String l : plugin.getConfig().getStringList("jaulas." + type.key + ".lore")) {
                lore.add(ZeedJaulas.color(l));
            }
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, type.key);
            item.setItemMeta(meta);
        }
        return item;
    }

    public CageType getType(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String v = meta.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return v == null ? null : CageType.fromKey(v);
    }

    // ------------------------------------------------------------ estado

    public boolean isCaged(Player p) {
        return active.containsKey(p.getUniqueId());
    }

    public boolean isProtected(Block b) {
        return isProtectedAt(b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    private boolean isProtectedAt(World w, int x, int y, int z) {
        if (protectedBlocks.isEmpty()) return false;
        return protectedBlocks.contains(new BlockKey(w.getUID(), x, y, z));
    }

    private boolean matchesInside(Material m) {
        String n = m.name();
        for (String p : insideNames) {
            if (p.startsWith("*") ? n.endsWith(p.substring(1)) : n.equals(p)) return true;
        }
        return false;
    }

    /** Teia / la (configuravel) dentro de uma jaula ativa: nao pode ser quebrada. */
    public boolean isProtectedInside(Block b) {
        if (active.isEmpty()) return false;
        if (!matchesInside(b.getType())) return false;
        for (ActiveCage ac : active.values()) {
            if (!ac.world.equals(b.getWorld())) continue;
            if (isInterior(ac.type, ac.r, ac.h, b.getX() - ac.bx, b.getY() - ac.by, b.getZ() - ac.bz)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ formato

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private int radius(CageType type) {
        int def = type == CageType.QUADRADA ? 2 : 3;
        int min = type == CageType.QUADRADA ? 1 : 2;
        return clamp(plugin.getConfig().getInt("jaulas." + type.key + ".raio", def), min, 8);
    }

    private int height(CageType type) {
        return clamp(plugin.getConfig().getInt("jaulas." + type.key + ".altura", 3), 2, 10);
    }

    private static boolean inSphere(int x, int y, int z, int r) {
        double lim = (r + 0.5) * (r + 0.5);
        return x * x + y * y + z * z <= lim;
    }

    /** Bloco da esfera que encosta (inclusive na diagonal) em algo de fora: forma uma casca sem buracos. */
    private static boolean sphereEdge(int x, int y, int z, int r) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oy = -1; oy <= 1; oy++) {
                for (int oz = -1; oz <= 1; oz++) {
                    if (ox == 0 && oy == 0 && oz == 0) continue;
                    if (!inSphere(x + ox, y + oy, z + oz, r)) return true;
                }
            }
        }
        return false;
    }

    /** Offsets relativos ao bloco dos pes do jogador. A esfera tem o fundo logo abaixo dos pes. */
    private List<int[]> shape(CageType type) {
        int r = radius(type);
        int h = height(type);
        List<int[]> list = new ArrayList<>();

        if (type == CageType.QUADRADA) {
            int e = r + 1;
            for (int dy = -1; dy <= h; dy++) {
                for (int dx = -e; dx <= e; dx++) {
                    for (int dz = -e; dz <= e; dz++) {
                        boolean cap = dy == -1 || dy == h;
                        if (cap || Math.abs(dx) == e || Math.abs(dz) == e) {
                            list.add(new int[]{dx, dy, dz});
                        }
                    }
                }
            }
        } else {
            int cy = r - 1;
            int e = r + 1;
            for (int x = -e; x <= e; x++) {
                for (int y = -e; y <= e; y++) {
                    for (int z = -e; z <= e; z++) {
                        if (!inSphere(x, y, z, r) || !sphereEdge(x, y, z, r)) continue;
                        list.add(new int[]{x, y + cy, z});
                    }
                }
            }
        }
        return list;
    }

    private static boolean isInterior(CageType type, int r, int h, int dx, int dy, int dz) {
        if (type == CageType.QUADRADA) {
            return Math.abs(dx) <= r && Math.abs(dz) <= r && dy >= 0 && dy < h;
        }
        int y = dy - (r - 1);
        return inSphere(dx, y, dz, r) && !sphereEdge(dx, y, dz, r);
    }

    private boolean replaceable(Block b) {
        Material t = b.getType();
        if (t == Material.NETHER_PORTAL || t == Material.END_PORTAL || t == Material.END_GATEWAY) return false;
        if (t.isAir() || b.isLiquid()) return true;
        // Blocos sem colisao (grama, flores, tochas...) sao substituidos e depois restaurados.
        // Blocos com dados proprios (placas, banners...) nunca sao mexidos.
        return b.isPassable() && !(b.getState() instanceof TileState);
    }

    // ------------------------------------------------------------ aplicar / remover

    public boolean apply(Player user, Player target, CageType type) {
        FileConfiguration cfg = plugin.getConfig();
        UUID id = target.getUniqueId();

        if (active.containsKey(id)) {
            if (user.getUniqueId().equals(id)) {
                plugin.send(user, "voce-ja-esta-preso");
            } else {
                plugin.send(user, "alvo-ja-preso", "jogador", target.getName());
            }
            return false;
        }

        World w = target.getWorld();
        Location loc = target.getLocation();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        List<int[]> shape = shape(type);

        for (int[] o : shape) {
            if (isProtectedAt(w, bx + o[0], by + o[1], bz + o[2])) {
                plugin.send(user, "area-ocupada");
                return false;
            }
        }

        // Centraliza o alvo no bloco (antes de ficar marcado como preso)
        if (target.isInsideVehicle()) target.leaveVehicle();
        target.eject();
        target.teleport(new Location(w, bx + 0.5, loc.getY(), bz + 0.5, loc.getYaw(), loc.getPitch()));

        Material mat = blockMaterial(type);
        BlockData cageData = mat.createBlockData();
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();

        ActiveCage ac = new ActiveCage();
        ac.world = w;
        ac.type = type;
        ac.bx = bx;
        ac.by = by;
        ac.bz = bz;
        ac.r = radius(type);
        ac.h = height(type);
        ac.material = mat;

        for (int[] o : shape) {
            int x = bx + o[0];
            int y = by + o[1];
            int z = bz + o[2];
            if (y < minY || y >= maxY) continue;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block b = w.getBlockAt(x, y, z);
            if (!replaceable(b)) continue;
            ac.blocks.add(new SavedBlock(x, y, z, b.getBlockData()));
            b.setBlockData(cageData, false);
            protectedBlocks.add(new BlockKey(w.getUID(), x, y, z));
        }

        ac.file = new File(cagesDir, id + ".yml");
        saveToDisk(ac);
        active.put(id, ac);

        particles(ac, "ao-aparecer", "END_ROD");

        int seconds = Math.max(1, cfg.getInt("duracao-segundos", 15));
        ac.task = Bukkit.getScheduler().runTaskLater(plugin, () -> end(id, true), seconds * 20L);

        if (user.getUniqueId().equals(id)) {
            plugin.send(user, "voce-se-prendeu", "segundos", String.valueOf(seconds));
        } else {
            plugin.send(user, "jaula-aplicada", "jogador", target.getName(), "segundos", String.valueOf(seconds));
            plugin.send(target, "voce-foi-preso", "jogador", user.getName(), "segundos", String.valueOf(seconds));
        }
        return true;
    }

    public void end(UUID id, boolean notify) {
        ActiveCage ac = active.remove(id);
        if (ac == null) return;
        if (ac.task != null) ac.task.cancel();
        restore(ac);
        if (ac.file != null && ac.file.exists()) ac.file.delete();
        if (notify) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) plugin.send(p, "jaula-removida");
        }
    }

    public void removeAll() {
        for (UUID id : new ArrayList<>(active.keySet())) {
            end(id, false);
        }
    }

    private void restore(ActiveCage ac) {
        for (SavedBlock s : ac.blocks) {
            protectedBlocks.remove(new BlockKey(ac.world.getUID(), s.x(), s.y(), s.z()));
            Block b = ac.world.getBlockAt(s.x(), s.y(), s.z());
            // So restaura se o bloco ainda for o da jaula (nunca sobrescreve outra coisa)
            if (b.getType() == ac.material) {
                b.setBlockData(s.original(), false);
            }
        }
        particles(ac, "ao-desaparecer", "CLOUD");
    }

    // ------------------------------------------------------------ persistencia

    private void saveToDisk(ActiveCage ac) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", ac.world.getName());
        y.set("material", ac.material.name());
        List<String> lines = new ArrayList<>();
        for (SavedBlock s : ac.blocks) {
            lines.add(s.x() + "|" + s.y() + "|" + s.z() + "|" + s.original().getAsString());
        }
        y.set("blocos", lines);
        try {
            y.save(ac.file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Nao foi possivel salvar a jaula em disco: " + ex.getMessage());
        }
    }

    /** Restaura jaulas que ficaram no mapa se o servidor caiu ou foi fechado a forca. */
    public void recoverLeftovers() {
        File[] files = cagesDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        int total = 0;
        for (File f : files) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            World w = Bukkit.getWorld(y.getString("world", ""));
            Material mat = Material.matchMaterial(y.getString("material", ""));
            if (w == null || mat == null) continue;
            for (String line : y.getStringList("blocos")) {
                String[] p = line.split("\\|", 4);
                if (p.length < 4) continue;
                try {
                    Block b = w.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                    if (b.getType() == mat) {
                        b.setBlockData(Bukkit.createBlockData(p[3]), false);
                        total++;
                    }
                } catch (Exception ignored) {
                    // linha invalida, ignora
                }
            }
            f.delete();
        }
        if (total > 0) plugin.getLogger().info("Restaurados " + total + " blocos de jaulas antigas.");
    }

    // ------------------------------------------------------------ particulas

    private void particles(ActiveCage ac, String path, String def) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("particulas.ativado", true)) return;
        try {
            Particle p;
            switch (cfg.getString("particulas." + path, def).toUpperCase()) {
                case "END_ROD" -> p = Particle.END_ROD;
                case "FLAME" -> p = Particle.FLAME;
                case "CRIT" -> p = Particle.CRIT;
                case "PORTAL" -> p = Particle.PORTAL;
                case "HEART" -> p = Particle.HEART;
                case "NOTE" -> p = Particle.NOTE;
                case "POOF" -> p = Particle.POOF;
                default -> p = Particle.CLOUD;
            }
            int count = clamp(cfg.getInt("particulas.quantidade", 2), 1, 10);
            for (SavedBlock s : ac.blocks) {
                ac.world.spawnParticle(p, s.x() + 0.5, s.y() + 0.5, s.z() + 0.5, count, 0.3, 0.3, 0.3, 0.0);
            }
        } catch (Throwable t) {
            if (!particleWarned) {
                particleWarned = true;
                plugin.getLogger().warning("Particulas indisponiveis nesta versao: " + t);
            }
        }
    }
}
