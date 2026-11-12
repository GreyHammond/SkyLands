package dev.skyrealms.menu;

import dev.skyrealms.RealmService;
import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.island.Flag;
import dev.skyrealms.island.Island;
import dev.skyrealms.island.Role;
import dev.skyrealms.island.Tier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.Consumer;

import static dev.skyrealms.menu.Menu.*;

/** Every chest screen. Anything that costs money goes through a confirm screen first. */
public final class Menus {
    private static final Material[] TIER_ICONS = {
            Material.OAK_SAPLING, Material.GRASS_BLOCK, Material.OAK_LOG, Material.MOSS_BLOCK, Material.FILLED_MAP};

    private final SkyRealmsPlugin plugin;
    private final RealmService svc;

    public Menus(SkyRealmsPlugin plugin, RealmService svc) {
        this.plugin = plugin;
        this.svc = svc;
    }

    // ---- main -----------------------------------------------------------------------

    public void main(Player p) {
        Island i = plugin.islands().of(p.getUniqueId());
        Menu m = new Menu(3, "<dark_aqua>Sky Realm");
        if (i == null) {
            m.button(11, glow(item(Material.GRASS_BLOCK, "<green>Buy an Island", "<gray>Pick a size and a biome.")), this::buyTier);
            m.button(15, item(Material.COMPASS, "<aqua>Visit Islands", "<gray>Look around other players' islands."), pl -> visit(pl, 0));
        } else {
            m.button(10, item(Material.RED_BED, "<green>Go Home", "<gray>Teleport to your island."), pl -> { pl.closeInventory(); svc.home(pl); });
            m.button(11, item(Material.WHEAT_SEEDS, "<green>Grow Land",
                    "<gray>Attach more of a biome you own.", "<gray>Cost: <gold>" + svc.money(svc.growPrice()) + " <gray>each",
                    "<gray>Pieces: <white>" + i.pieces.size() + "/" + i.tier.maxPieces), pl -> biomes(pl, Mode.GROW, null));
            m.button(12, item(Material.CHERRY_SAPLING, "<light_purple>Unlock a Biome",
                    "<gray>Add a whole new biome region", "<gray>to the edge of your island."), pl -> biomes(pl, Mode.UNLOCK, null));
            m.button(13, upgradeIcon(i), this::upgrade);
            m.button(14, item(Material.SHIELD, "<yellow>Protection",
                    "<gray>Choose what visitors and", "<gray>friends can do on your island."), this::protection);
            m.button(15, item(Material.PLAYER_HEAD, "<yellow>Friends", "<gray>Friends: <white>" + i.friends.size()), this::friends);
            m.button(16, item(Material.COMPASS, "<aqua>Visit Islands"), pl -> visit(pl, 0));
            m.button(21, info(i), null);
            m.button(22, glow(item(Material.FEATHER, "<aqua>Get a Sky Feather", "<gray>Use it anywhere to fly home.")),
                    pl -> { pl.closeInventory(); svc.giveFeather(pl, true); });
            m.button(23, item(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, "<green>Set Home Here", "<gray>Stand on your island first."),
                    pl -> { pl.closeInventory(); svc.setHome(pl); });
        }
        m.fill().open(p);
    }

    private org.bukkit.inventory.ItemStack info(Island i) {
        return item(Material.BOOK, "<white>Your Island",
                "<gray>Size: <white>" + i.tier.pretty() + " (" + i.tier.footprint + "x" + i.tier.footprint + ")",
                "<gray>Land pieces: <white>" + i.pieces.size() + "/" + i.tier.maxPieces,
                "<gray>Biomes: <green>" + String.join(", ", i.biomes),
                "<gray>Location: <white>" + i.centerX + ", " + i.centerZ,
                "<dark_gray>Jump off the edge to fall to the overworld.");
    }

    private org.bukkit.inventory.ItemStack upgradeIcon(Island i) {
        Tier next = i.tier.next();
        if (next == null) return item(Material.NETHER_STAR, "<gold>Max Size", "<gray>You're already Continental.");
        return item(Material.EXPERIENCE_BOTTLE, "<gold>Upgrade to " + next.pretty(),
                "<gray>" + i.tier.footprint + "x" + i.tier.footprint + " → <white>" + next.footprint + "x" + next.footprint,
                "<gray>Room for <white>" + next.maxPieces + "</white> pieces of land",
                "<gray>Cost: <gold>" + svc.money(svc.upgradeCost(i)));
    }

    // ---- buying -------------------------------------------------------------------------

    public void buyTier(Player p) {
        Menu m = new Menu(3, "<dark_aqua>Choose an island size");
        Tier[] tiers = Tier.values();
        int[] slots = {11, 12, 13, 14, 15};
        for (int n = 0; n < tiers.length; n++) {
            Tier t = tiers[n];
            m.button(slots[n], item(TIER_ICONS[n], "<green>" + t.pretty(),
                    "<gray>Size: <white>" + t.footprint + " x " + t.footprint,
                    "<gray>Starts with <white>" + t.startPieces + "</white> pieces of land",
                    "<gray>Can grow to <white>" + t.maxPieces + "</white> pieces",
                    "<gray>Price: <gold>" + svc.money(t.price)), pl -> biomes(pl, Mode.BUY, t));
        }
        m.button(18, back(), this::main);
        m.fill().open(p);
    }

    public enum Mode { BUY, GROW, UNLOCK }

    public void biomes(Player p, Mode mode, Tier tier) {
        Island i = plugin.islands().of(p.getUniqueId());
        List<String> list = new ArrayList<>(plugin.library().biomes());
        if (mode == Mode.GROW && i != null) list.retainAll(i.biomes);
        if (mode == Mode.UNLOCK && i != null) list.removeAll(i.biomes);
        String title = switch (mode) {
            case BUY -> "<dark_aqua>Choose your biome";
            case GROW -> "<dark_aqua>Grow which biome?";
            case UNLOCK -> "<dark_aqua>Unlock a new biome";
        };
        int rows = Math.min(6, Math.max(2, (list.size() + 8) / 9 + 1));
        Menu m = new Menu(rows, title);
        int slot = 0;
        for (String b : list) {
            if (slot >= (rows - 1) * 9) break;
            int variants = plugin.library().variants(b).size();
            switch (mode) {
                case BUY -> m.button(slot++, item(biomeIcon(b), "<green>" + pretty(b), "<gray>" + variants + " terrain styles",
                        "<gray>Island: <white>" + tier.pretty() + " for <gold>" + svc.money(tier.price)),
                        pl -> confirm(pl, "Buy a " + tier.pretty() + " " + pretty(b) + " island?", tier.price,
                                yes -> { yes.closeInventory(); svc.buy(yes, tier, b); }, back -> biomes(back, Mode.BUY, tier)));
                case GROW -> m.button(slot++, item(biomeIcon(b), "<green>" + pretty(b), "<gray>Attach one more piece",
                        "<gray>Cost: <gold>" + svc.money(svc.growPrice())),
                        pl -> confirm(pl, "Grow more " + pretty(b) + "?", svc.growPrice(),
                                yes -> { svc.grow(yes, b); biomes(yes, Mode.GROW, null); }, back -> biomes(back, Mode.GROW, null)));
                case UNLOCK -> m.button(slot++, item(biomeIcon(b), "<light_purple>" + pretty(b), "<gray>" + variants + " terrain styles",
                        "<gray>Attaches a new region to your edge", "<gray>Cost: <gold>" + svc.money(svc.unlockPrice(b))),
                        pl -> confirm(pl, "Unlock " + pretty(b) + "?", svc.unlockPrice(b),
                                yes -> { yes.closeInventory(); svc.unlockBiome(yes, b); }, back -> biomes(back, Mode.UNLOCK, null)));
            }
        }
        if (list.isEmpty()) {
            m.button(4, item(Material.BARRIER, "<red>Nothing here",
                    mode == Mode.UNLOCK ? "<gray>You own every biome!" : "<gray>No biomes are set up yet."), null);
        }
        m.button((rows - 1) * 9, back(), mode == Mode.BUY ? this::buyTier : this::main);
        m.fill().open(p);
    }

    public void upgrade(Player p) {
        Island i = plugin.islands().of(p.getUniqueId());
        if (i == null || i.tier.next() == null) return;
        confirm(p, "Upgrade to " + i.tier.next().pretty() + "?", svc.upgradeCost(i), yes -> { svc.upgrade(yes); main(yes); }, this::main);
    }

    /** A yes/no screen. Big targets so it's easy to tap on Bedrock. */
    public void confirm(Player p, String question, double price, Consumer<Player> yes, Consumer<Player> no) {
        Menu m = new Menu(3, "<dark_aqua>Are you sure?");
        m.button(11, item(Material.LIME_CONCRETE, "<green>Yes", "<gray>" + question, "<gray>Cost: <gold>" + svc.money(price)), yes);
        m.button(13, item(Material.GOLD_INGOT, "<gold>" + svc.money(price), "<gray>" + question), null);
        m.button(15, item(Material.RED_CONCRETE, "<red>No, go back"), no);
        m.fill().open(p);
    }

    // ---- protection -------------------------------------------------------------------------

    public void protection(Player p) {
        if (svc.owned(p) == null) return;
        Menu m = new Menu(3, "<dark_aqua>Island protection");
        m.button(11, item(Material.LEATHER_BOOTS, "<yellow>Visitors", "<gray>Everyone who isn't your friend.",
                "<gray>By default they can only use doors."), pl -> flags(pl, Role.VISITOR));
        m.button(13, item(Material.PLAYER_HEAD, "<green>Friends", "<gray>Players you added as friends.",
                "<gray>By default they can do everything", "<gray>except trample farmland."), pl -> flags(pl, Role.FRIEND));
        m.button(15, item(Material.GRASS_BLOCK, "<aqua>Island settings", "<gray>Explosions, fire, mobs, PvP", "<gray>and whether visitors may come."),
                pl -> flags(pl, null));
        m.button(18, back(), this::main);
        m.fill().open(p);
    }

    /** Toggle screen for one role (or island settings when role is null). */
    public void flags(Player p, Role role) {
        Island i = svc.owned(p);
        if (i == null) return;
        Flag.Scope scope = role == null ? Flag.Scope.ISLAND : Flag.Scope.ROLE;
        List<Flag> list = Arrays.stream(Flag.values()).filter(f -> f.scope == scope).toList();
        Menu m = new Menu(4, role == null ? "<dark_aqua>Island settings" : "<dark_aqua>What can " + role.pretty().toLowerCase() + " do?");
        int slot = 0;
        for (Flag f : list) {
            boolean on = i.get(role, f);
            m.button(slot++, toggleIcon(f, on), pl -> { svc.toggle(i, role, f); flags(pl, role); });
        }
        m.button(27, back(), this::protection);
        if (role != null) {
            m.button(30, item(Material.LIME_DYE, "<green>Allow everything", "<gray>Turn every option on for " + role.pretty().toLowerCase() + "."),
                    pl -> { list.forEach(f -> i.set(role, f, true)); plugin.islands().save(); flags(pl, role); });
            m.button(31, item(Material.GRAY_DYE, "<white>Reset to default"),
                    pl -> { list.forEach(f -> i.set(role, f, role == Role.FRIEND ? f.defaultFriend : f.defaultVisitor)); plugin.islands().save(); flags(pl, role); });
            m.button(32, item(Material.RED_DYE, "<red>Block everything", "<gray>Turn every option off for " + role.pretty().toLowerCase() + "."),
                    pl -> { list.forEach(f -> i.set(role, f, false)); plugin.islands().save(); flags(pl, role); });
        }
        m.fill().open(p);
    }

    private static org.bukkit.inventory.ItemStack toggleIcon(Flag f, boolean on) {
        var it = item(f.icon, (on ? "<green>" : "<red>") + f.title + ": " + (on ? "ALLOWED" : "BLOCKED"),
                "<gray>" + f.description, "", on ? "<yellow>Tap to block" : "<yellow>Tap to allow");
        return on ? glow(it) : it;
    }

    // ---- friends -------------------------------------------------------------------------

    public void friends(Player p) {
        Island i = svc.owned(p);
        if (i == null) return;
        Menu m = new Menu(6, "<dark_aqua>Friends");
        int slot = 0;
        for (UUID id : i.friends) {
            if (slot >= 45) break;
            OfflinePlayer f = Bukkit.getOfflinePlayer(id);
            String name = Objects.toString(f.getName(), "Unknown");
            m.button(slot++, head(f, "<green>" + name, f.isOnline() ? "<green>Online" : "<gray>Offline", "<yellow>Tap to remove"),
                    pl -> confirm(pl, "Remove " + name + " as a friend?", 0,
                            yes -> { svc.removeFriend(yes, f); friends(yes); }, this::friends));
        }
        m.button(45, back(), this::main);
        m.button(49, glow(item(Material.EMERALD, "<green>Add a friend", "<gray>Pick from online players.")), pl -> pickPlayer(pl, 0));
        m.button(53, item(Material.SHIELD, "<yellow>What can friends do?"), pl -> flags(pl, Role.FRIEND));
        m.fill().open(p);
    }

    /** Pick an online player to befriend. No typing needed, which matters on Bedrock. */
    public void pickPlayer(Player p, int page) {
        Island i = svc.owned(p);
        if (i == null) return;
        List<Player> online = Bukkit.getOnlinePlayers().stream()
                .filter(o -> !o.equals(p) && !i.friends.contains(o.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .map(o -> (Player) o).toList();
        Menu m = new Menu(6, "<dark_aqua>Add a friend");
        paged(m, online, page, o -> head(o, "<green>" + o.getName(), "<yellow>Tap to add as a friend"),
                (pl, o) -> { svc.addFriend(pl, o); friends(pl); }, pg -> pickPlayer(p, pg));
        if (online.isEmpty()) m.button(22, item(Material.BARRIER, "<red>No one else is online"), null);
        m.button(45, back(), this::friends);
        m.fill().open(p);
    }

    // ---- visiting -------------------------------------------------------------------------

    public void visit(Player p, int page) {
        List<Island> open = plugin.islands().all().stream()
                .filter(i -> !i.owner.equals(p.getUniqueId()))
                .filter(i -> i.setting(Flag.VISITS) || i.friends.contains(p.getUniqueId()))
                .sorted(Comparator.comparing((Island i) -> Bukkit.getPlayer(i.owner) == null)
                        .thenComparing(i -> -i.pieces.size()))
                .toList();
        Menu m = new Menu(6, "<dark_aqua>Visit an island");
        paged(m, open, page, i -> {
            OfflinePlayer o = Bukkit.getOfflinePlayer(i.owner);
            return head(o, "<green>" + Objects.toString(o.getName(), "?") + "'s island",
                    "<gray>" + i.tier.pretty() + " · " + String.join(", ", i.biomes),
                    o.isOnline() ? "<green>Owner online" : "<gray>Owner offline",
                    i.friends.contains(p.getUniqueId()) ? "<aqua>You're a friend here" : "", "<yellow>Tap to visit");
        }, (pl, i) -> { pl.closeInventory(); svc.visit(pl, Bukkit.getOfflinePlayer(i.owner)); }, pg -> visit(p, pg));
        if (open.isEmpty()) m.button(22, item(Material.BARRIER, "<red>No islands open for visits yet"), null);
        m.button(45, back(), this::main);
        m.fill().open(p);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private <T> void paged(Menu m, List<T> items, int page, java.util.function.Function<T, org.bukkit.inventory.ItemStack> icon,
                           java.util.function.BiConsumer<Player, T> onClick, Consumer<Integer> openPage) {
        int per = 45, pages = Math.max(1, (items.size() + per - 1) / per);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int n = 0; n < per && page * per + n < items.size(); n++) {
            T t = items.get(page * per + n);
            m.button(n, icon.apply(t), pl -> onClick.accept(pl, t));
        }
        int pg = page;
        if (page > 0) m.button(48, item(Material.ARROW, "<white>Previous page"), pl -> openPage.accept(pg - 1));
        if (page < pages - 1) m.button(50, item(Material.ARROW, "<white>Next page"), pl -> openPage.accept(pg + 1));
        if (pages > 1) m.button(49, item(Material.PAPER, "<gray>Page " + (page + 1) + "/" + pages), null);
    }

    private static org.bukkit.inventory.ItemStack back() { return item(Material.ARROW, "<white>Back"); }

    private Material biomeIcon(String biome) {
        String configured = plugin.getConfig().getString("biome-icons." + biome);
        Material m = configured == null ? null : Material.matchMaterial(configured);
        if (m != null && m.isItem()) return m;
        String b = biome.toUpperCase(Locale.ROOT);
        for (String guess : new String[]{b + "_SAPLING", b + "_LOG", b + "_LEAVES", b + "_BLOCK", b}) {
            m = Material.matchMaterial(guess);
            if (m != null && m.isItem()) return m;
        }
        if (b.contains("DESERT") || b.contains("BEACH")) return Material.SAND;
        if (b.contains("SNOW") || b.contains("ICE") || b.contains("FROZEN")) return Material.SNOW_BLOCK;
        if (b.contains("BADLANDS") || b.contains("MESA")) return Material.TERRACOTTA;
        if (b.contains("MUSHROOM")) return Material.RED_MUSHROOM_BLOCK;
        if (b.contains("JUNGLE")) return Material.JUNGLE_SAPLING;
        if (b.contains("CHERRY")) return Material.CHERRY_SAPLING;
        if (b.contains("SWAMP")) return Material.LILY_PAD;
        if (b.contains("TAIGA") || b.contains("SPRUCE")) return Material.SPRUCE_SAPLING;
        if (b.contains("SAVANNA")) return Material.ACACIA_SAPLING;
        if (b.contains("DARK")) return Material.DARK_OAK_SAPLING;
        if (b.contains("BIRCH")) return Material.BIRCH_SAPLING;
        if (b.contains("FLOWER") || b.contains("MEADOW")) return Material.POPPY;
        if (b.contains("MOUNTAIN") || b.contains("PEAK") || b.contains("STONY")) return Material.STONE;
        if (b.contains("FOREST")) return Material.OAK_SAPLING;
        return Material.GRASS_BLOCK;
    }

    static String pretty(String biome) {
        StringBuilder sb = new StringBuilder();
        for (String w : biome.split("_")) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
