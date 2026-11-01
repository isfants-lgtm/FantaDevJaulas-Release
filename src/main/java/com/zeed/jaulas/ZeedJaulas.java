package com.zeed.jaulas;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class ZeedJaulas extends JavaPlugin {

    private CageManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new CageManager(this);
        manager.recoverLeftovers();

        getServer().getPluginManager().registerEvents(new CageListener(this, manager), this);

        JaulaCommand cmd = new JaulaCommand(this, manager);
        PluginCommand pc = getCommand("jaula");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }
        getLogger().info("ZeedJaulas ativado!");
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.removeAll();
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /** Envia uma mensagem da config. Pares: chave, valor, chave, valor... */
    public void send(CommandSender to, String key, String... kv) {
        String raw = getConfig().getString("mensagens." + key);
        if (raw == null || raw.isEmpty()) return;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            raw = raw.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        String prefix = getConfig().getString("mensagens.prefixo", "");
        to.sendMessage(color(prefix + raw));
    }
}
