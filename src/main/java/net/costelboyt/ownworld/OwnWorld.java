package net.costelboyt.ownworld;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class OwnWorld extends JavaPlugin implements CommandExecutor, Listener, TabCompleter {

    private World ownWorldsDimension;
    private final Map<String, TerritoryData> territories = new HashMap<>();
    private final Map<UUID, Location> lastOutsideLocations = new HashMap<>();
    private final Map<UUID, String> activeGuiSelections = new HashMap<>();
    private final Map<UUID, BossBar> playerBossBars = new HashMap<>();

    private File dataFile;
    private FileConfiguration dataConfig;
    private final Random random = new Random();

    private static final double BASE_BORDER_SIZE = 80.0;
    private static final int GRID_SPACING = 3200;

    private final String guiTitle = "§lOwnWorld+";

    private final Material[] spinIcons = {
            Material.GRASS_BLOCK, Material.DIAMOND_ORE, Material.GOLD_BLOCK,
            Material.NETHERITE_SCRAP, Material.EMERALD, Material.BEACON,
            Material.AMETHYST_CLUSTER, Material.MAGMA_BLOCK, Material.ENDER_EYE
    };

    @Override
    public void onEnable() {
        WorldCreator creator = new WorldCreator("ownworlds");
        creator.environment(World.Environment.NORMAL);
        creator.type(WorldType.NORMAL);
        ownWorldsDimension = creator.createWorld();

        dataFile = new File(getDataFolder(), "worlds.yml");
        if (!dataFile.exists()) {
            getDataFolder().mkdirs();
            try { dataFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        loadTerritories();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("ownworld") != null) {
            getCommand("ownworld").setExecutor(this);
            getCommand("ownworld").setTabCompleter(this);
        }

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                updatePlayerBorderAndBossBar(player);
            }
        }, 20L, 20L);
    }

    @Override
    public void onDisable() {
        for (BossBar bar : playerBossBars.values()) {
            bar.removeAll();
        }
        playerBossBars.clear();
        saveTerritories();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }

        if (args.length == 0) {
            openSlotGUI(player);
            return true;
        }

        String action = args[0].toLowerCase();

        if (action.equals("help")) {
            sendHelpMenu(player);
            return true;
        }

        if (action.equals("back")) {
            if (!player.getWorld().getName().equals("ownworlds")) {
                player.sendMessage("§cYou can only use this command while inside a private world!");
                return true;
            }

            Location returnLoc = lastOutsideLocations.get(player.getUniqueId());
            if (returnLoc == null) {
                World overworld = Bukkit.getWorlds().get(0);
                returnLoc = player.getRespawnLocation();
                if (returnLoc == null || !returnLoc.getWorld().equals(overworld)) {
                    returnLoc = overworld != null ? overworld.getSpawnLocation() : player.getLocation();
                }
            }

            removeBossBar(player);
            player.teleport(returnLoc);
            lastOutsideLocations.remove(player.getUniqueId());
            player.sendMessage("§aTeleporting you back to your last overworld position.");
            return true;
        }

        if (action.equals("invite") || action.equals("remove")) {
            if (args.length < 2) {
                player.sendMessage("§cUsage: /ownworld " + action + " <player_name>");
                return true;
            }

            String targetName = args[1];
            Player targetPlayer = Bukkit.getPlayer(targetName);
            UUID targetUUID = targetPlayer != null ? targetPlayer.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();

            if (targetUUID == null) {
                player.sendMessage("§cPlayer not found.");
                return true;
            }

            Optional<Map.Entry<String, TerritoryData>> ownedWorldOpt = territories.entrySet().stream()
                    .filter(entry -> entry.getValue().ownerUUID.equals(player.getUniqueId()))
                    .findFirst();

            if (ownedWorldOpt.isEmpty()) {
                player.sendMessage("§cYou do not own any world territory to manage members!");
                return true;
            }

            String worldKey = ownedWorldOpt.get().getKey();
            TerritoryData data = ownedWorldOpt.get().getValue();

            if (action.equals("invite")) {
                if (data.ownerUUID.equals(targetUUID)) {
                    player.sendMessage("§cYou cannot invite yourself.");
                    return true;
                }
                if (data.trustedPlayers.contains(targetUUID)) {
                    player.sendMessage("§cThat player is already invited.");
                    return true;
                }
                data.trustedPlayers.add(targetUUID);
                player.sendMessage("§aSuccessfully invited " + targetName + " to your world '" + worldKey + "'!");
                if (targetPlayer != null) {
                    targetPlayer.sendMessage("§aYou have been invited to join " + player.getName() + "'s world! Use §e/ownworld join " + worldKey + " §ato visit.");
                }
            } else {
                if (!data.trustedPlayers.contains(targetUUID)) {
                    player.sendMessage("§cThat player is not invited.");
                    return true;
                }
                data.trustedPlayers.remove(targetUUID);
                player.sendMessage("§aRemoved " + targetName + " from world access.");
            }
            saveTerritories();
            return true;
        }

        if (args.length < 2) {
            sendHelpMenu(player);
            return true;
        }

        String worldName = args[1].toLowerCase();

        if (action.equals("create")) {
            if (territories.containsKey(worldName)) {
                player.sendMessage("§cA world with that name already exists!");
                return true;
            }

            UUID playerUUID = player.getUniqueId();
            boolean alreadyOwnsWorld = territories.values().stream().anyMatch(d -> d.ownerUUID.equals(playerUUID));

            if (alreadyOwnsWorld) {
                player.sendMessage("§cYou have already created a world!");
                return true;
            }

            player.sendMessage("§eFinding a suitable terrain territory... Please wait...");

            Location loc = findRandomUnoccupiedLocation();
            int highestY = ownWorldsDimension.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
            loc.setY(highestY + 1);

            territories.put(worldName, new TerritoryData(loc, playerUUID, new ArrayList<>(), 1, 0));
            saveTerritories();

            player.sendMessage("§aWorld '" + args[1] + "' successfully created!");
            return true;

        } else if (action.equals("join")) {
            if (!territories.containsKey(worldName)) {
                player.sendMessage("§cThat world does not exist.");
                return true;
            }

            TerritoryData data = territories.get(worldName);
            UUID viewerUUID = player.getUniqueId();

            if (!data.ownerUUID.equals(viewerUUID) && !data.trustedPlayers.contains(viewerUUID)) {
                player.sendMessage("§cYou do not have permission to join this world!");
                return true;
            }

            if (!player.getWorld().getName().equals("ownworlds")) {
                lastOutsideLocations.put(player.getUniqueId(), player.getLocation());
            }

            Location dest = data.location.clone().add(0.5, 0, 0.5);
            player.teleport(dest);
            player.sendMessage("§aTeleporting to world: " + args[1]);
            return true;

        } else if (action.equals("delete")) {
            if (!territories.containsKey(worldName)) {
                player.sendMessage("§cThat world does not exist.");
                return true;
            }

            TerritoryData data = territories.get(worldName);
            if (!data.ownerUUID.equals(player.getUniqueId())) {
                player.sendMessage("§cOnly the creator can delete this territory.");
                return true;
            }

            World overworld = Bukkit.getWorlds().get(0);
            Location spawnBackup = overworld != null ? overworld.getSpawnLocation() : player.getLocation();

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getWorld().getName().equals("ownworlds") && p.getLocation().distanceSquared(data.location) < 40000) {
                    removeBossBar(p);
                    p.teleport(spawnBackup);
                    p.sendMessage("§6The territory you were in has been deleted.");
                }
            }

            territories.remove(worldName);
            saveTerritories();
            player.sendMessage("§aTerritory successfully deleted!");
            return true;
        }

        sendHelpMenu(player);
        return true;
    }

    private void sendHelpMenu(Player player) {
        player.sendMessage("§7§m================§r §b§lOwnWorld+ Help §r§7§m================");
        player.sendMessage("§b/ownworld §7- Opens the Slot Machine World Browser GUI.");
        player.sendMessage("§b/ownworld help §7- Shows this command reference menu.");
        player.sendMessage("§b/ownworld create <name> §7- Generates your custom private territory.");
        player.sendMessage("§b/ownworld join <name> §7- Teleports to your territory or one you are invited to.");
        player.sendMessage("§b/ownworld back §7- Returns to your last location outside ownworlds.");
        player.sendMessage("§b/ownworld invite <player> §7- Allows a friend to build/break in your territory.");
        player.sendMessage("§b/ownworld remove <player> §7- Revokes building/breaking rights from a friend.");
        player.sendMessage("§b/ownworld delete <name> §7- Permanently wipes your world (Creator only).");
        player.sendMessage("§7§m====================================================");
    }

    private void openSlotGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, guiTitle);

        ItemStack blackFrame = createGuiItem(Material.BLACK_CONCRETE, " ");
        ItemStack grayFrame = createGuiItem(Material.GRAY_CONCRETE, " ");

        gui.setItem(3, blackFrame);
        gui.setItem(21, blackFrame);
        gui.setItem(12, grayFrame);

        gui.setItem(5, blackFrame);
        gui.setItem(23, blackFrame);
        gui.setItem(14, grayFrame);

        gui.setItem(4, blackFrame);
        gui.setItem(22, blackFrame);
        gui.setItem(13, grayFrame);

        gui.setItem(11, createGuiItem(Material.REDSTONE_TORCH, "§e§lSPIN MACHINE", "§7Click here to randomize worlds!"));

        gui.setItem(15, createGuiItem(Material.GREEN_TERRACOTTA, "§a§lVISIT WORLD",
                "§7Click here to visit the selected",
                "§7world as a guest explorer.",
                "",
                "§cNote: You cannot build, break,",
                "§cor attack while visiting."));

        gui.setItem(17, createGuiItem(Material.OAK_SIGN, "§6§lInstructions",
                "§fClick the §cRedstone Torch §fto",
                "§fspin the slot machine nodes.",
                "§fThe center row displays worlds",
                "§floaded from active databases.",
                "§fClick the §aGreen Terracotta §fto visit."));

        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(guiTitle)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = event.getCurrentItem();

        if (clicked == null || clicked.getType() == Material.AIR) return;

        if (clicked.getType() == Material.REDSTONE_TORCH) {
            if (territories.isEmpty()) {
                player.sendMessage("§cThere are no registered world territories on this server yet!");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
                return;
            }
            startSlotAnimation(player, event.getInventory());
            return;
        }

        if (clicked.getType() == Material.GREEN_TERRACOTTA) {
            String targetedWorld = activeGuiSelections.get(player.getUniqueId());

            if (targetedWorld == null || !territories.containsKey(targetedWorld)) {
                player.sendMessage("§cPlease spin the machine first to find a valid world to visit!");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
                return;
            }

            TerritoryData data = territories.get(targetedWorld);
            player.closeInventory();

            if (!player.getWorld().getName().equals("ownworlds")) {
                lastOutsideLocations.put(player.getUniqueId(), player.getLocation());
            }

            Location dest = data.location.clone().add(0.5, 0, 0.5);
            player.teleport(dest);
            player.sendMessage("§aExploring §e" + targetedWorld);
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getView().getTitle().equals(guiTitle)) {
            activeGuiSelections.remove(event.getPlayer().getUniqueId());
        }
    }

    private void startSlotAnimation(Player player, Inventory gui) {
        List<Map.Entry<String, TerritoryData>> worldList = new ArrayList<>(territories.entrySet());
        activeGuiSelections.remove(player.getUniqueId());

        new BukkitRunnable() {
            int ticks = 0;
            final int totalDuration = 25;

            @Override
            public void run() {
                if (ticks >= totalDuration) {
                    cancel();

                    Map.Entry<String, TerritoryData> finalWorldCenter = worldList.get(random.nextInt(worldList.size()));
                    setSlotItem(gui, 13, finalWorldCenter);

                    activeGuiSelections.put(player.getUniqueId(), finalWorldCenter.getKey());

                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
                    player.sendMessage("§a§l★ Slot Aligned! §7Found world territory: §e" + finalWorldCenter.getKey());
                    return;
                }

                Map.Entry<String, TerritoryData> randomCenterTop = worldList.get(random.nextInt(worldList.size()));
                Map.Entry<String, TerritoryData> randomCenterMid = worldList.get(random.nextInt(worldList.size()));
                Map.Entry<String, TerritoryData> randomCenterBot = worldList.get(random.nextInt(worldList.size()));

                setSlotItem(gui, 4, randomCenterTop);
                setSlotItem(gui, 13, randomCenterMid);
                setSlotItem(gui, 22, randomCenterBot);

                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 1.0f + ((float) ticks / totalDuration));
                ticks++;
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    private void setSlotItem(Inventory gui, int slot, Map.Entry<String, TerritoryData> worldEntry) {
        Material displayMaterial = spinIcons[Math.abs(worldEntry.getKey().hashCode()) % spinIcons.length];

        String ownerName = Bukkit.getOfflinePlayer(worldEntry.getValue().ownerUUID).getName();
        if (ownerName == null) ownerName = "Unknown";

        gui.setItem(slot, createGuiItem(displayMaterial, "§b§lWorld: " + worldEntry.getKey(),
                "§7Owner: §f" + ownerName,
                "§7Level: §e" + worldEntry.getValue().level,
                "§7Center X: §f" + worldEntry.getValue().location.getBlockX(),
                "§7Center Z: §f" + worldEntry.getValue().location.getBlockZ()));
    }

    private ItemStack createGuiItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!event.getPlayer().getWorld().getName().equals("ownworlds")) return;

        Location blockLoc = event.getBlock().getLocation();
        Map.Entry<String, TerritoryData> currentTerritory = getTerritoryAt(blockLoc);

        if (currentTerritory == null) return;

        if (!hasBuildAccess(event.getPlayer(), blockLoc)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cYou do not have permission to break blocks here!");
            return;
        }

        addTerritoryXp(currentTerritory.getValue(), currentTerritory.getKey(), 1);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!event.getPlayer().getWorld().getName().equals("ownworlds")) return;

        Location blockLoc = event.getBlock().getLocation();
        Map.Entry<String, TerritoryData> currentTerritory = getTerritoryAt(blockLoc);

        if (currentTerritory == null) return;

        if (!hasBuildAccess(event.getPlayer(), blockLoc)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cYou do not have permission to build blocks here!");
            return;
        }

        addTerritoryXp(currentTerritory.getValue(), currentTerritory.getKey(), 1);
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!attacker.getWorld().getName().equals("ownworlds")) return;

        if (!hasBuildAccess(attacker, attacker.getLocation())) {
            event.setCancelled(true);
            attacker.sendMessage("§cYou cannot attack anyone or anything in this territory!");
        }
    }

    private void addTerritoryXp(TerritoryData data, String worldName, int amount) {
        data.xp += amount;
        int requiredXp = data.level * 100;

        if (data.xp >= requiredXp) {
            data.xp -= requiredXp;
            data.level++;

            saveTerritories();

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getWorld().getName().equals("ownworlds") && getTerritoryAt(p.getLocation()) != null
                        && getTerritoryAt(p.getLocation()).getKey().equals(worldName)) {

                    p.sendMessage("§a§l▲ LEVEL UP! §7This territory reached §eLevel " + data.level + "§7! Size expanded!");
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                    updatePlayerBorderAndBossBar(p);
                }
            }
        }
    }

    private boolean hasBuildAccess(Player player, Location targetLoc) {
        Map.Entry<String, TerritoryData> match = getTerritoryAt(targetLoc);
        if (match != null) {
            UUID uuid = player.getUniqueId();
            return match.getValue().ownerUUID.equals(uuid) || match.getValue().trustedPlayers.contains(uuid);
        }
        return false;
    }

    private Map.Entry<String, TerritoryData> getTerritoryAt(Location targetLoc) {
        for (Map.Entry<String, TerritoryData> entry : territories.entrySet()) {
            TerritoryData data = entry.getValue();
            double dynamicBorderRadius = (BASE_BORDER_SIZE + data.level) / 2.0;

            double minX = data.location.getX() - dynamicBorderRadius;
            double maxX = data.location.getX() + dynamicBorderRadius;
            double minZ = data.location.getZ() - dynamicBorderRadius;
            double maxZ = data.location.getZ() + dynamicBorderRadius;

            if (targetLoc.getX() >= minX && targetLoc.getX() <= maxX &&
                    targetLoc.getZ() >= minZ && targetLoc.getZ() <= maxZ) {
                return entry;
            }
        }
        return null;
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        updatePlayerBorderAndBossBar(event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null && event.getTo().getWorld().getName().equals("ownworlds")) {
            if (event.getFrom().getWorld() != null && !event.getFrom().getWorld().getName().equals("ownworlds")) {
                lastOutsideLocations.put(event.getPlayer().getUniqueId(), event.getFrom());
            }
            Bukkit.getScheduler().runTaskLater(this, () -> updatePlayerBorderAndBossBar(event.getPlayer()), 5L);
        }
    }

    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event) {
        removeBossBar(event.getPlayer());
    }

    private void removeBossBar(Player player) {
        BossBar bar = playerBossBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.removePlayer(player);
        }
    }

    private void updatePlayerBorderAndBossBar(Player player) {
        if (!player.getWorld().getName().equals("ownworlds")) {
            player.setWorldBorder(player.getWorld().getWorldBorder());
            removeBossBar(player);
            return;
        }

        Map.Entry<String, TerritoryData> currentMatch = getTerritoryAt(player.getLocation());

        if (currentMatch != null) {
            TerritoryData data = currentMatch.getValue();

            double activeWidth = BASE_BORDER_SIZE + data.level;
            org.bukkit.WorldBorder personalBorder = Bukkit.createWorldBorder();
            personalBorder.setCenter(data.location.getX(), data.location.getZ());
            personalBorder.setSize(activeWidth);
            personalBorder.setDamageAmount(0.2);
            personalBorder.setDamageBuffer(0.0);
            personalBorder.setWarningDistance(5);
            player.setWorldBorder(personalBorder);

            BossBar bar = playerBossBars.get(player.getUniqueId());
            String titleString = "§b§l" + currentMatch.getKey().toUpperCase() + " §7- §e§lLEVEL " + data.level + " §f(" + data.xp + "/" + (data.level * 100) + " XP)";

            double progressRatio = (double) data.xp / (data.level * 100);
            progressRatio = Math.max(0.0, Math.min(1.0, progressRatio));

            if (bar == null) {
                bar = Bukkit.createBossBar(titleString, BarColor.BLUE, BarStyle.SEGMENTED_10);
                bar.addPlayer(player);
                playerBossBars.put(player.getUniqueId(), bar);
            } else {
                bar.setTitle(titleString);
            }
            bar.setProgress(progressRatio);

        } else {
            player.setWorldBorder(player.getWorld().getWorldBorder());
            removeBossBar(player);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> subCommands = new ArrayList<>(Arrays.asList("create", "join", "delete", "invite", "remove", "back", "help"));
            String currentInput = args[0].toLowerCase();
            return subCommands.stream().filter(sub -> sub.startsWith(currentInput)).collect(Collectors.toList());
        }

        if (args.length == 2) {
            String currentInput = args[1].toLowerCase();
            String sub = args[0].toLowerCase();

            if (sub.equals("invite") || sub.equals("remove")) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(currentInput))
                        .collect(Collectors.toList());
            }

            if (sub.equals("join")) {
                if (sender instanceof Player player) {
                    UUID uuid = player.getUniqueId();
                    return territories.entrySet().stream()
                            .filter(entry -> entry.getValue().ownerUUID.equals(uuid) || entry.getValue().trustedPlayers.contains(uuid))
                            .map(Map.Entry::getKey)
                            .filter(name -> name.toLowerCase().startsWith(currentInput))
                            .collect(Collectors.toList());
                }
            }

            if (sub.equals("delete")) {
                if (sender instanceof Player player) {
                    return territories.entrySet().stream()
                            .filter(entry -> entry.getValue().ownerUUID.equals(player.getUniqueId()))
                            .map(Map.Entry::getKey)
                            .filter(name -> name.toLowerCase().startsWith(currentInput))
                            .collect(Collectors.toList());
                }
            }
        }
        return completions;
    }

    private Location findRandomUnoccupiedLocation() {
        while (true) {
            int x = (random.nextInt(2000) - 1000) * GRID_SPACING;
            int z = (random.nextInt(2000) - 1000) * GRID_SPACING;
            Location potential = new Location(ownWorldsDimension, x + 8, 64, z + 8);

            boolean collision = territories.values().stream()
                    .anyMatch(d -> d.location.getBlockX() == potential.getBlockX() && d.location.getBlockZ() == potential.getBlockZ());

            if (!collision) {
                return potential;
            }
        }
    }

    private void loadTerritories() {
        if (dataConfig.getConfigurationSection("worlds") == null) return;
        for (String key : dataConfig.getConfigurationSection("worlds").getKeys(false)) {
            double x = dataConfig.getDouble("worlds." + key + ".x");
            double y = dataConfig.getDouble("worlds." + key + ".y");
            double z = dataConfig.getDouble("worlds." + key + ".z");
            UUID ownerUUID = UUID.fromString(dataConfig.getString("worlds." + key + ".owner"));

            List<String> trustedStrings = dataConfig.getStringList("worlds." + key + ".trusted");
            List<UUID> trustedUUIDs = trustedStrings.stream().map(UUID::fromString).collect(Collectors.toList());

            int level = dataConfig.getInt("worlds." + key + ".level", 1);
            int xp = dataConfig.getInt("worlds." + key + ".xp", 0);

            territories.put(key, new TerritoryData(new Location(ownWorldsDimension, x, y, z), ownerUUID, trustedUUIDs, level, xp));
        }
    }

    private void saveTerritories() {
        dataConfig.set("worlds", null);
        for (Map.Entry<String, TerritoryData> entry : territories.entrySet()) {
            String key = entry.getKey();
            TerritoryData data = entry.getValue();
            dataConfig.set("worlds." + key + ".x", data.location.getX());
            dataConfig.set("worlds." + key + ".y", data.location.getY());
            dataConfig.set("worlds." + key + ".z", data.location.getZ());
            dataConfig.set("worlds." + key + ".owner", data.ownerUUID.toString());
            dataConfig.set("worlds." + key + ".level", data.level);
            dataConfig.set("worlds." + key + ".xp", data.xp);

            List<String> trustedStrings = data.trustedPlayers.stream().map(UUID::toString).collect(Collectors.toList());
            dataConfig.set("worlds." + key + ".trusted", trustedStrings);
        }
        try { dataConfig.save(dataFile); } catch (IOException e) { e.printStackTrace(); }
    }

    private static class TerritoryData {
        final Location location;
        final UUID ownerUUID;
        final List<UUID> trustedPlayers;
        int level;
        int xp;

        TerritoryData(Location location, UUID ownerUUID, List<UUID> trustedPlayers, int level, int xp) {
            this.location = location;
            this.ownerUUID = ownerUUID;
            this.trustedPlayers = trustedPlayers;
            this.level = level;
            this.xp = xp;
        }
    }
}