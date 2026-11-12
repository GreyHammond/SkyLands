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

    /** Top level: Return/Buy, Manage, Delete. */
    public void main(Player p) {
        Island i = plugin.islands().of(p.getUniqueId());
        Menu m = new Menu(3, "<dark_aqua>Skyland");
        if (i == null) {
            m.button(11, glow(item(Material.GRASS_BLOCK, "<green>Buy an Island", "<gray>Pick a size and a biome,",
                    "<gray>or visit other players' islands.")), this::buyTier);
            var archived = plugin.archiver().info(p.getUniqueId());
            if (archived != null) {
                m.button(13, glow(item(Material.RECOVERY_COMPASS, "<aqua>Restore My Island",
                        "<gray>Your island was archived while", "<gray>you were away. Bring it back",
                        "<gray>exactly as you left it, for free.", "<yellow>" + archived.daysLeft() + " days left")),
                        pl -> confirm(pl, "Restore your archived island?", 0,
                                yes -> { yes.closeInventory(); svc.restoreOwn(yes); }, this::main));
            } else {
                m.button(13, item(Material.GRAY_DYE, "<gray>Manage Island", "<dark_gray>You don't have an island yet."), null);
            }
            m.button(15, item(Material.GRAY_DYE, "<gray>Delete Island", "<dark_gray>You don't have an island yet."), null);
        } else {
            boolean owner = i.owner.equals(p.getUniqueId());
            m.button(11, glow(item(Material.ELYTRA, "<green>Return to Island", "<gray>Ride the wind home,",
                    "<gray>or visit other islands.")), this::travel);
            m.button(13, item(Material.WRITABLE_BOOK, "<yellow>Manage Island", "<gray>Grow, upgrade, biomes,",
                    "<gray>protection, friends" + (owner ? ", co-owners." : "."),
                    owner ? "" : "<aqua>You co-own " + nameOf(i.owner) + "'s island"), this::manage);
            if (owner) m.button(15, item(Material.TNT, "<red>Delete Island", "<gray>Remove your island for good."), this::delete);
            else m.button(15, item(Material.OAK_DOOR, "<red>Leave Island", "<gray>Stop co-owning " + nameOf(i.owner) + "'s island."),
                    pl -> confirm(pl, "Stop co-owning " + nameOf(i.owner) + "'s island?", 0,
                            yes -> { yes.closeInventory(); svc.leaveIsland(yes); }, this::main));
        }
        m.fill().open(p);
    }

    /** Return / visit. */
    public void travel(Player p) {
        Island i = plugin.islands().of(p.getUniqueId());
        Menu m = new Menu(3, "<dark_aqua>Return to Island");
        if (i != null) {
            long cd = svc.cooldownLeft(p);
            m.button(11, glow(item(Material.ELYTRA, "<green>Return to My Island",
                    "<gray>A gust of wind carries you home.", cd > 0 ? "<red>Ready in " + cd + "s" : "<yellow>Tap to fly")),
                    pl -> { pl.closeInventory(); svc.launchHome(pl); });
        }
        List<Island> friendOf = plugin.islands().friendOf(p.getUniqueId());
        m.button(13, item(Material.PLAYER_HEAD, "<aqua>Friends' Islands", "<gray>Islands where you're a friend: <white>" + friendOf.size()),
                pl -> islandList(pl, friendOf, "<dark_aqua>Friends' islands", 0, this::travel));
        m.button(15, item(Material.COMPASS, "<aqua>Visit Islands", "<gray>Every island open to visitors."), pl -> visit(pl, 0));
        m.button(22, item(Material.GOLDEN_HELMET, "<gold>Top Islands", "<gray>The highest level islands", "<gray>on the server."), pl -> topIslands(pl, 0));
        m.button(18, back(), this::main);
        m.fill().open(p);
    }

    /** Everything an owner can change. */
    public void manage(Player p) {
        Island i = svc.owned(p);
        if (i == null) { main(p); return; }
        Menu m = new Menu(4, "<dark_aqua>Manage Island");
        m.button(4, levelCard(i), this::levels);
        m.button(10, item(Material.WHEAT_SEEDS, "<green>Grow Land",
                "<gray>Attach more of a biome you own.", "<gray>Cost: <gold>" + svc.money(svc.growPrice()) + " <gray>each",
                "<gray>Pieces: <white>" + i.pieces.size() + "/" + i.tier.maxPieces), pl -> biomes(pl, Mode.GROW, null));
        m.button(11, item(Material.CHERRY_SAPLING, "<light_purple>Unlock a Biome",
                "<gray>Unlock a new biome so you", "<gray>can grow it with Grow Land."), pl -> biomes(pl, Mode.UNLOCK, null));
        m.button(12, upgradeIcon(i), this::upgrade);
        var cur = dev.skyrealms.terrain.UndersideStyle.byId(i.underside);
        m.button(13, item(cur.icon, "<aqua>Underside Style", "<gray>What hangs under new land.",
                "<gray>Current: <white>" + cur.displayName, "<dark_gray>Existing land isn't changed."), this::undersides);
        m.button(14, item(Material.SHIELD, "<yellow>Protection",
                "<gray>Choose what visitors and", "<gray>friends can do on your island."), this::protection);
        m.button(15, item(Material.PLAYER_HEAD, "<yellow>Friends", "<gray>Friends: <white>" + i.friends.size()), this::friends);
        m.button(16, item(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, "<green>Set Home Here",
                "<gray>Where Return to Island drops you.", "<gray>Stand on your island first."),
                pl -> { pl.closeInventory(); svc.setHome(pl); });
        boolean owner = i.owner.equals(p.getUniqueId());
        m.button(20, owner ? item(Material.GOLDEN_HELMET, "<gold>Co-owners", "<gray>Players who can manage the",
                        "<gray>island with you. Co-owners: <white>" + i.coOwners.size())
                : item(Material.GRAY_DYE, "<gray>Co-owners", "<dark_gray>Only the owner can change this."), owner ? this::coOwners : null);
        m.button(22, info(i), null);
        m.button(24, owner ? item(Material.NAME_TAG, "<gold>Transfer Island", "<gray>Give the island to someone else.",
                        "<red>You lose all access to it.")
                : item(Material.GRAY_DYE, "<gray>Transfer Island", "<dark_gray>Only the owner can do this."), owner ? this::transfer : null);
        m.button(31, glow(item(Material.ALLIUM, "<light_purple>Cosmetics & Themes", "<gray>Island name, particles, border,",
                "<gray>floating islets and themes.", "<dark_gray>More unlock as your island levels up.")), this::cosmetics);
        m.button(27, back(), this::main);
        m.fill().open(p);
    }

    /** Two confirmations before anything is destroyed. */
    public void delete(Player p) {
        Island i = svc.ownerOnly(p);
        if (i == null) { main(p); return; }
        double refund = svc.deleteRefund(i);
        Menu m = new Menu(3, "<dark_red>Delete your island?");
        m.button(11, item(Material.RED_CONCRETE, "<red>Yes, delete it",
                "<gray>Every block, chest and animal on", "<gray>your island will be gone forever.",
                refund > 0 ? "<gray>Refund: <gold>" + svc.money(refund) : "<gray>No refund."), this::deleteFinal);
        m.button(13, info(i), null);
        m.button(15, item(Material.LIME_CONCRETE, "<green>No, keep my island"), this::main);
        m.fill().open(p);
    }

    private void deleteFinal(Player p) {
        if (svc.ownerOnly(p) == null) return;
        Menu m = new Menu(3, "<dark_red>This can't be undone!");
        m.button(11, item(Material.LIME_CONCRETE, "<green>Cancel", "<gray>Keep my island."), this::main);
        m.button(13, item(Material.BARRIER, "<red>Last chance", "<gray>Are you really sure?"), null);
        m.button(15, glow(item(Material.TNT, "<dark_red>DELETE FOREVER", "<gray>Your island will crumble", "<gray>into the clouds.")),
                pl -> { pl.closeInventory(); svc.deleteIsland(pl); });
        m.fill().open(p);
    }

    private org.bukkit.inventory.ItemStack info(Island i) {
        return item(Material.BOOK, "<white>" + plugin.cosmetics().displayName(i), "<gold>" + plugin.xp().badge(i),
                "<gray>Size: <white>" + i.tier.pretty() + " (" + i.tier.footprint + "x" + i.tier.footprint + ")",
                "<gray>Land pieces: <white>" + i.pieces.size() + "/" + i.tier.maxPieces,
                "<gray>Biomes: <green>" + String.join(", ", i.biomes),
                "<gray>Location: <white>" + i.centerX + ", " + i.centerZ,
                "<gray>Owner: <white>" + nameOf(i.owner) + (i.coOwners.isEmpty() ? "" : " <gray>+ " + i.coOwners.size() + " co-owner(s)"),
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
        m.button(26, item(Material.COMPASS, "<aqua>Visit Islands", "<gray>Look around before you buy."), pl -> visit(pl, 0));
        m.fill().open(p);
    }

    public enum Mode { BUY, GROW, UNLOCK }

    public void biomes(Player p, Mode mode, Tier tier) {
        Island i = plugin.islands().of(p.getUniqueId());
        List<String> list = new ArrayList<>(plugin.biomes().all());
        if (mode == Mode.BUY) list.removeIf(b -> !plugin.biomes().startable(b));
        if (mode == Mode.GROW && i != null) list.retainAll(i.biomes);
        if (mode == Mode.UNLOCK && i != null) list.removeAll(i.biomes);
        var lv = plugin.xp().levels();
        int islandLevel = mode == Mode.BUY || i == null ? 1 : plugin.xp().level(i);
        if (mode != Mode.GROW) list.sort(Comparator.comparingInt(lv::biomeLevel));
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
            String style = plugin.biomes().builtIn(b) ? describe(b) : plugin.library().variants(b).size() + " captured terrain styles";
            int need = lv.biomeLevel(b);
            if (mode != Mode.GROW && need > islandLevel) {
                m.button(slot++, item(Material.GRAY_DYE, "<gray>" + pretty(b) + " <dark_gray>(locked)", "<dark_gray>" + style,
                        "<red>Unlocks at island level " + need,
                        mode == Mode.BUY ? "<dark_gray>New islands start at level 1." : "<dark_gray>Your island is level " + islandLevel + "."), null);
                continue;
            }
            switch (mode) {
                case BUY -> m.button(slot++, item(biomeIcon(b), "<green>" + pretty(b), "<gray>" + style,
                        "<gray>Island: <white>" + tier.pretty() + " for <gold>" + svc.money(tier.price)),
                        pl -> confirm(pl, "Buy a " + tier.pretty() + " " + pretty(b) + " island?", tier.price,
                                yes -> { yes.closeInventory(); svc.buy(yes, tier, b); }, back -> biomes(back, Mode.BUY, tier)));
                case GROW -> m.button(slot++, item(biomeIcon(b), "<green>" + pretty(b),
                        i != null && i.pieces.stream().noneMatch(pc -> pc.biome().equals(b))
                                ? "<yellow>New! First piece attaches at your island's edge" : "<gray>Attach one more piece",
                        "<gray>Cost: <gold>" + svc.money(svc.growPrice())),
                        pl -> confirm(pl, "Grow more " + pretty(b) + "?", svc.growPrice(),
                                yes -> { svc.grow(yes, b); biomes(yes, Mode.GROW, null); }, back -> biomes(back, Mode.GROW, null)));
                case UNLOCK -> m.button(slot++, item(biomeIcon(b), "<light_purple>" + pretty(b), "<gray>" + style,
                        "<gray>Unlocks it for Grow Land", "<gray>Cost: <gold>" + svc.money(svc.unlockPrice(b))),
                        pl -> confirm(pl, "Unlock " + pretty(b) + "?", svc.unlockPrice(b),
                                yes -> { if (svc.unlockBiome(yes, b)) biomes(yes, Mode.GROW, null); else yes.closeInventory(); },
                                back -> biomes(back, Mode.UNLOCK, null)));
            }
        }
        if (list.isEmpty()) {
            m.button(4, item(Material.BARRIER, "<red>Nothing here",
                    mode == Mode.UNLOCK ? "<gray>You own every biome!" : "<gray>No biomes are set up yet."), null);
        }
        m.button((rows - 1) * 9, back(), mode == Mode.BUY ? this::buyTier : this::manage);
        m.fill().open(p);
    }

    public void upgrade(Player p) {
        Island i = plugin.islands().of(p.getUniqueId());
        if (i == null || i.tier.next() == null) return;
        confirm(p, "Upgrade to " + i.tier.next().pretty() + "?", svc.upgradeCost(i), yes -> { svc.upgrade(yes); manage(yes); }, this::manage);
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
        m.button(18, back(), this::manage);
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
        m.button(45, back(), this::manage);
        m.button(49, glow(item(Material.EMERALD, "<green>Add a friend", "<gray>Pick from online players.")), pl -> pickPlayer(pl, 0));
        m.button(53, item(Material.SHIELD, "<yellow>What can friends do?"), pl -> flags(pl, Role.FRIEND));
        m.fill().open(p);
    }

    /** Pick an online player to befriend. No typing needed, which matters on Bedrock. */
    public void pickPlayer(Player p, int page) {
        Island i = svc.owned(p);
        if (i == null) return;
        pickOnline(p, page, "<dark_aqua>Add a friend", "<yellow>Tap to add as a friend",
                o -> !i.roleOf(o.getUniqueId()).manages() && !i.friends.contains(o.getUniqueId()),
                (pl, o) -> { svc.addFriend(pl, o); friends(pl); }, this::friends);
    }

    private void pickOnline(Player p, int page, String title, String hint, java.util.function.Predicate<Player> filter,
                            java.util.function.BiConsumer<Player, Player> onPick, Consumer<Player> back) {
        List<Player> online = Bukkit.getOnlinePlayers().stream()
                .filter(o -> !o.equals(p)).map(o -> (Player) o).filter(filter)
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        Menu m = new Menu(6, title);
        paged(m, online, page, o -> head(o, "<green>" + o.getName(), hint), onPick,
                pg -> pickOnline(p, pg, title, hint, filter, onPick, back));
        if (online.isEmpty()) m.button(22, item(Material.BARRIER, "<red>No one else to pick", "<gray>They need to be online."), null);
        m.button(45, back(), back);
        m.fill().open(p);
    }

    // ---- co-owners & transfer --------------------------------------------------------------

    public void coOwners(Player p) {
        Island i = svc.ownerOnly(p);
        if (i == null) return;
        Menu m = new Menu(6, "<dark_aqua>Co-owners");
        int slot = 0;
        for (UUID id : i.coOwners) {
            if (slot >= 45) break;
            OfflinePlayer c = Bukkit.getOfflinePlayer(id);
            String name = Objects.toString(c.getName(), "Unknown");
            m.button(slot++, head(c, "<gold>" + name, c.isOnline() ? "<green>Online" : "<gray>Offline", "<yellow>Tap to remove"),
                    pl -> confirm(pl, "Remove " + name + " as a co-owner?", 0,
                            yes -> { svc.removeCoOwner(yes, c); coOwners(yes); }, this::coOwners));
        }
        if (i.coOwners.isEmpty()) m.button(22, item(Material.GOLDEN_HELMET, "<gray>No co-owners yet",
                "<gray>Co-owners can grow, unlock, upgrade,", "<gray>and change protection and friends.",
                "<gray>Only you can delete or transfer."), null);
        m.button(45, back(), this::manage);
        m.button(49, glow(item(Material.EMERALD, "<green>Add a co-owner", "<gray>Pick from online players.",
                "<gray>They can't already own an island.")),
                pl -> pickOnline(pl, 0, "<dark_aqua>Add a co-owner", "<yellow>Tap to make co-owner",
                        o -> plugin.islands().of(o.getUniqueId()) == null,
                        (who, o) -> confirm(who, "Make " + o.getName() + " a co-owner?", 0,
                                yes -> { svc.addCoOwner(yes, o); coOwners(yes); }, this::coOwners),
                        this::coOwners));
        m.fill().open(p);
    }

    /** Pick who gets the island: co-owners first, then anyone online without an island. */
    public void transfer(Player p) {
        Island i = svc.ownerOnly(p);
        if (i == null) return;
        Menu m = new Menu(6, "<dark_aqua>Give your island to...");
        List<OfflinePlayer> people = new ArrayList<>();
        for (UUID c : i.coOwners) people.add(Bukkit.getOfflinePlayer(c));
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p) || i.coOwners.contains(o.getUniqueId()) || plugin.islands().of(o.getUniqueId()) != null) continue;
            people.add(o);
        }
        paged(m, people, 0, o -> head(o, "<gold>" + Objects.toString(o.getName(), "?"),
                        i.coOwners.contains(o.getUniqueId()) ? "<aqua>Co-owner" : "<gray>Online player", "<yellow>Tap to choose"),
                (pl, o) -> transferConfirm(pl, o), pg -> transfer(p));
        if (people.isEmpty()) m.button(22, item(Material.BARRIER, "<red>No one to give it to",
                "<gray>Add a co-owner, or ask the player", "<gray>to come online."), null);
        m.button(45, back(), this::manage);
        m.fill().open(p);
    }

    private void transferConfirm(Player p, OfflinePlayer target) {
        String name = Objects.toString(target.getName(), "them");
        Menu m = new Menu(3, "<dark_red>Give your island away?");
        m.button(11, item(Material.LIME_CONCRETE, "<green>Cancel", "<gray>Keep my island."), this::manage);
        m.button(13, head(target, "<gold>" + name, "<gray>will become the owner.", "<red>You become a visitor and",
                "<red>lose all access immediately."), null);
        m.button(15, glow(item(Material.NAME_TAG, "<dark_red>GIVE IT TO " + name.toUpperCase(),
                "<gray>This can't be undone.")), pl -> { pl.closeInventory(); svc.transfer(pl, target); });
        m.fill().open(p);
    }

    private static String nameOf(UUID id) { return Objects.toString(Bukkit.getOfflinePlayer(id).getName(), "someone"); }

    // ---- visiting -------------------------------------------------------------------------

    public void visit(Player p, int page) {
        List<Island> open = plugin.islands().all().stream()
                .filter(i -> !i.owner.equals(p.getUniqueId()))
                .filter(i -> i.setting(Flag.VISITS) || i.friends.contains(p.getUniqueId()))
                .toList();
        islandList(p, open, "<dark_aqua>Visit an island", page,
                plugin.islands().of(p.getUniqueId()) == null ? this::buyTier : this::travel);
    }

    private void islandList(Player p, List<Island> islands, String title, int page, Consumer<Player> back) {
        List<Island> sorted = islands.stream()
                .sorted(Comparator.comparing((Island i) -> Bukkit.getPlayer(i.owner) == null).thenComparing(i -> -i.xp))
                .toList();
        showIslands(p, sorted, title, page, back, false);
    }

    /** Top Islands: everyone open to visitors (plus your own and friends'), ranked by level, then visits. */
    public void topIslands(Player p, int page) {
        List<Island> ranked = plugin.islands().all().stream()
                .filter(i -> i.setting(Flag.VISITS) || i.roleOf(p.getUniqueId()) != Role.VISITOR)
                .sorted(Comparator.comparingLong((Island i) -> -i.xp).thenComparingLong(i -> -i.visitorsTotal))
                .toList();
        showIslands(p, ranked, "<dark_aqua>Top Islands", page, this::travel, true);
    }

    private void showIslands(Player p, List<Island> list, String title, int page, Consumer<Player> back, boolean ranked) {
        Menu m = new Menu(6, title);
        paged(m, list, page, i -> {
            OfflinePlayer o = Bukkit.getOfflinePlayer(i.owner);
            String rank = ranked ? "<gold>#" + (list.indexOf(i) + 1) + " " : "";
            Role r = i.roleOf(p.getUniqueId());
            return head(o, rank + "<green>" + plugin.cosmetics().displayName(i),
                    "<gold>" + plugin.xp().badge(i) + (i.name != null ? " <gray>· by " + Objects.toString(o.getName(), "?") : ""),
                    "<gray>" + i.tier.pretty() + " · " + i.biomes.size() + " biomes · " + String.format("%,d", i.visitorsTotal) + " visits",
                    o.isOnline() ? "<green>Owner online" : "<gray>Owner offline",
                    r == Role.OWNER ? "<aqua>Your island" : r == Role.COOWNER ? "<aqua>You co-own this" : r == Role.FRIEND ? "<aqua>You're a friend here" : "",
                    "<yellow>Tap to visit");
        }, (pl, i) -> { pl.closeInventory(); svc.visit(pl, Bukkit.getOfflinePlayer(i.owner)); },
                pg -> showIslands(p, list, title, pg, back, ranked));
        if (list.isEmpty()) m.button(22, item(Material.BARRIER, "<red>No islands here yet"), null);
        m.button(45, back(), back);
        m.fill().open(p);
    }

    // ---- cosmetics & themes ----------------------------------------------------------------

    public void cosmetics(Player p) {
        Island i = svc.owned(p);
        if (i == null) return;
        var cs = plugin.cosmetics();
        int level = plugin.xp().level(i);
        var theme = dev.skyrealms.cosmetic.Cosmetics.Theme.byId(i.theme);
        Menu m = new Menu(3, "<dark_aqua>Cosmetics & Themes");
        m.button(10, item(theme != null ? theme.icon : Material.PAINTING, "<light_purple>Themes",
                "<gray>One tap: underside + particles + border.", "<gray>Current: <white>" + (theme != null ? theme.displayName : "none")), this::themes);
        var ps = dev.skyrealms.cosmetic.Cosmetics.ParticleSet.byId(i.particles);
        m.button(11, item(ps.icon == Material.BARRIER ? Material.GLOWSTONE_DUST : ps.icon, "<aqua>Particles",
                "<gray>Ambient effects on your island.", "<gray>Current: <white>" + ps.displayName), pl -> options(pl, "particles"));
        var bs = dev.skyrealms.cosmetic.Cosmetics.BorderStyle.byId(i.border);
        m.button(12, item(bs.icon == Material.BARRIER ? Material.WHITE_DYE : bs.icon, "<aqua>Border Style",
                "<gray>Lights up when you're near the edge.", "<gray>Current: <white>" + bs.displayName), pl -> options(pl, "border"));
        var ds = dev.skyrealms.cosmetic.Cosmetics.DecorationSet.byId(i.decorations);
        m.button(13, item(ds.icon == Material.BARRIER ? Material.MOSS_BLOCK : ds.icon, "<aqua>Floating Decorations",
                "<gray>Little islets floating around.", "<gray>Current: <white>" + ds.displayName), pl -> options(pl, "decorations"));
        boolean canName = level >= plugin.xp().levels().nameLevel;
        m.button(15, canName ? item(Material.NAME_TAG, "<gold>Rename Island", "<gray>Now: <white>" + cs.displayName(i),
                        "<yellow>Tap, then type the name in chat")
                : item(Material.GRAY_DYE, "<gray>Rename Island <dark_gray>(locked)", "<red>Unlocks at island level " + plugin.xp().levels().nameLevel),
                canName ? pl -> plugin.namePrompt().ask(pl) : null);
        var us = dev.skyrealms.terrain.UndersideStyle.byId(i.underside);
        m.button(16, item(us.icon, "<aqua>Underside Style", "<gray>Current: <white>" + us.displayName), this::undersides);
        m.button(18, back(), this::manage);
        m.fill().open(p);
    }

    public void themes(Player p) {
        Island i = svc.owned(p);
        if (i == null) return;
        int level = plugin.xp().level(i);
        Menu m = new Menu(3, "<dark_aqua>Themes");
        int slot = 10;
        for (var t : dev.skyrealms.cosmetic.Cosmetics.Theme.values()) {
            int need = plugin.cosmetics().need(t);
            String parts = dev.skyrealms.terrain.UndersideStyle.byId(t.underside).displayName + " underside · "
                    + t.particles.displayName + " particles · " + t.border.displayName + " border";
            if (level < need) {
                m.button(slot++, item(Material.GRAY_DYE, "<gray>" + t.displayName + " <dark_gray>(locked)", "<dark_gray>" + parts,
                        "<red>Unlocks at island level " + need), null);
                continue;
            }
            boolean current = t.id.equals(i.theme);
            var icon = item(t.icon, (current ? "<green>✔ " : "<light_purple>") + t.displayName, "<gray>" + parts,
                    "<dark_gray>Underside applies to new land only.", current ? "<green>In use" : "<yellow>Tap to apply");
            m.button(slot++, current ? glow(icon) : icon, pl -> { plugin.cosmetics().applyTheme(pl, t); themes(pl); });
        }
        m.button(18, back(), this::cosmetics);
        m.fill().open(p);
    }

    /** Pick particles, border style or decorations. */
    public void options(Player p, String category) {
        Island i = svc.owned(p);
        if (i == null) return;
        int level = plugin.xp().level(i);
        Enum<?>[] values = switch (category) {
            case "particles" -> dev.skyrealms.cosmetic.Cosmetics.ParticleSet.values();
            case "border" -> dev.skyrealms.cosmetic.Cosmetics.BorderStyle.values();
            default -> dev.skyrealms.cosmetic.Cosmetics.DecorationSet.values();
        };
        String title = switch (category) { case "particles" -> "Particles"; case "border" -> "Border Style"; default -> "Floating Decorations"; };
        String currentId = switch (category) { case "particles" -> i.particles; case "border" -> i.border; default -> i.decorations; };
        Menu m = new Menu(3, "<dark_aqua>" + title);
        int slot = 10;
        for (Enum<?> v : values) {
            String id, name, desc; Material icon;
            switch (v) {
                case dev.skyrealms.cosmetic.Cosmetics.ParticleSet x -> { id = x.id; name = x.displayName; desc = x.description; icon = x.icon; }
                case dev.skyrealms.cosmetic.Cosmetics.BorderStyle x -> { id = x.id; name = x.displayName; desc = x.description; icon = x.icon; }
                case dev.skyrealms.cosmetic.Cosmetics.DecorationSet x -> { id = x.id; name = x.displayName; desc = x.description; icon = x.icon; }
                default -> { continue; }
            }
            int need = plugin.cosmetics().need(v);
            if (level < need) {
                m.button(slot++, item(Material.GRAY_DYE, "<gray>" + name + " <dark_gray>(locked)", "<dark_gray>" + desc,
                        "<red>Unlocks at island level " + need), null);
                continue;
            }
            boolean current = id.equals(currentId);
            List<String> lore = new ArrayList<>(List.of("<gray>" + desc));
            if (category.equals("decorations") && !id.equals("none")) lore.add("<dark_gray>Changing this rebuilds the islets.");
            lore.add(current ? "<green>In use" : "<yellow>Tap to use");
            var it = item(icon, (current ? "<green>✔ " : "<aqua>") + name, lore.toArray(String[]::new));
            m.button(slot++, current ? glow(it) : it, pl -> {
                switch (v) {
                    case dev.skyrealms.cosmetic.Cosmetics.ParticleSet x -> plugin.cosmetics().setParticles(pl, x);
                    case dev.skyrealms.cosmetic.Cosmetics.BorderStyle x -> plugin.cosmetics().setBorder(pl, x);
                    case dev.skyrealms.cosmetic.Cosmetics.DecorationSet x -> {
                        if (!i.islets.isEmpty()) {
                            confirm(pl, "Rebuild floating islets? Anything built on them is removed.", 0,
                                    yes -> { plugin.cosmetics().setDecorations(yes, x); options(yes, category); }, back -> options(back, category));
                            return;
                        }
                        plugin.cosmetics().setDecorations(pl, x);
                    }
                    default -> { }
                }
                options(pl, category);
            });
        }
        m.button(18, back(), this::cosmetics);
        m.fill().open(p);
    }

    // ---- underside styles -------------------------------------------------------------------

    public void undersides(Player p) {
        Island i = svc.owned(p);
        if (i == null) return;
        int level = plugin.xp().level(i);
        var lv = plugin.xp().levels();
        Menu m = new Menu(3, "<dark_aqua>Underside Style");
        int slot = 9;
        for (var st : dev.skyrealms.terrain.UndersideStyle.values()) {
            int need = lv.undersideLevel(st.id);
            boolean current = st.id.equals(i.underside);
            if (level < need) {
                m.button(slot++, item(Material.GRAY_DYE, "<gray>" + st.displayName + " <dark_gray>(locked)", "<dark_gray>" + st.description,
                        "<red>Unlocks at island level " + need), null);
                continue;
            }
            var icon = item(st.icon, (current ? "<green>✔ " : "<aqua>") + st.displayName, "<gray>" + st.description,
                    current ? "<green>In use for new land" : "<yellow>Tap to use for new land");
            m.button(slot++, current ? glow(icon) : icon, pl -> { svc.setUnderside(pl, st); undersides(pl); });
        }
        m.button(4, item(Material.BOOK, "<white>How it works", "<gray>Land you grow from now on hangs",
                "<gray>this style underneath. Land you", "<gray>already have is never changed."), null);
        m.button(18, back(), this::manage);
        m.fill().open(p);
    }

    // ---- levels ----------------------------------------------------------------------------

    private org.bukkit.inventory.ItemStack levelCard(Island i) {
        var lv = plugin.xp().levels();
        int level = plugin.xp().level(i);
        List<String> lore = new ArrayList<>();
        lore.add(plugin.xp().progressBar(i));
        lore.add("<gray>Total XP: <white>" + String.format("%,d", i.xp));
        String next = nextUnlock(level);
        if (next != null) lore.add("<gray>Next: " + next);
        lore.add("");
        lore.add("<gray>Earn XP by growing, unlocking,");
        lore.add("<gray>building, farming and visitors.");
        lore.add("<yellow>Tap to see all unlocks");
        return glow(item(Material.EXPERIENCE_BOTTLE, "<gold>Island Level " + level + " <gray>· <yellow>" + lv.title(level),
                lore.toArray(String[]::new)));
    }

    /** "Lv 15: Jungle, Badlands" for the next level that unlocks something. */
    private String nextUnlock(int level) {
        var lv = plugin.xp().levels();
        for (int l = level + 1; l <= lv.maxLevel; l++) {
            List<String> what = unlocksAt(l);
            if (!what.isEmpty()) return "<white>Lv " + l + "<gray>: " + String.join(", ", what);
        }
        return null;
    }

    private List<String> unlocksAt(int l) {
        var lv = plugin.xp().levels();
        List<String> out = new ArrayList<>();
        for (String b : plugin.biomes().all()) if (lv.biomeLevel(b) == l && l > 1) out.add(pretty(b));
        for (var st : dev.skyrealms.terrain.UndersideStyle.values())
            if (lv.undersideLevel(st.id) == l && l > 1) out.add(st.displayName + " underside");
        out.addAll(plugin.cosmetics().unlocksAt(l));
        String t = lv.titles().get(l);
        if (t != null) out.add("title \"" + t + "\"");
        return out;
    }

    /** Every level that unlocks something, reached ones lit up. */
    public void levels(Player p) {
        Island i = plugin.islands().of(p.getUniqueId());
        if (i == null) { main(p); return; }
        var lv = plugin.xp().levels();
        int level = plugin.xp().level(i);
        TreeSet<Integer> milestones = new TreeSet<>(lv.titles().keySet());
        for (String b : plugin.biomes().all()) if (lv.biomeLevel(b) > 1) milestones.add(lv.biomeLevel(b));
        for (var st : dev.skyrealms.terrain.UndersideStyle.values()) if (lv.undersideLevel(st.id) > 1) milestones.add(lv.undersideLevel(st.id));
        for (int l = 2; l <= lv.maxLevel; l++) if (!plugin.cosmetics().unlocksAt(l).isEmpty()) milestones.add(l);
        Menu m = new Menu(6, "<dark_aqua>Levels & Unlocks");
        m.button(4, levelCard(i), null);
        int slot = 9;
        for (int l : milestones) {
            if (slot >= 45) break;
            boolean got = level >= l;
            List<String> lore = new ArrayList<>();
            for (String what : unlocksAt(l)) lore.add((got ? "<green>✔ " : "<gray>• ") + what);
            if (l == 1) lore.add("<green>✔ Starting biomes: " + String.join(", ",
                    plugin.biomes().all().stream().filter(b -> lv.biomeLevel(b) == 1 && plugin.biomes().builtIn(b)).map(this::pretty).toList()));
            lore.add(got ? "<green>Reached" : "<gray>Needs " + String.format("%,d", lv.xpFor(l)) + " total XP");
            m.button(slot++, item(got ? Material.LIME_DYE : Material.GRAY_DYE, (got ? "<green>" : "<gray>") + "Level " + l,
                    lore.toArray(String[]::new)), null);
        }
        m.button(45, back(), this::manage);
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

    private String describe(String biome) {
        return switch (biome) {
            case "plains" -> "Gentle grassland with a few oaks";
            case "hilly_plains" -> "Rolling grassy hills";
            case "meadow" -> "Wildflowers everywhere";
            case "oak_forest" -> "Dense oak and birch woods";
            case "dark_oak_forest" -> "Dark canopy and giant mushrooms";
            case "cherry_grove" -> "Pink blossoms and petals";
            case "jungle" -> "Towering jungle trees and bushes";
            case "swamp" -> "Murky shallows and lily pads";
            case "desert" -> "Sand dunes and cacti";
            case "badlands" -> "Striped terracotta mesas";
            case "beach" -> "Sandy shore at sea level";
            case "shallow_ocean" -> "Shallow water with seagrass";
            case "deep_ocean" -> "Deep water and kelp forests";
            case "snowy" -> "Snowy spruce forest, frozen lakes";
            case "mushroom" -> "Mycelium and giant mushrooms";
            case "lush_caverns" -> "Moss, azaleas and glow berries";
            case "volcanic" -> "Basalt, magma and lava lakes";
            case "crystal" -> "Calcite, amethyst and crystal spires";
            case "ancient" -> "Mossy ruins and broken pillars";
            default -> "Generated terrain";
        };
    }

    private Material biomeIcon(String biome) {
        Material builtIn = plugin.biomes().icon(biome);
        if (builtIn != null) return builtIn;
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

    private String pretty(String biome) { return plugin.biomes().pretty(biome); }

    static String prettyId(String biome) {
        StringBuilder sb = new StringBuilder();
        for (String w : biome.split("_")) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
