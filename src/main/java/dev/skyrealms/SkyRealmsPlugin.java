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
    private dev.skyrealms.terrain.BiomeCatalog biomes;
    private RealmService service;
    private Menus menus;
    private dev.skyrealms.island.ClearQueue clearer;
    private dev.skyrealms.island.IslandArchiver archiver;

    @Override
    public void onEnable() {
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
        if (Bukkit.getWorld(overName) == null) {
            getLogger().warning("Overworld '" + overName + "' isn't loaded. Falling off islands won't work until it is.");
        }

        int spacing = cfg.getInt("islands.spacing", 2048);
        if (spacing < Tier.CONTINENTAL.footprint + 64) {
            getLogger().warning("islands.spacing (" + spacing + ") is too small for continental islands; using "
                    + (Tier.CONTINENTAL.footprint + 64));
            spacing = Tier.CONTINENTAL.footprint + 64;
        }
        baseY = cfg.getInt("islands.base-y", 180);

        library = new PieceLibrary(new File(getDataFolder(), "pieces"), getLogger());
        library.reload();
        PasteQueue paster = new PasteQueue(this, cfg.getInt("islands.blocks-per-tick", 20000));
        terrain = new dev.skyrealms.terrain.TerrainGenerator(this, baseY, cfg.getInt("islands.blocks-per-tick", 20000));
        biomes = new dev.skyrealms.terrain.BiomeCatalog(library);
        grower = new IslandGrower(library, paster, terrain, baseY, cfg.getInt("islands.piece-y-jitter", 3),
                cfg.getInt("terrain.blob-radius-min", 11), cfg.getInt("terrain.blob-radius-max", 18));
        islands = new IslandManager(new File(getDataFolder(), "islands.yml"), spacing, getLogger());
        islands.load();

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
        pm.registerEvents(new FallTransit(this, skyName, overName, cfg.getConfigurationSection("transit")), this);
        pm.registerEvents(new ProtectionListener(islands, skyName), this);

        SkylandCommands commands = new SkylandCommands(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> commands.register(event.registrar()));

        Bukkit.getScheduler().runTaskTimer(this, islands::save, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        if (islands != null) islands.save();
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
    public RealmService service() { return service; }
    public Menus menus() { return menus; }
    public dev.skyrealms.island.ClearQueue clearer() { return clearer; }
    public dev.skyrealms.island.IslandArchiver archiver() { return archiver; }
}
