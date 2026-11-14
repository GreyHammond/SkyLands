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
package dev.skyrealms.journal;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.player.Milestone;
import dev.skyrealms.player.PlayerProfiles;
import dev.skyrealms.util.Msg;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.time.Duration;
import java.util.*;

/**
 * The Guide: a guided tutorial of ordered steps that unlocks journal pages, plus the journal itself.
 * Only the current step listens for its milestone. Java players read a written book; Bedrock players
 * get forms (see dev.skyrealms.bedrock.JournalForms, loaded only with Floodgate).
 */
public final class Journal implements Listener {
    /** A tutorial step: the milestone that completes it, the page it unlocks, what to tell the player. */
    public record Step(String page, Milestone milestone, String objective, double defaultReward) {}

    private static final List<Step> DEFAULT_STEPS = List.of(
            new Step("getting_started", Milestone.CLAIMED_ISLAND, "Claim your free island: open /skyland and pick a biome.", 0),
            new Step("world_below", Milestone.LANDED_OVERWORLD, "Jump off the edge of your island to fall into the world below.", 100),
            new Step("returning_home", Milestone.RETURNED_HOME, "Fly home with the return feather in your hotbar, or /ihome.", 100),
            new Step("corruption", Milestone.ENTERED_CORRUPTION, "Find corrupted land: stay in one area of the world below until the corruption reaches 25%.", 100),
            new Step("void_shards", Milestone.COLLECTED_SHARD, "Collect Void Shards by mining corrupted land.", 150),
            new Step("corrupted_creatures", Milestone.KILLED_CORRUPTED_MOB, "Defeat a corrupted mob (purple name) on corrupted land.", 200),
            new Step("earning", Milestone.SOLD_AT_EXCHANGE, "Sell something at the Void Exchange: /skyland, then The Corruption.", 100),
            new Step("corrupted_ingredients", Milestone.COLLECTED_CORRUPTED_SPIDER_EYE, "Collect a Corrupted Spider Eye from a corrupted spider.", 150),
            new Step("cleansing_tonics", Milestone.BREWED_TONIC, "Brew a Cleansing Tonic I: Tainted Potion + Corrupted Spider Eye.", 250),
            new Step("warded_armor", Milestone.CRAFTED_WARD_TEMPLATE, "Craft a Ward Template: 8 Void Shards around Crying Obsidian.", 250),
            new Step("ward_levels", Milestone.SMITHED_WARD, "Smith a piece of Ward I armor: Ward Template + armor + Void Shard.", 300),
            new Step("daily_contracts", Milestone.CLAIMED_CONTRACT, "Claim a daily contract: /skyland contracts.", 200));

    /** Profile.rewarded marker for the finish reward (steps use their index). */
    private static final int FINISH_MARK = 1000;

    private final SkyRealmsPlugin plugin;
    private final List<Step> steps = new ArrayList<>();
    private final Map<String, Double> rewards = new HashMap<>();
    private final double finishReward;
    private final int shardGoal;
    private final boolean enabled;

    public Journal(SkyRealmsPlugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        if (cfg == null) cfg = new MemoryConfiguration();
        enabled = cfg.getBoolean("enabled", true);
        finishReward = Math.max(0, cfg.getDouble("finish-reward", 500));
        shardGoal = Math.max(1, cfg.getInt("shard-goal", 3));
        Map<String, Step> byPage = new LinkedHashMap<>();
        for (Step s : DEFAULT_STEPS) byPage.put(s.page(), s);
        List<String> order = cfg.getStringList("order");
        if (order.isEmpty()) steps.addAll(DEFAULT_STEPS);
        else for (String id : order) { Step s = byPage.get(id); if (s != null && !steps.contains(s)) steps.add(s); }
        if (steps.isEmpty()) steps.addAll(DEFAULT_STEPS);
        for (Step s : steps) rewards.put(s.page(), Math.max(0, cfg.getDouble("rewards." + s.page(), s.defaultReward())));
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.milestones().listen(this::onMilestone);
    }

    // ---- state -------------------------------------------------------------------------

    private PlayerProfiles.Profile prof(Player p) { return plugin.profiles().get(p.getUniqueId()); }

    public List<Step> steps() { return Collections.unmodifiableList(steps); }

    /** Pages counted in "Journal n/m": every step page plus the two finish pages. */
    public List<String> countedPages() {
        List<String> out = new ArrayList<>();
        for (Step s : steps) out.add(s.page());
        out.add(JournalPages.RESETS);
        out.add(JournalPages.REALMS);
        return out;
    }

    public boolean unlocked(Player p, String page) { return JournalPages.STORY.equals(page) || prof(p).pages.contains(page); }

    public int unlockedCount(Player p) {
        int n = 0;
        for (String id : countedPages()) if (unlocked(p, id)) n++;
        return n;
    }

    public boolean finished(Player p) { return prof(p).tutorialStep >= steps.size(); }

    public boolean skipped(Player p) { return prof(p).tutorialSkipped; }

    /** What the player should do next, for the journal, the hotbar book and anything else. */
    public String currentObjective(Player p) {
        PlayerProfiles.Profile pr = prof(p);
        if (pr.tutorialSkipped) return "Tutorial skipped. Every page is open; restart it any time from the journal.";
        if (pr.tutorialStep >= steps.size()) return "Tutorial complete. The journal is yours.";
        Step s = steps.get(Math.max(0, pr.tutorialStep));
        if (s.milestone() == Milestone.COLLECTED_SHARD)
            return "Collect " + shardGoal + " Void Shards by mining corrupted land (" + Math.min(shardGoal, pr.counter("shards")) + "/" + shardGoal + ").";
        return s.objective();
    }

    // ---- progress -----------------------------------------------------------------------

    private void onMilestone(Player p, Milestone m, int amount) {
        if (!enabled) return;
        PlayerProfiles.Profile pr = prof(p);
        if (pr.tutorialSkipped || pr.tutorialStep >= steps.size()) return;
        Step s = steps.get(Math.max(0, pr.tutorialStep));
        if (s.milestone() != m) return;
        if (m == Milestone.COLLECTED_SHARD) {
            int have = pr.addCounter("shards", Math.max(1, amount));
            plugin.profiles().dirty();
            if (have < shardGoal) {
                p.sendMessage(Msg.c("<dark_purple>Void Shards: <white>" + have + "/" + shardGoal));
                return;
            }
        }
        completeCurrent(p, true);
    }

    /** Finishes the current step: unlock its page, pay once, move on, announce. */
    private void completeCurrent(Player p, boolean announce) {
        PlayerProfiles.Profile pr = prof(p);
        int idx = Math.max(0, pr.tutorialStep);
        if (idx >= steps.size()) return;
        Step s = steps.get(idx);
        pr.pages.add(s.page());
        double pay = rewards.getOrDefault(s.page(), 0.0);
        boolean paid = false;
        if (pay > 0 && pr.rewarded.add(idx)) { plugin.money().refund(p, pay); paid = true; }
        pr.tutorialStep = idx + 1;
        if (pr.tutorialStep >= steps.size()) {
            pr.pages.add(JournalPages.RESETS);
            pr.pages.add(JournalPages.REALMS);
            boolean finishPaid = finishReward > 0 && pr.rewarded.add(FINISH_MARK);
            if (finishPaid) plugin.money().refund(p, finishReward);
            plugin.profiles().dirty();
            if (announce) announce(p, "Tutorial complete", JournalPages.get(JournalPages.RESETS).title() + " and " + JournalPages.get(JournalPages.REALMS).title(),
                    paid || finishPaid ? (paid ? pay : 0) + (finishPaid ? finishReward : 0) : 0);
            return;
        }
        plugin.profiles().dirty();
        if (announce) announce(p, "New journal entry", JournalPages.get(s.page()).title(), paid ? pay : 0);
    }

    private void announce(Player p, String head, String pageTitle, double paid) {
        p.showTitle(Title.title(Msg.c("<light_purple>" + head), Msg.c("<white>" + pageTitle),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
        String reward = paid > 0 ? " <gold>+" + plugin.money().format(paid) + "</gold>" : "";
        p.sendMessage(Msg.c("<light_purple>Journal: <white>" + pageTitle + "</white>" + reward));
        p.sendMessage(Msg.c("<gray>Next: <white>" + esc(currentObjective(p)) + "</white> <aqua><click:run_command:'/skyland journal'><hover:show_text:'Open your journal'>[Open journal]</hover></click>"));
    }

    private static String esc(String s) { return s.replace("<", "\\<"); }

    /** A player who already has an island when they reach step 1 gets it ticked off on join. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PlayerProfiles.Profile pr = prof(p);
        if (!enabled || pr.tutorialSkipped || pr.tutorialStep >= steps.size()) return;
        Step s = steps.get(Math.max(0, pr.tutorialStep));
        if (s.milestone() == Milestone.CLAIMED_ISLAND && plugin.islands().of(p.getUniqueId()) != null) completeCurrent(p, false);
    }

    // ---- skip / restart / admin ---------------------------------------------------------------

    /** Opens every page; no step rewards are paid. */
    public void skip(Player p) {
        PlayerProfiles.Profile pr = prof(p);
        pr.tutorialSkipped = true;
        pr.tutorialStep = steps.size();
        pr.pages.addAll(countedPages());
        plugin.profiles().dirty();
    }

    /** Back to step 1. Pages stay unlocked and rewards are never paid twice. */
    public void restart(Player p) {
        PlayerProfiles.Profile pr = prof(p);
        pr.tutorialSkipped = false;
        pr.tutorialStep = 0;
        pr.counters.remove("shards");
        plugin.profiles().dirty();
    }

    /** Admin: forget the tutorial and journal entirely (rewards can be earned again). */
    public void reset(UUID id) {
        PlayerProfiles.Profile pr = plugin.profiles().get(id);
        pr.tutorialSkipped = false;
        pr.tutorialStep = 0;
        pr.pages.clear();
        pr.rewarded.clear();
        pr.counters.remove("shards");
        plugin.profiles().dirty();
    }

    /** Admin: mark every step done and open every page, without paying. */
    public void complete(UUID id) {
        PlayerProfiles.Profile pr = plugin.profiles().get(id);
        pr.tutorialSkipped = false;
        pr.tutorialStep = steps.size();
        pr.pages.addAll(countedPages());
        plugin.profiles().dirty();
    }

    public void skip(UUID id) {
        PlayerProfiles.Profile pr = plugin.profiles().get(id);
        pr.tutorialSkipped = true;
        pr.tutorialStep = steps.size();
        pr.pages.addAll(countedPages());
        plugin.profiles().dirty();
    }

    // ---- opening ------------------------------------------------------------------------------

    /** Opens the journal: the written book on Java, forms on Bedrock. */
    public void openFor(Player p) {
        if (plugin.bedrock().handles(p)) { plugin.bedrock().openJournal(p, false); return; }
        p.openBook(book(p, false));
    }

    /** Opens the journal at the story (first join). */
    public void start(Player p) {
        if (plugin.bedrock().handles(p)) { plugin.bedrock().openJournal(p, true); return; }
        p.openBook(book(p, true));
    }

    private static final int PAGE_CHARS = 200;

    /** The written book: story first when {@code storyFirst}, else the objective page, contents, then pages. */
    public Book book(Player p, boolean storyFirst) {
        List<Component> pages = new ArrayList<>();
        List<String> order = new ArrayList<>();
        order.add(JournalPages.STORY);
        order.addAll(countedPages());

        // Work out where each unlocked page starts (front matter = objective + contents = 2 pages).
        Map<String, Integer> startPage = new LinkedHashMap<>();
        int n = 3;
        if (storyFirst) n += JournalPages.chunks(JournalPages.get(JournalPages.STORY).text(), PAGE_CHARS).size();
        for (String id : order) {
            if (!unlocked(p, id)) continue;
            if (storyFirst && id.equals(JournalPages.STORY)) { startPage.put(id, 1); continue; }
            startPage.put(id, n);
            n += JournalPages.chunks(JournalPages.get(id).text(), PAGE_CHARS).size();
        }
        if (storyFirst) addPage(pages, JournalPages.get(JournalPages.STORY));

        // Objective page.
        StringBuilder front = new StringBuilder("<bold>The Guide</bold>\n<dark_gray>Journal " + unlockedCount(p) + "/" + countedPages().size() + "</dark_gray>\n\n"
                + "<dark_purple>Your objective:</dark_purple>\n" + esc(currentObjective(p)) + "\n\n");
        if (!finished(p)) front.append("<dark_red><click:run_command:'/skyland journal skip'><hover:show_text:'Open every page now, without step rewards'>[Skip tutorial]</hover></click></dark_red>");
        else front.append("<dark_aqua><click:run_command:'/skyland journal restart'><hover:show_text:'Start the steps again (no rewards twice)'>[Restart tutorial]</hover></click></dark_aqua>");
        pages.add(Msg.c(front.toString()));

        // Contents page.
        StringBuilder contents = new StringBuilder("<bold>Contents</bold>\n\n");
        for (String id : order) {
            Integer at = startPage.get(id);
            if (at == null) contents.append("<gray>???</gray>\n");
            else contents.append("<dark_blue><click:change_page:'").append(at).append("'>").append(JournalPages.get(id).title()).append("</click></dark_blue>\n");
        }
        pages.add(Msg.c(contents.toString()));

        for (String id : order) {
            if (!startPage.containsKey(id) || (storyFirst && id.equals(JournalPages.STORY))) continue;
            addPage(pages, JournalPages.get(id));
        }
        return Book.book(Component.text("The Guide"), Component.text("SkyLands"), pages);
    }

    private static void addPage(List<Component> pages, JournalPages.Page page) {
        List<String> chunks = JournalPages.chunks(page.text(), PAGE_CHARS);
        for (int i = 0; i < chunks.size(); i++) {
            String head = i == 0 ? "<bold>" + page.title() + "</bold>\n\n" : "";
            pages.add(Msg.c(head + esc(chunks.get(i))));
        }
    }
}
