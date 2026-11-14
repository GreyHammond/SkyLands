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

import dev.skyrealms.economy.Recipes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The journal's pages: an id, a title and the text. Plain text; the book and the forms format it. */
public final class JournalPages {
    public record Page(String id, String title, String text) {}

    public static final String STORY = "the_story";
    public static final String RESETS = "the_resets";
    public static final String REALMS = "untouched_realms";

    private static final Map<String, Page> PAGES = new LinkedHashMap<>();

    private static void add(String id, String title, String text) { PAGES.put(id, new Page(id, title, text)); }

    private static String lines(List<String> l) { return String.join("\n", l.stream().map(s -> "- " + s).toList()); }

    static {
        add(STORY, "The Story", "Long ago, the dungeons held the corruption sealed away. Adventurers came, took a little loot, and left their depths unfinished. "
                + "Season by season the seals weakened, until the corruption seeped out. The dungeons are sealed now, but too late. "
                + "The corruption is loose in the world. The gods tried to burn it away, remaking the world again and again, but it always returns. "
                + "You were lifted to the skylands, the one place the corruption cannot reach. From here you can return to the world below: "
                + "gather what you need, fight what it has twisted, and push back. But linger too long, and the land will die beneath your feet.");
        add("getting_started", "Getting Started", "This island is yours: your home in the skylands, and where you respawn whenever you have no bed. "
                + "The corruption can never reach it. Open /skyland, or use the menu book, to grow land, unlock biomes and manage your island. "
                + "When you're ready, jump off the edge.");
        add("world_below", "The World Below", "Jumping off your island drops you into the overworld, and that landing never hurts. "
                + "Down here you'll find wood, ore and food. But the corruption follows the living: stay too long in one place and the land around you begins to die.");
        add("returning_home", "Returning Home", "Use the return feather in your hotbar, or /ihome, and a gust of wind carries you back to your island. "
                + "Home is always one click away, so go back down whenever you're ready.");
        add("corruption", "The Corruption", "Every patch of land holds a corruption value, shown above your hotbar. "
                + "At 25% the land dries out, at 50% it turns to wasteland, and at 75% bedrock spreads. "
                + "It never hurts you, and leaving lets it fade, but bedrock stays until the world is recycled.");
        add("void_shards", "Void Shards", "Mining corrupted land can turn up Void Shards, and the deeper the corruption, the better the odds. "
                + "Blocks you placed yourself never drop them. Sell shards at the Void Exchange, or keep them to craft Ward Templates and brew tonics.");
        add("corrupted_creatures", "Corrupted Creatures", "Monsters that spawn on corrupted land can become Corrupted: purple names, more health and harder hits. "
                + "They drop Corrupted Remnants, sometimes Void Shards, and corrupted versions of their usual drops.");
        add("earning", "Earning", "The Void Exchange, in /skyland under The Corruption, buys Void Shards and Corrupted Remnants. "
                + "Daily contracts pay too, and visiting other players' islands earns a tip for you and for the owner.");
        add("corrupted_ingredients", "Corrupted Ingredients", "Corrupted spiders, skeletons and creepers drop a Corrupted Spider Eye, Bone or Gunpowder. "
                + "Only these work in corrupted brews; the normal versions don't. "
                + "Brew a Water Bottle with a Void Shard to make a Tainted Potion, the base of every tonic.");
        add("cleansing_tonics", "Cleansing Tonics", "While a Cleansing Tonic lasts, you cause no corruption at all: 60, 120 or 180 seconds by level.\n"
                + lines(Recipes.TONIC));
        add("warded_armor", "Warded Armor", "Warded armor cuts the corruption you cause: 5% a piece at Ward I, up to 80% for a full Ward III set. "
                + "Its trims and enchantments are kept.\n" + lines(Recipes.WARD.subList(0, 2)));
        add("ward_levels", "Ward Levels", lines(Recipes.WARD.subList(2, Recipes.WARD.size()))
                + "\nEcho Shards come from chests in Ancient Cities, deep in the Deep Dark.");
        add("daily_contracts", "Daily Contracts", "Every day brings three contracts in /skyland under The Corruption: deliver items, hunt, mine, explore or hand in shards. "
                + "They pay money and shards, and pay more in the last day before a recycle.");
        add(RESETS, "The Resets", "The gods remake the overworld every week to hold the corruption back. "
                + "Warnings count down first, and everyone below is carried home. Anything built down there is lost, so build your home on your island. "
                + "The resets buy time, but they have never stopped the corruption.");
        add(REALMS, "The Untouched Realms", "The corruption never reached the Nether or the End. Those old realms are still pure. "
                + "Their materials may one day help push the corruption back.");
    }

    private JournalPages() {}

    public static Page get(String id) { return PAGES.get(id); }

    /** Every page in journal order. */
    public static List<Page> all() { return new ArrayList<>(PAGES.values()); }

    /** Splits a page's text into chunks that fit one book page (~200 characters), at sentence or line breaks. */
    public static List<String> chunks(String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String part : text.split("(?<=[.!?])\\s+|\\n")) {
            String piece = part.trim();
            if (piece.isEmpty()) continue;
            String sep = cur.isEmpty() ? "" : (part.startsWith("- ") || piece.startsWith("- ") ? "\n" : " ");
            if (cur.length() + sep.length() + piece.length() > max && !cur.isEmpty()) { out.add(cur.toString()); cur.setLength(0); sep = ""; }
            cur.append(sep).append(piece);
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }
}
