package dev.skyrealms.util;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Pays for things with one of two currencies, chosen in config (economy.currency):
 * <ul>
 *   <li><b>vault</b>: any Vault economy, found by reflection so Vault isn't needed to compile.
 *       With no economy installed, everything is free unless economy.require-vault is true.</li>
 *   <li><b>item</b>: a physical item such as diamonds or a named token. Prices are converted with
 *       economy.item.value (e.g. 1000 means one diamond per 1,000 of price, rounded up).</li>
 * </ul>
 */
public final class Money {
    private enum Mode { VAULT, ITEM }

    private final Mode mode;
    private final boolean required;

    // vault
    private Object economy;
    private Method has, withdraw, deposit, format, success;

    // item
    private Material item = Material.DIAMOND;
    private String itemName;      // optional exact display name, for custom tokens
    private double itemValue = 1000;
    private String singular = "Diamond", plural = "Diamonds";

    public Money(ConfigurationSection economyConfig) {
        ConfigurationSection cfg = economyConfig;
        String m = cfg == null ? "vault" : cfg.getString("currency", "vault");
        this.mode = "item".equalsIgnoreCase(m) ? Mode.ITEM : Mode.VAULT;
        this.required = cfg != null && cfg.getBoolean("require-vault", false);
        if (mode == Mode.ITEM) loadItem(cfg.getConfigurationSection("item"));
        else hookVault();
    }

    private void loadItem(ConfigurationSection c) {
        if (c == null) return;
        Material mat = Material.matchMaterial(c.getString("material", "DIAMOND"));
        if (mat != null && mat.isItem()) item = mat;
        String name = c.getString("name", "");
        itemName = name == null || name.isBlank() ? null : name;
        itemValue = Math.max(0.0001, c.getDouble("value", 1000));
        String pretty = itemName != null ? itemName : prettyMaterial(item);
        String sg = c.getString("singular", ""), pl = c.getString("plural", "");
        singular = sg == null || sg.isBlank() ? pretty : sg;
        plural = pl == null || pl.isBlank() ? (pretty.endsWith("s") ? pretty : pretty + "s") : pl;
    }

    private void hookVault() {
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

    public boolean isItemMode() { return mode == Mode.ITEM; }

    /** True when prices actually cost something. */
    public boolean enabled() { return mode == Mode.ITEM || economy != null; }

    /** False only when Vault is required but missing. */
    public boolean usable() { return mode == Mode.ITEM || economy != null || !required; }

    public String describe() {
        if (mode == Mode.ITEM) return "Item currency: " + plural + " (1 per " + String.format("%,.0f", itemValue) + ")";
        return economy != null ? "Hooked into Vault economy." : "No Vault economy found - purchases are free.";
    }

    /** How many items a price costs in item mode. */
    public int items(double amount) { return amount <= 0 ? 0 : (int) Math.ceil(amount / itemValue - 1e-9); }

    public String format(double amount) {
        if (mode == Mode.ITEM) {
            int n = items(amount);
            return n == 0 ? "free" : n + " " + (n == 1 ? singular : plural);
        }
        if (economy != null) {
            try { return (String) format.invoke(economy, amount); } catch (ReflectiveOperationException ignored) {}
        }
        return economy == null ? "free" : String.format("%,.0f", amount);
    }

    /** Charges the player; returns true if paid (or if running without an economy). */
    public boolean charge(OfflinePlayer player, double amount) {
        if (amount <= 0) return true;
        if (mode == Mode.ITEM) return takeItems(player.getPlayer(), items(amount));
        if (economy == null) return !required;
        try {
            if (!(boolean) has.invoke(economy, player, amount)) return false;
            Object resp = withdraw.invoke(economy, player, amount);
            return (boolean) success.invoke(resp);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** Gives money (or items) back, e.g. when a purchase can't be completed or on a delete refund. */
    public void refund(OfflinePlayer player, double amount) {
        if (amount <= 0) return;
        if (mode == Mode.ITEM) { giveItems(player.getPlayer(), (int) Math.floor(amount / itemValue + 1e-9)); return; }
        if (economy == null) return;
        try { deposit.invoke(economy, player, amount); } catch (ReflectiveOperationException ignored) {}
    }

    // ---- item helpers ----------------------------------------------------------------

    private boolean matches(ItemStack s) {
        if (s == null || s.getType() != item) return false;
        if (itemName == null) return true;
        if (!s.hasItemMeta() || s.getItemMeta().displayName() == null) return false;
        String shown = PlainTextComponentSerializer.plainText().serialize(s.getItemMeta().displayName());
        return shown.equalsIgnoreCase(itemName);
    }

    private boolean takeItems(Player p, int count) {
        if (count <= 0) return true;
        if (p == null) return false;
        ItemStack[] contents = p.getInventory().getStorageContents();
        int have = 0;
        for (ItemStack s : contents) if (matches(s)) have += s.getAmount();
        if (have < count) return false;
        int left = count;
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack s = contents[i];
            if (!matches(s)) continue;
            int take = Math.min(left, s.getAmount());
            s.setAmount(s.getAmount() - take);
            if (s.getAmount() <= 0) contents[i] = null;
            left -= take;
        }
        p.getInventory().setStorageContents(contents);
        return true;
    }

    private void giveItems(Player p, int count) {
        if (p == null || count <= 0) return;
        while (count > 0) {
            int n = Math.min(count, item.getMaxStackSize());
            ItemStack s = new ItemStack(item, n);
            if (itemName != null) s.editMeta(m -> m.displayName(Msg.c("<!italic>" + itemName)));
            p.getInventory().addItem(s).values().forEach(rest -> p.getWorld().dropItem(p.getLocation(), rest));
            count -= n;
        }
    }

    private static String prettyMaterial(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String w : m.name().toLowerCase(Locale.ROOT).split("_")) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
