package dev.skyrealms.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/**
 * Talks to Vault's Economy service by reflection, so the plugin compiles and runs
 * without Vault on the classpath. With no economy installed, everything is free
 * unless economy.require-vault is true.
 */
public final class Money {
    private Object economy;
    private Method has, withdraw, deposit, format, success;
    private final boolean required;

    public Money(boolean required) {
        this.required = required;
        try {
            Class<?> cls = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(cls);
            if (rsp == null) return;
            economy = rsp.getProvider();
            has = cls.getMethod("has", OfflinePlayer.class, double.class);
            withdraw = cls.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            deposit = cls.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            format = cls.getMethod("format", double.class);
            success = Class.forName("net.milkbowl.vault.economy.EconomyResponse").getMethod("transactionSuccess");
        } catch (ReflectiveOperationException | LinkageError ignored) {
            economy = null;
        }
    }

    public boolean enabled() { return economy != null; }

    /** False only when Vault is required but missing. */
    public boolean usable() { return economy != null || !required; }

    public String format(double amount) {
        if (economy != null) {
            try { return (String) format.invoke(economy, amount); } catch (ReflectiveOperationException ignored) {}
        }
        return economy == null ? "free" : String.format("%,.0f", amount);
    }

    /** Charges the player; returns true if paid (or if running without an economy). */
    public boolean charge(OfflinePlayer player, double amount) {
        if (amount <= 0) return true;
        if (economy == null) return !required;
        try {
            if (!(boolean) has.invoke(economy, player, amount)) return false;
            Object resp = withdraw.invoke(economy, player, amount);
            return (boolean) success.invoke(resp);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** Gives money back (used when a purchase can't be completed). */
    public void refund(OfflinePlayer player, double amount) {
        if (economy == null || amount <= 0) return;
        try { deposit.invoke(economy, player, amount); } catch (ReflectiveOperationException ignored) {}
    }
}
