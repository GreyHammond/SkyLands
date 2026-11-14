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
package dev.skyrealms;

import dev.skyrealms.command.SkylandCommands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import dev.skyrealms.island.IslandGrower;
import dev.skyrealms.island.IslandManager;
import dev.skyrealms.island.ProtectionListener;
import dev.skyrealms.island.Tier;
import dev.skyrealms.menu.MenuListener;
import dev.skyrealms.menu.Menus;
import dev.skyrealms.piece.PasteQueue;
import dev.skyrealms.piece.PieceLibrary;
import dev.skyrealms.transit.FallTransit;
import dev.skyrealms.util.Money;
import dev.skyrealms.world.SkyWorld;
import dev.skyrealms.world.VoidGenerator;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class SkyRealmsPlugin extends JavaPlugin {
    private World skyWorld;
    private IslandManager islands;
    private PieceLibrary library;
    private IslandGrower grower;
    private Money money;
    private int baseY;
    private dev.skyrealms.terrain.TerrainGenerator terrain;
    private dev.skyrealms.terrain.UndersideRestyler restyler;
    private dev.skyrealms.terrain.BiomeCatalog biomes;
    private RealmService service;
    private Menus menus;
    private dev.skyrealms.island.ClearQueue clearer;
    private dev.skyrealms.island.IslandArchiver archiver;
    private dev.skyrealms.level.XpService xp;
    private dev.skyrealms.island.ProgressStore progress;
    private dev.skyrealms.cosmetic.CosmeticService cosmetics;
    private dev.skyrealms.cosmetic.NamePrompt namePrompt;
    private dev.skyrealms.bedrock.BedrockUi bedrock;
    private dev.skyrealms.decay.DecayService decay;
    private dev.skyrealms.decay.recycle.WorldRecycler recycler;
    /** The overworld players fall into: worlds.overworld, or the current recycled world. */
    private volatile String overworldName = "world";

    @Override
    public void onEnable() {
        Credits.banner(Bukkit.getConsoleSender(), getPluginMeta().getVersion());
        saveDefaultConfig();
        FileConfiguration cfg = getConfig();
        Tier.load(cfg.getConfigurationSection("tiers"));

        String skyName = cfg.getString("worlds.sky", "skyrealms_sky");
        String overName = cfg.getString("worlds.overworld", "world");
        skyWorld = SkyWorld.loadOrCreate(skyName);
        if (skyWorld == null) {
            getLogger().severe("Could not load or create sky world '" + skyName + "'. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Weekly overworld recycle (off unless recycle.enabled). Its managed world replaces worlds.overworld.
        recycler = new dev.skyrealms.decay.recycle.WorldRecycler(this, cfg.getConfigurationSection("recycle"));
        String recycled = recycler.setup();
        if (recycled != null) overName = recycled;
        overworldName = overName;

        if (Bukkit.getWorld(overName) == null) {
            getLogger().warning("Overworld '" + overName + "' isn't loaded. Falling off islands won't work until it is.");
        }

        int spacing = cfg.getInt("islands.spacing", 2048);
        if (spacing < Tier.CONTINENTAL.footprint + 64) {
            getLogger().warning("islands.spacing (" + spacing + ") is too small for continental islands; using "
                    + (Tier.CONTINENTAL.footprint + 64));
            spacing = Tier.CONTINENTAL.footprint + 64;
        }
        if (cfg.getInt("config-version", 1) < 2) {
            // v0.12: islands sat inside the cloud layer and falls ended early. Lower the old defaults;
            // existing islands keep the height they were built at.
            int oldBase = cfg.getInt("islands.base-y", 180);
            cfg.set("islands.legacy-base-y", oldBase);
            if (oldBase == 180) cfg.set("islands.base-y", 140);
            if (cfg.getDouble("transit.depart-y", 100) == 100) cfg.set("transit.depart-y", -40);
            cfg.set("config-version", 2);
            saveConfig();
            getLogger().info("Config updated: new islands generate at Y " + cfg.getInt("islands.base-y")
                    + ", falls hand off at Y " + cfg.getDouble("transit.depart-y") + ". Existing islands stay where they are.");
        }
        if (cfg.getInt("config-version", 1) < 3) {
            // v0.12.2: the return gust now keeps lifting; raise the untouched old default for a higher flight.
            if (cfg.getDouble("return.launch-power", 1.8) == 1.8) cfg.set("return.launch-power", 2.2);
            cfg.set("config-version", 3);
            saveConfig();
        }
        baseY = cfg.getInt("islands.base-y", 140);
        IslandManager.newSeaY = baseY;
        IslandManager.legacySeaY = cfg.getInt("islands.legacy-base-y", baseY);

        library = new PieceLibrary(new File(getDataFolder(), "pieces"), getLogger());
        library.reload();
        PasteQueue paster = new PasteQueue(this, cfg.getInt("islands.blocks-per-tick", 20000));
        terrain = new dev.skyrealms.terrain.TerrainGenerator(this, baseY, cfg.getInt("islands.blocks-per-tick", 20000));
        biomes = new dev.skyrealms.terrain.BiomeCatalog(library);
        restyler = new dev.skyrealms.terrain.UndersideRestyler(this, baseY, cfg.getInt("islands.restyle-columns-per-tick", 400));
        grower = new IslandGrower(library, paster, terrain, baseY, cfg.getInt("islands.piece-y-jitter", 3),
                cfg.getInt("terrain.blob-radius-min", 11), cfg.getInt("terrain.blob-radius-max", 18));
        islands = new IslandManager(new File(getDataFolder(), "islands.yml"), spacing, getLogger());
        islands.load();
        progress = new dev.skyrealms.island.ProgressStore(new File(getDataFolder(), "players.yml"), getLogger());

        if (!new File(getDataFolder(), "levels.yml").exists()) saveResource("levels.yml", false);
        xp = new dev.skyrealms.level.XpService(this, new dev.skyrealms.level.Levels(new File(getDataFolder(), "levels.yml")));

        // Vault registers its economy late, so look it up once everything has enabled.
        money = new Money(cfg.getConfigurationSection("economy"));
        Bukkit.getScheduler().runTask(this, () -> {
            money = new Money(cfg.getConfigurationSection("economy"));
            getLogger().info(money.describe());
        });

        service = new RealmService(this);
        menus = new Menus(this, service);
        clearer = new dev.skyrealms.island.ClearQueue(this, cfg.getInt("islands.blocks-per-tick", 20000));

        var pm = getServer().getPluginManager();
        archiver = new dev.skyrealms.island.IslandArchiver(this, cfg.getConfigurationSection("prune"));
        pm.registerEvents(archiver, this);
        pm.registerEvents(new MenuListener(), this);
        shortcuts = new dev.skyrealms.menu.HotbarShortcuts(this, cfg.getConfigurationSection("hotbar"));
        pm.registerEvents(shortcuts, this);
        pm.registerEvents(new dev.skyrealms.level.XpListener(this), this);
        cosmetics = new dev.skyrealms.cosmetic.CosmeticService(this);
        namePrompt = new dev.skyrealms.cosmetic.NamePrompt(this);
        pm.registerEvents(namePrompt, this);
        Bukkit.getScheduler().runTaskTimer(this, new dev.skyrealms.cosmetic.CosmeticEffects(this), 40L, 10L);
        bedrock = new dev.skyrealms.bedrock.BedrockUi(this);
        // Islands from before levels existed get XP for the land and biomes they already have.
        Bukkit.getScheduler().runTask(this, () -> {
            for (var i : islands.all()) xp.backfill(i);
            islands.save();
        });
        pm.registerEvents(new FallTransit(this, skyName, this::overworldName, cfg.getConfigurationSection("transit")), this);
        pm.registerEvents(new ProtectionListener(islands, skyName), this);

        // Overworld decay (off unless decay.enabled is true). Starts after all worlds have loaded.
        decay = new dev.skyrealms.decay.DecayService(this,
                dev.skyrealms.decay.DecaySettings.load(cfg.getConfigurationSection("decay"), overName));
        if (recycled != null) decay.switchWorld(recycled); // the recycled world overrides decay.world
        Bukkit.getScheduler().runTask(this, () -> decay.start(skyName));

        SkylandCommands commands = new SkylandCommands(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> commands.register(event.registrar()));

        Bukkit.getScheduler().runTaskTimer(this, islands::save, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        if (islands != null) islands.save();
        if (decay != null && decay.isRunning()) decay.flush();
    }

    /** Lets Multiverse (or bukkit.yml) create the sky world with: generator SkyRealms */
    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new VoidGenerator();
    }

    public World skyWorld() { return skyWorld; }
    public IslandManager islands() { return islands; }
    public PieceLibrary library() { return library; }
    public IslandGrower grower() { return grower; }
    public Money money() { return money; }
    public int baseY() { return baseY; }
    public dev.skyrealms.terrain.BiomeCatalog biomes() { return biomes; }
    public dev.skyrealms.terrain.TerrainGenerator terrain() { return terrain; }
    public dev.skyrealms.terrain.UndersideRestyler restyler() { return restyler; }
    public RealmService service() { return service; }
    public Menus menus() { return menus; }
    private dev.skyrealms.menu.HotbarShortcuts shortcuts;
    public dev.skyrealms.menu.HotbarShortcuts shortcuts() { return shortcuts; }
    public dev.skyrealms.island.ClearQueue clearer() { return clearer; }
    public dev.skyrealms.island.IslandArchiver archiver() { return archiver; }
    public dev.skyrealms.level.XpService xp() { return xp; }
    public dev.skyrealms.island.ProgressStore progress() { return progress; }
    public dev.skyrealms.cosmetic.CosmeticService cosmetics() { return cosmetics; }
    public dev.skyrealms.cosmetic.NamePrompt namePrompt() { return namePrompt; }
    public dev.skyrealms.bedrock.BedrockUi bedrock() { return bedrock; }
    public dev.skyrealms.decay.DecayService decay() { return decay; }
    public dev.skyrealms.decay.recycle.WorldRecycler recycler() { return recycler; }
    public String overworldName() { return overworldName; }

    /** Switches every overworld target (falls, decay, safe spots) to another world. Used by the recycle. */
    public void setOverworldName(String name) {
        overworldName = name;
        if (decay != null) decay.switchWorld(name);
    }
}
