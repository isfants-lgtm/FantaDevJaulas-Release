package com.zeed.jaulas;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JaulaCommand implements CommandExecutor, TabCompleter {

    private static final String PERM = "zeedjaulas.admin";

    private final ZeedJaulas plugin;
    private final CageManager manager;

    public JaulaCommand(ZeedJaulas plugin, CageManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERM)) {
            plugin.send(sender, "sem-permissao");
            return true;
        }
        if (args.length == 0) {
            plugin.send(sender, "uso");
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("reload")) {
            plugin.reloadConfig();
            manager.reloadSettings();
            plugin.send(sender, "recarregado");
            return true;
        }

        if (sub.equals("remover")) {
            if (args.length < 2) {
                plugin.send(sender, "uso");
                return true;
            }
            Player t = Bukkit.getPlayerExact(args[1]);
            if (t == null) {
                plugin.send(sender, "jogador-nao-encontrado");
                return true;
            }
            if (!manager.isCaged(t)) {
                plugin.send(sender, "nao-preso", "jogador", t.getName());
                return true;
            }
            manager.end(t.getUniqueId(), true);
            plugin.send(sender, "jaula-removida-admin", "jogador", t.getName());
            return true;
        }

        CageType type = CageType.fromKey(sub);
        if (type == null) {
            plugin.send(sender, "uso");
            return true;
        }

        // Formatos: /jaula quadrada | /jaula quadrada 5 | /jaula quadrada <jogador> | /jaula quadrada <jogador> 5
        int amount = 1;
        String playerArg = null;
        if (args.length >= 2) {
            if (args[1].matches("\\d{1,3}")) {
                amount = Integer.parseInt(args[1]);
            } else {
                playerArg = args[1];
            }
        }
        if (args.length >= 3 && args[2].matches("\\d{1,3}")) {
            amount = Integer.parseInt(args[2]);
        }
        amount = Math.max(1, Math.min(64, amount));

        Player receiver;
        if (playerArg != null) {
            receiver = Bukkit.getPlayerExact(playerArg);
            if (receiver == null) {
                plugin.send(sender, "jogador-nao-encontrado");
                return true;
            }
        } else if (sender instanceof Player p) {
            receiver = p;
        } else {
            plugin.send(sender, "apenas-jogadores", "jaula", type.key);
            return true;
        }

        ItemStack item = manager.createItem(type);
        item.setAmount(amount);
        Map<Integer, ItemStack> left = receiver.getInventory().addItem(item);
        for (ItemStack l : left.values()) {
            receiver.getWorld().dropItem(receiver.getLocation(), l);
        }

        plugin.send(receiver, "item-recebido", "jaula", type.key);
        if (!receiver.equals(sender)) {
            plugin.send(sender, "item-entregue", "jaula", type.key, "jogador", receiver.getName());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission(PERM)) return out;

        if (args.length == 1) {
            for (String s : List.of("quadrada", "redonda", "remover", "reload")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }
}
