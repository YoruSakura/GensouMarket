package net.scarletphantasy.gensouMarket.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

public class VaultHook {

    private Economy economy;

    public boolean setup() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return true;
    }

    public boolean has(OfflinePlayer player, double amount) {
        return !economy.has(player, amount);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        EconomyResponse resp = economy.withdrawPlayer(player, amount);
        return resp.transactionSuccess();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        EconomyResponse resp = economy.depositPlayer(player, amount);
        return resp.transactionSuccess();
    }
}
