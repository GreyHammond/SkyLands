/*
 * SkyLands (SkyRealms) - floating-island survival for Paper
 * Copyright (C) 2026 Hammond Digital Studios
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License version 3, together with the additional terms in the NOTICE
 * file (section 7: you must keep the attribution "SkyLands by Hammond Digital Studios" and mark
 * modified versions). This program is distributed WITHOUT ANY WARRANTY. See the LICENSE and
 * NOTICE files, or <https://www.gnu.org/licenses/>.
 */
package dev.skyrealms.bedrock;

import dev.skyrealms.RealmService;
import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.cosmetic.Cosmetics;
import dev.skyrealms.island.Flag;
import dev.skyrealms.island.Island;
import dev.skyrealms.island.Role;
import dev.skyrealms.island.Tier;
import dev.skyrealms.terrain.UndersideStyle;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.*;
import java.util.function.Consumer;

/**
 * Native Bedrock forms (via Floodgate) for every Skyland screen. Bedrock players get big buttons,
 * text boxes and toggles instead of chest menus. All actions go through the same RealmService /
 * CosmeticService code the chest menus use, so prices, levels and permissions behave identically.
 *
 * This class is only loaded when Floodgate is installed.
 */
public final class FormMenus {
    private static final String G = "§a", Y = "§e", A = "§b", P = "§d", O = "§6", GR = "§7", DG = "§8", R = "§c", W = "§f";

    private final SkyRealmsPlugin plugin;
    private final RealmService svc;
    private final boolean icons;

    public FormMenus(SkyRealmsPlugin plugin) {
        this.plugin = plugin;
        this.svc = plugin.service();
        this.icons = plugin.getConfig().getBoolean("bedrock.button-icons", true);
    }

    /** Test hook: when set, built forms go here instead of to Floodgate. */
    public static volatile java.util.function.BiConsumer<Player, org.geysermc.cumulus.form.Form> testSender;

    private void send(Player p, org.geysermc.cumulus.form.util.FormBuilder<?, ?, ?> builder) {
        org.geysermc.cumulus.form.Form form = builder.build();
        var hook = testSender;
        if (hook != null) hook.accept(p, form);
        else FloodgateApi.getInstance().sendForm(p.getUniqueId(), form);
    }

    public static boolean isBedrock(Player p) {
        return FloodgateApi.getInstance().isFloodgatePlayer(p.getUniqueId());
    }

    // =========================================================================================
    //  Form helpers
    // =========================================================================================

    private record Btn(String text, String icon, Consumer<Player> action) {}

    private static Btn btn(String text, String icon, Consumer<Player> action) { return new Btn(text, icon, action); }

    private void simple(Player p, String title, String content, List<Btn> buttons) {
        SimpleForm.Builder f = SimpleForm.builder().title(title).content(content == null ? "" : content);
        for (Btn b : buttons) {
            if (icons && b.icon != null) f.button(b.text, FormImage.Type.PATH, b.icon);
            else f.button(b.text);
        }
        f.validResultHandler(r -> sync(p, () -> {
            int id = r.clickedButtonId();
            if (id >= 0 && id < buttons.size() && buttons.get(id).action != null) buttons.get(id).action.accept(p);
        }));
        send(p, f);
    }

    /** Yes/no question. Bedrock shows button1 on top. */
    private void modal(Player p, String title, String content, String yes, String no, Consumer<Player> onYes, Consumer<Player> onNo) {
        ModalForm.Builder f = ModalForm.builder().title(title).content(content).button1(yes).button2(no)
                .validResultHandler(r -> sync(p, () -> {
                    if (r.clickedFirst()) onYes.accept(p);
                    else if (onNo != null) onNo.accept(p);
                }));
        send(p, f);
    }

    private void confirm(Player p, String question, double price, Consumer<Player> yes, Consumer<Player> back) {
        String cost = price > 0 ? "\n\n" + GR + "Cost: " + O + svc.money(price) : "";
        modal(p, "Are you sure?", W + question + cost, G + "Yes", R + "No, go back", yes, back);
    }

    /** Form responses arrive off the main thread; hop back onto it before touching the world. */
    private void sync(Player p, Runnable r) {
        if (Bukkit.isPrimaryThread()) { if (p.isOnline()) r.run(); return; }
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) r.run(); });
    }

    private Island own(Player p) { return plugin.islands().of(p.getUniqueId()); }

    private String biomeName(String b) { return plugin.biomes().pretty(b); }

    private static String name(UUID id) { return Objects.toString(Bukkit.getOfflinePlayer(id).getName(), "?"); }

    // =========================================================================================
    //  Top level
    // =========================================================================================

    public void main(Player p) {
        Island i = own(p);
        List<Btn> b = new ArrayList<>();
        String content;
        if (i == null) {
            content = GR + "You don't have an island yet.";
            b.add(btn(G + "Buy an Island\n" + DG + "Pick a size and a biome", "textures/blocks/grass_side_carried", this::buyTier));
            var archived = plugin.archiver().info(p.getUniqueId());
            if (archived != null) b.add(btn(A + "Restore My Island\n" + DG + archived.daysLeft() + " days left", "textures/items/compass_item",
                    pl -> modal(pl, "Restore island?", W + "Bring back your archived island exactly as you left it, for free?",
                            G + "Restore", R + "Not now", yes -> svc.restoreOwn(yes), this::main)));
            b.add(btn(A + "Visit Islands", "textures/items/compass_item", pl -> visit(pl)));
            b.add(btn(O + "Top Islands", "textures/items/gold_ingot", this::topIslands));
        } else {
            boolean owner = i.owner.equals(p.getUniqueId());
            content = W + plugin.cosmetics().displayName(i) + "\n" + O + plugin.xp().badge(i)
                    + (owner ? "" : "\n" + A + "You co-own " + name(i.owner) + "'s island");
            b.add(btn(G + "Return to Island\n" + DG + "Fly home or visit others", "textures/items/elytra", this::travel));
            b.add(btn(Y + "Manage Island\n" + DG + "Grow, upgrade, protect, style", "textures/items/book_writable", this::manage));
            if (owner) b.add(btn(R + "Delete Island", "textures/blocks/tnt_side", this::delete));
            else b.add(btn(R + "Leave Island", "textures/items/door_wood", pl -> modal(pl, "Leave island?",
                    W + "Stop co-owning " + name(i.owner) + "'s island?", R + "Leave", G + "Stay", svc::leaveIsland, this::main)));
        }
        if (plugin.shortcuts().available()) {
            boolean on = plugin.shortcuts().isOn(p);
            b.add(btn(A + "Hotbar Shortcuts: " + (on ? G + "On" : R + "Off") + "\n" + DG + (on ? "Tap to remove the book & feather" : "Menu book & return feather"),
                    "textures/items/feather", pl -> { plugin.shortcuts().toggle(pl); main(pl); }));
        }
        simple(p, "Skyland", content + "\n\n" + DG + dev.skyrealms.Credits.ATTRIBUTION, b);
    }

    public void travel(Player p) {
        Island i = own(p);
        List<Btn> b = new ArrayList<>();
        if (i != null) {
            long cd = svc.cooldownLeft(p);
            b.add(btn(G + "Return to My Island\n" + DG + (cd > 0 ? "Ready in " + cd + "s" : "A gust of wind carries you home"),
                    "textures/items/elytra", svc::launchHome));
        }
        List<Island> friendOf = plugin.islands().friendOf(p.getUniqueId());
        b.add(btn(A + "Friends' Islands\n" + DG + friendOf.size() + " island(s)", "textures/items/emerald",
                pl -> islandList(pl, "Friends' Islands", friendOf, false, this::travel)));
        b.add(btn(A + "Visit Islands\n" + DG + "Everyone open to visitors", "textures/items/compass_item", this::visit));
        b.add(btn(O + "Top Islands\n" + DG + "Ranked by level", "textures/items/gold_ingot", this::topIslands));
        b.add(btn(GR + "Back", null, this::main));
        simple(p, "Return to Island", null, b);
    }

    // =========================================================================================
    //  Buying
    // =========================================================================================

    public void buyTier(Player p) {
        List<Btn> b = new ArrayList<>();
        for (Tier t : Tier.values()) {
            b.add(btn(G + t.pretty() + " " + DG + t.footprint + "x" + t.footprint + "\n" + O + svc.money(t.price)
                    + DG + " · grows to " + t.maxPieces + " pieces", null, pl -> biomes(pl, Mode.BUY, t)));
        }
        b.add(btn(A + "Visit Islands first", "textures/items/compass_item", this::visit));
        b.add(btn(GR + "Back", null, this::main));
        simple(p, "Choose an island size", GR + "Bigger islands can grow more land.", b);
    }

    public enum Mode { BUY, GROW, UNLOCK }

    public void biomes(Player p, Mode mode, Tier tier) {
        Island i = own(p);
        var lv = plugin.xp().levels();
        int level = mode == Mode.BUY || i == null ? svc.startLevel(p.getUniqueId()) : plugin.xp().level(i);
        List<String> list = new ArrayList<>(plugin.biomes().all());
        if (mode == Mode.BUY) list.removeIf(b -> !plugin.biomes().startable(b));
        if (mode == Mode.GROW && i != null) list.retainAll(i.biomes);
        if (mode == Mode.UNLOCK && i != null) list.removeAll(i.biomes);
        if (mode != Mode.GROW) list.sort(Comparator.comparingInt(lv::biomeLevel));

        List<Btn> b = new ArrayList<>();
        List<String> locked = new ArrayList<>();
        for (String biome : list) {
            int need = lv.biomeLevel(biome);
            if (mode != Mode.GROW && need > level) { locked.add(biomeName(biome) + " (Lv " + need + ")"); continue; }
            String n = biomeName(biome);
            switch (mode) {
                case BUY -> b.add(btn(G + n, null, pl -> newIslandStyle(pl, tier, biome)));
                case GROW -> {
                    boolean fresh = i != null && i.pieces.stream().noneMatch(pc -> pc.biome().equals(biome));
                    b.add(btn(G + n + "\n" + DG + (fresh ? "New! Starts at your island's edge" : "Add one more piece"), null,
                            pl -> placement(pl, biome)));
                }
                case UNLOCK -> b.add(btn(P + n + "\n" + O + svc.money(svc.unlockPrice(biome)), null,
                        pl -> confirm(pl, "Unlock " + n + "? You can then grow it with Grow Land.", svc.unlockPrice(biome),
                                yes -> { if (svc.unlockBiome(yes, biome)) biomes(yes, Mode.GROW, null); }, back -> biomes(back, Mode.UNLOCK, null))));
            }
        }
        b.add(btn(GR + "Back", null, mode == Mode.BUY ? this::buyTier : this::manage));
        String title = switch (mode) { case BUY -> "Choose your biome"; case GROW -> "Grow which biome?"; case UNLOCK -> "Unlock a biome"; };
        String content = switch (mode) {
            case BUY -> GR + tier.pretty() + " island for " + O + svc.money(tier.price) + GR + ". Your new island starts at level " + level + ".";
            case GROW -> GR + "Cost: " + O + svc.money(svc.growPrice()) + GR + " per piece. Pieces: " + W
                    + (i == null ? 0 : i.pieces.size()) + "/" + (i == null ? 0 : i.tier.maxPieces);
            case UNLOCK -> GR + "Unlocking adds a biome to your list. Then use Grow Land.";
        };
        if (!locked.isEmpty()) content += "\n\n" + DG + "Locked: " + String.join(", ", locked);
        if (b.size() == 1) content += "\n\n" + Y + (mode == Mode.UNLOCK ? "Nothing to unlock right now." : "Nothing here yet.");
        simple(p, title, content, b);
    }

    /** Pick the underside style or theme for a new island (only what the player has unlocked). */
    public void newIslandStyle(Player p, Tier tier, String biome) {
        var styles = svc.startUndersides(p.getUniqueId());
        var themes = svc.startThemes(p.getUniqueId());
        String n = biomeName(biome);
        java.util.function.Function<String, Consumer<Player>> buyWith = style -> pl ->
                confirm(pl, "Buy a " + tier.pretty() + " " + n + " island?", tier.price,
                        yes -> svc.buy(yes, tier, biome, style), back -> newIslandStyle(back, tier, biome));
        if (styles.size() <= 1 && themes.isEmpty()) { buyWith.apply(null).accept(p); return; }
        List<Btn> b = new ArrayList<>();
        b.add(btn(G + "Keep " + (plugin.progress().has(p.getUniqueId()) ? "my saved style" : "Rock") + "\n" + DG + "Use what you had before", null, buyWith.apply(null)));
        for (var st : styles) b.add(btn(A + st.displayName + " underside\n" + DG + st.description, null, buyWith.apply("underside:" + st.id)));
        for (var t : themes) b.add(btn(P + t.displayName + " theme\n" + DG + UndersideStyle.byId(t.underside).displayName + " underside · "
                + t.particles.displayName + " · " + t.border.displayName + " border", null, buyWith.apply("theme:" + t.id)));
        b.add(btn(GR + "Back", null, pl -> biomes(pl, Mode.BUY, tier)));
        simple(p, "Style your new island", GR + "Pick an underside style or a theme. Name, particles and border can be set from Cosmetics after buying.", b);
    }

    /** Where should the new land go? */
    public void placement(Player p, String biome) {
        Island i = svc.owned(p);
        if (i == null) return;
        String n = biomeName(biome);
        List<Btn> b = new ArrayList<>();
        for (RealmService.Placement where : RealmService.Placement.values()) {
            if (where == RealmService.Placement.HERE && !svc.canPlaceHere(p, i)) continue;
            String label = switch (where) {
                case AUTO -> G + "Automatic\n" + DG + "Let the island choose a good spot";
                case HERE -> A + "Toward where I'm standing\n" + DG + "As close to you as it fits";
                default -> W + where.label + " side";
            };
            b.add(btn(label, where == RealmService.Placement.AUTO ? "textures/items/compass_item" : null,
                    pl -> confirm(pl, "Grow " + n + (where == RealmService.Placement.AUTO ? "" : " (" + where.label.toLowerCase() + ")") + "?",
                            svc.growPrice(), yes -> { svc.grow(yes, biome, where); biomes(yes, Mode.GROW, null); }, back -> placement(back, biome))));
        }
        b.add(btn(GR + "Back", null, pl -> biomes(pl, Mode.GROW, null)));
        String content = GR + "Pick where the new " + n + " land attaches. It has to touch your island and stay inside your border."
                + (svc.canPlaceHere(p, i) ? "" : "\n\n" + DG + "Stand on your island to use \"Toward where I'm standing\".")
                + "\n" + DG + "North is toward -Z.";
        simple(p, "Where should it go?", content, b);
    }

    // =========================================================================================
    //  Manage
    // =========================================================================================

    public void manage(Player p) {
        Island i = own(p);
        if (i == null) { main(p); return; }
        boolean owner = i.owner.equals(p.getUniqueId());
        String bar = plugin.xp().progressBar(i).replaceAll("<[^>]+>", "");
        StringBuilder content = new StringBuilder(W + plugin.cosmetics().displayName(i) + "\n" + O + plugin.xp().badge(i)
                + "\n" + GR + bar + "\n" + GR + i.tier.pretty() + " · " + i.pieces.size() + "/" + i.tier.maxPieces + " pieces");
        List<Btn> b = new ArrayList<>();
        b.add(btn(G + "Grow Land\n" + DG + svc.money(svc.growPrice()) + " per piece", "textures/items/seeds_wheat", pl -> biomes(pl, Mode.GROW, null)));
        b.add(btn(P + "Unlock a Biome", "textures/blocks/sapling_oak", pl -> biomes(pl, Mode.UNLOCK, null)));
        Tier next = i.tier.next();
        if (next != null) b.add(btn(O + "Upgrade to " + next.pretty() + "\n" + DG + svc.money(svc.upgradeCost(i)) + " · room for " + next.maxPieces,
                "textures/items/experience_bottle", pl -> confirm(pl, "Upgrade to " + next.pretty() + "? This adds room, not land.",
                        svc.upgradeCost(i), yes -> { svc.upgrade(yes); manage(yes); }, this::manage)));
        b.add(btn(Y + "Levels & Unlocks", "textures/items/experience_bottle", this::levels));
        b.add(btn(P + "Cosmetics & Themes", "textures/items/name_tag", this::cosmetics));
        b.add(btn(A + "Underside Style\n" + DG + UndersideStyle.byId(i.underside).displayName, null, this::undersides));
        b.add(btn(Y + "Protection", null, this::protection));
        b.add(btn(Y + "Friends\n" + DG + i.friends.size() + " friend(s)", "textures/items/emerald", this::friends));
        b.add(btn(G + "Set Home Here", "textures/items/bed_red", svc::setHome));
        if (owner) {
            b.add(btn(O + "Co-owners\n" + DG + i.coOwners.size() + " co-owner(s)", "textures/items/gold_ingot", this::coOwners));
            b.add(btn(O + "Transfer Island", "textures/items/name_tag", this::transfer));
        }
        b.add(btn(GR + "Back", null, this::main));
        simple(p, "Manage Island", content.toString(), b);
    }

    public void levels(Player p) {
        Island i = own(p);
        if (i == null) { main(p); return; }
        var lv = plugin.xp().levels();
        int level = plugin.xp().level(i);
        TreeSet<Integer> milestones = new TreeSet<>(lv.titles().keySet());
        for (String b : plugin.biomes().all()) if (lv.biomeLevel(b) > 1) milestones.add(lv.biomeLevel(b));
        for (var st : UndersideStyle.values()) if (lv.undersideLevel(st.id) > 1) milestones.add(lv.undersideLevel(st.id));
        for (int l = 2; l <= lv.maxLevel; l++) if (!plugin.cosmetics().unlocksAt(l).isEmpty()) milestones.add(l);
        StringBuilder sb = new StringBuilder(O + plugin.xp().badge(i) + "\n" + GR + plugin.xp().progressBar(i).replaceAll("<[^>]+>", "")
                + "\n" + GR + "Earn XP by growing, unlocking, building, farming and visitors.\n");
        for (int l : milestones) {
            List<String> what = new ArrayList<>();
            for (String b : plugin.biomes().all()) if (lv.biomeLevel(b) == l && l > 1) what.add(biomeName(b));
            for (var st : UndersideStyle.values()) if (lv.undersideLevel(st.id) == l && l > 1) what.add(st.displayName + " underside");
            what.addAll(plugin.cosmetics().unlocksAt(l));
            String t = lv.titles().get(l);
            if (t != null) what.add("title \"" + t + "\"");
            if (what.isEmpty()) continue;
            sb.append("\n").append(level >= l ? G + "✔ Lv " + l : GR + "• Lv " + l).append(DG).append(": ").append(String.join(", ", what));
        }
        simple(p, "Levels & Unlocks", sb.toString(), List.of(btn(GR + "Back", null, this::manage)));
    }

    // =========================================================================================
    //  Protection (toggles on one screen)
    // =========================================================================================

    public void protection(Player p) {
        if (svc.owned(p) == null) return;
        simple(p, "Island Protection", GR + "Choose what visitors and friends can do. Owners and co-owners can always do everything.", List.of(
                btn(Y + "Visitors\n" + DG + "Everyone who isn't your friend", null, pl -> flagForm(pl, Role.VISITOR)),
                btn(G + "Friends\n" + DG + "Players you added as friends", null, pl -> flagForm(pl, Role.FRIEND)),
                btn(A + "Island settings\n" + DG + "Explosions, fire, mobs, PvP, visits", null, pl -> flagForm(pl, null)),
                btn(GR + "Back", null, this::manage)));
    }

    private void flagForm(Player p, Role role) {
        Island i = svc.owned(p);
        if (i == null) return;
        Flag.Scope scope = role == null ? Flag.Scope.ISLAND : Flag.Scope.ROLE;
        List<Flag> flags = Arrays.stream(Flag.values()).filter(f -> f.scope == scope).toList();
        String title = role == null ? "Island settings" : "What can " + role.pretty().toLowerCase() + " do?";
        CustomForm.Builder f = CustomForm.builder().title(title);
        if (role != null) f.dropdown("Quick set", List.of("Use my choices below", "Allow everything", "Block everything", "Reset to defaults"), 0);
        for (Flag fl : flags) f.toggle(fl.title + " §8- " + fl.description, i.get(role, fl));
        f.validResultHandler(r -> sync(p, () -> {
            Island isl = svc.owned(p);
            if (isl == null) return;
            int idx = 0, quick = 0;
            if (role != null) quick = r.asDropdown(idx++);
            for (Flag fl : flags) {
                boolean on = r.asToggle(idx++);
                if (quick == 1) on = true;
                else if (quick == 2) on = false;
                else if (quick == 3) on = role == Role.FRIEND ? fl.defaultFriend : fl.defaultVisitor;
                isl.set(role, fl, on);
            }
            plugin.islands().save();
            p.sendMessage("§3[§bSkyLands§3] §7Saved " + (role == null ? "island settings." : role.pretty().toLowerCase() + " permissions."));
            protection(p);
        }));
        send(p, f);
    }

    // =========================================================================================
    //  Friends, co-owners, transfer
    // =========================================================================================

    public void friends(Player p) {
        Island i = svc.owned(p);
        if (i == null) return;
        List<Btn> b = new ArrayList<>();
        b.add(btn(G + "Add a friend\n" + DG + "Pick from online players", "textures/items/emerald",
                pl -> pickOnline(pl, "Add a friend", o -> !i.roleOf(o.getUniqueId()).manages() && !i.friends.contains(o.getUniqueId()),
                        (who, o) -> { svc.addFriend(who, o); friends(who); }, this::friends)));
        for (UUID id : i.friends) {
            OfflinePlayer f = Bukkit.getOfflinePlayer(id);
            String n = Objects.toString(f.getName(), "Unknown");
            b.add(btn(W + n + "\n" + (f.isOnline() ? G + "Online" : DG + "Offline") + DG + " · tap to remove", null,
                    pl -> confirm(pl, "Remove " + n + " as a friend?", 0, yes -> { svc.removeFriend(yes, f); friends(yes); }, this::friends)));
        }
        b.add(btn(Y + "What can friends do?", null, pl -> flagForm(pl, Role.FRIEND)));
        b.add(btn(GR + "Back", null, this::manage));
        simple(p, "Friends", GR + "Friends get whatever the Friends protection settings allow.", b);
    }

    public void coOwners(Player p) {
        Island i = svc.ownerOnly(p);
        if (i == null) return;
        List<Btn> b = new ArrayList<>();
        b.add(btn(G + "Add a co-owner\n" + DG + "They can't already own an island", "textures/items/emerald",
                pl -> pickOnline(pl, "Add a co-owner", o -> plugin.islands().of(o.getUniqueId()) == null,
                        (who, o) -> confirm(who, "Make " + o.getName() + " a co-owner? They can grow, upgrade and change settings.", 0,
                                yes -> { svc.addCoOwner(yes, o); coOwners(yes); }, this::coOwners), this::coOwners)));
        for (UUID id : i.coOwners) {
            OfflinePlayer c = Bukkit.getOfflinePlayer(id);
            String n = Objects.toString(c.getName(), "Unknown");
            b.add(btn(O + n + "\n" + DG + "tap to remove", null,
                    pl -> confirm(pl, "Remove " + n + " as a co-owner?", 0, yes -> { svc.removeCoOwner(yes, c); coOwners(yes); }, this::coOwners)));
        }
        b.add(btn(GR + "Back", null, this::manage));
        simple(p, "Co-owners", GR + "Co-owners can grow, unlock, upgrade, and change protection and friends. Only you can delete or transfer.", b);
    }

    public void transfer(Player p) {
        Island i = svc.ownerOnly(p);
        if (i == null) return;
        List<Btn> b = new ArrayList<>();
        List<OfflinePlayer> people = new ArrayList<>();
        for (UUID c : i.coOwners) people.add(Bukkit.getOfflinePlayer(c));
        for (Player o : Bukkit.getOnlinePlayers())
            if (!o.equals(p) && !i.coOwners.contains(o.getUniqueId()) && plugin.islands().of(o.getUniqueId()) == null) people.add(o);
        for (OfflinePlayer o : people) {
            String n = Objects.toString(o.getName(), "?");
            b.add(btn(O + n + "\n" + DG + (i.coOwners.contains(o.getUniqueId()) ? "Co-owner" : "Online player"), null,
                    pl -> modal(pl, "Give your island away?", W + n + " will become the owner.\n\n" + R + "You become a visitor and lose all access immediately.",
                            R + "Give it to " + n, G + "Cancel",
                            yes -> modal(yes, "This can't be undone!", R + "Really give your island to " + n + "?", R + "Yes, give it away", G + "Cancel",
                                    sure -> svc.transfer(sure, o), this::manage), this::manage)));
        }
        b.add(btn(GR + "Back", null, this::manage));
        simple(p, "Transfer Island", people.isEmpty() ? Y + "No one to give it to. Add a co-owner, or ask the player to come online."
                : GR + "Pick the new owner. Co-owners are listed first.", b);
    }

    private void pickOnline(Player p, String title, java.util.function.Predicate<Player> filter,
                            java.util.function.BiConsumer<Player, Player> onPick, Consumer<Player> back) {
        List<Player> online = Bukkit.getOnlinePlayers().stream().filter(o -> !o.equals(p)).map(o -> (Player) o).filter(filter)
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        List<Btn> b = new ArrayList<>();
        for (Player o : online) b.add(btn(W + o.getName(), null, pl -> onPick.accept(pl, o)));
        b.add(btn(GR + "Back", null, back));
        simple(p, title, online.isEmpty() ? Y + "No one else to pick. They need to be online." : GR + "Tap a player.", b);
    }

    // =========================================================================================
    //  Visiting
    // =========================================================================================

    public void visit(Player p) {
        List<Island> open = plugin.islands().all().stream()
                .filter(i -> !i.owner.equals(p.getUniqueId()))
                .filter(i -> i.setting(Flag.VISITS) || i.friends.contains(p.getUniqueId())).toList();
        islandList(p, "Visit Islands", open, false, own(p) == null ? this::buyTier : this::travel);
    }

    public void topIslands(Player p) {
        List<Island> ranked = plugin.islands().all().stream()
                .filter(i -> i.setting(Flag.VISITS) || i.roleOf(p.getUniqueId()) != Role.VISITOR)
                .sorted(Comparator.comparingLong((Island i) -> -i.xp).thenComparingLong(i -> -i.visitorsTotal)).toList();
        islandList(p, "Top Islands", ranked, true, own(p) == null ? this::main : this::travel);
    }

    private void islandList(Player p, String title, List<Island> islands, boolean ranked, Consumer<Player> back) {
        List<Island> list = ranked ? islands : islands.stream()
                .sorted(Comparator.comparing((Island i) -> Bukkit.getPlayer(i.owner) == null).thenComparing(i -> -i.xp)).toList();
        List<Btn> b = new ArrayList<>();
        int rank = 1;
        for (Island i : list.subList(0, Math.min(list.size(), 50))) {
            String head = (ranked ? O + "#" + rank++ + " " : "") + G + plugin.cosmetics().displayName(i);
            b.add(btn(head + "\n" + DG + plugin.xp().badge(i) + " · " + i.visitorsTotal + " visits", null,
                    pl -> svc.visit(pl, Bukkit.getOfflinePlayer(i.owner))));
        }
        b.add(btn(GR + "Back", null, back));
        simple(p, title, list.isEmpty() ? Y + "No islands here yet." : GR + "Tap an island to visit.", b);
    }

    // =========================================================================================
    //  Cosmetics, themes, undersides
    // =========================================================================================

    public void cosmetics(Player p) {
        Island i = svc.owned(p);
        if (i == null) return;
        int level = plugin.xp().level(i);
        var theme = Cosmetics.Theme.byId(i.theme);
        boolean canName = level >= plugin.xp().levels().nameLevel;
        List<Btn> b = new ArrayList<>();
        b.add(btn(P + "Themes\n" + DG + "Now: " + (theme != null ? theme.displayName : "none"), null, this::themes));
        b.add(btn(A + "Particles\n" + DG + "Now: " + Cosmetics.ParticleSet.byId(i.particles).displayName, null, pl -> options(pl, "particles")));
        b.add(btn(A + "Border Style\n" + DG + "Now: " + Cosmetics.BorderStyle.byId(i.border).displayName, null, pl -> options(pl, "border")));
        b.add(btn(A + "Floating Decorations\n" + DG + "Now: " + Cosmetics.DecorationSet.byId(i.decorations).displayName, null, pl -> options(pl, "decorations")));
        b.add(btn(canName ? O + "Rename Island\n" + DG + plugin.cosmetics().displayName(i) : GR + "Rename Island\n" + R + "Unlocks at level "
                + plugin.xp().levels().nameLevel, "textures/items/name_tag", canName ? this::rename : null));
        b.add(btn(A + "Underside Style\n" + DG + UndersideStyle.byId(i.underside).displayName, null, this::undersides));
        b.add(btn(GR + "Back", null, this::manage));
        simple(p, "Cosmetics & Themes", GR + "More unlock as your island levels up.", b);
    }

    /** A real text box, so Bedrock players never have to type into chat. */
    public void rename(Player p) {
        Island i = svc.owned(p);
        if (i == null) return;
        CustomForm.Builder f = CustomForm.builder().title("Rename Island")
                .input("Island name (leave empty to reset to the default)", "My Floating Grove", i.name == null ? "" : i.name)
                .validResultHandler(r -> sync(p, () -> {
                    String text = r.asInput(0);
                    plugin.namePrompt().rename(p, text == null || text.isBlank() ? "reset" : text);
                    cosmetics(p);
                }));
        send(p, f);
    }

    public void themes(Player p) {
        Island i = svc.owned(p);
        if (i == null) return;
        int level = plugin.xp().level(i);
        List<Btn> b = new ArrayList<>();
        List<String> locked = new ArrayList<>();
        for (var t : Cosmetics.Theme.values()) {
            int need = plugin.cosmetics().need(t);
            if (level < need) { locked.add(t.displayName + " (Lv " + need + ")"); continue; }
            String parts = UndersideStyle.byId(t.underside).displayName + " underside · " + t.particles.displayName + " · " + t.border.displayName + " border";
            b.add(btn((t.id.equals(i.theme) ? G + "✔ " : P) + t.displayName + "\n" + DG + parts, null,
                    pl -> { if (plugin.cosmetics().applyTheme(pl, t)) undersideChoice(pl, UndersideStyle.byId(t.underside), this::themes); }));
        }
        b.add(btn(GR + "Back", null, this::cosmetics));
        String content = GR + "A theme sets the underside (new land only), particles and border in one tap.";
        if (!locked.isEmpty()) content += "\n\n" + DG + "Locked: " + String.join(", ", locked);
        simple(p, "Themes", content, b);
    }

    public void options(Player p, String category) {
        Island i = svc.owned(p);
        if (i == null) return;
        int level = plugin.xp().level(i);
        Enum<?>[] values = switch (category) {
            case "particles" -> Cosmetics.ParticleSet.values();
            case "border" -> Cosmetics.BorderStyle.values();
            default -> Cosmetics.DecorationSet.values();
        };
        String current = switch (category) { case "particles" -> i.particles; case "border" -> i.border; default -> i.decorations; };
        List<Btn> b = new ArrayList<>();
        List<String> locked = new ArrayList<>();
        for (Enum<?> v : values) {
            String id, n, d;
            switch (v) {
                case Cosmetics.ParticleSet x -> { id = x.id; n = x.displayName; d = x.description; }
                case Cosmetics.BorderStyle x -> { id = x.id; n = x.displayName; d = x.description; }
                case Cosmetics.DecorationSet x -> { id = x.id; n = x.displayName; d = x.description; }
                default -> { continue; }
            }
            int need = plugin.cosmetics().need(v);
            if (level < need) { locked.add(n + " (Lv " + need + ")"); continue; }
            b.add(btn((id.equals(current) ? G + "✔ " : A) + n + "\n" + DG + d, null, pl -> {
                switch (v) {
                    case Cosmetics.ParticleSet x -> plugin.cosmetics().setParticles(pl, x);
                    case Cosmetics.BorderStyle x -> plugin.cosmetics().setBorder(pl, x);
                    case Cosmetics.DecorationSet x -> {
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
            }));
        }
        b.add(btn(GR + "Back", null, this::cosmetics));
        String title = switch (category) { case "particles" -> "Particles"; case "border" -> "Border Style"; default -> "Floating Decorations"; };
        String content = locked.isEmpty() ? GR + "Tap to use." : GR + "Tap to use.\n\n" + DG + "Locked: " + String.join(", ", locked);
        simple(p, title, content, b);
    }

    public void undersides(Player p) {
        Island i = svc.owned(p);
        if (i == null) return;
        int level = plugin.xp().level(i);
        List<Btn> b = new ArrayList<>();
        List<String> locked = new ArrayList<>();
        for (var st : UndersideStyle.values()) {
            int need = plugin.xp().levels().undersideLevel(st.id);
            if (level < need) { locked.add(st.displayName + " (Lv " + need + ")"); continue; }
            b.add(btn((st.id.equals(i.underside) ? G + "✔ " : A) + st.displayName + "\n" + DG + st.description, null,
                    pl -> undersideChoice(pl, st, this::undersides)));
        }
        b.add(btn(GR + "Back", null, this::manage));
        String content = GR + "Land you grow from now on hangs this style underneath. Existing land is never changed.";
        if (!locked.isEmpty()) content += "\n\n" + DG + "Locked: " + String.join(", ", locked);
        simple(p, "Underside Style", content, b);
    }

    /** New land only, or convert the rock under the existing island too? */
    public void undersideChoice(Player p, UndersideStyle st, Consumer<Player> back) {
        if (svc.owned(p) == null) return;
        simple(p, st.displayName + " underside", GR + st.description + "\n\n" + GR + "Use it for new land only, or also reshape the rock under the land you already have. Your builds and soil aren't touched.", List.of(
                btn(G + "New land only\n" + DG + "Free", null, pl -> { svc.setUnderside(pl, st); back.accept(pl); }),
                btn(A + "Convert my whole island\n" + O + svc.money(svc.restylePrice()), null,
                        pl -> confirm(pl, "Convert your island's underside to " + st.displayName + "?", svc.restylePrice(),
                                yes -> svc.restyleUnderside(yes, st), b2 -> undersideChoice(b2, st, back))),
                btn(GR + "Back", null, back)));
    }

    // =========================================================================================
    //  Delete
    // =========================================================================================

    public void delete(Player p) {
        Island i = svc.ownerOnly(p);
        if (i == null) return;
        double refund = svc.deleteRefund(i);
        modal(p, "Delete your island?", W + "Every block, chest and animal on your island will be gone forever."
                        + (refund > 0 ? "\n" + GR + "Refund: " + O + svc.money(refund) : "\n" + GR + "No refund."),
                R + "Yes, delete it", G + "No, keep it",
                yes -> modal(yes, "This can't be undone!", R + "Really delete " + plugin.cosmetics().displayName(i) + " forever?",
                        R + "DELETE FOREVER", G + "Cancel", svc::deleteIsland, this::main), this::main);
    }
}
