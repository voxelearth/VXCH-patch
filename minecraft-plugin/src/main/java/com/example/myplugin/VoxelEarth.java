package com.example.voxelearth;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.World;

import org.json.JSONObject;

// Field 
import java.lang.reflect.Field;

import java.util.concurrent.ConcurrentHashMap;

import java.util.UUID;

import org.bukkit.boss.*;   // for BossBar, BarColor, etc.

import java.util.Map;
import java.util.HashMap;
import java.util.StringJoiner;
import java.util.Iterator;

import java.util.List;


public class VoxelEarth extends JavaPlugin {

    // Hold a single instance of VoxelChunkGenerator
    private VoxelChunkGenerator voxelChunkGenerator;
    private PlayerMovementListener playerMovementListener;

    // Per-player movement radius
    private ConcurrentHashMap<UUID, Double> movementRadiusMap = new ConcurrentHashMap<>();
    private ConcurrentHashMap<UUID, Double> visitRadiusMap = new ConcurrentHashMap<>();

    // Per-player attribution map
    private Map<UUID, Map<String, Integer>> attributionMap = new ConcurrentHashMap<>();
    private Map<UUID, BossBar> playerBossBars = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        getLogger().info("VoxelEarth has been enabled");
        
        // Register the player movement listener
        // getServer().getPluginManager().registerEvents(new PlayerMovementListener(this), this);
        playerMovementListener = new PlayerMovementListener(this);
        getServer().getPluginManager().registerEvents(playerMovementListener, this);
        getLogger().info("Player movement listener registered successfully");


        // Re-attach the generator to the existing world
        reattachGeneratorToWorld("world");
        getLogger().info("VoxelChunkGenerator re-attached to world 'world'");
    }

    public PlayerMovementListener getPlayerMovementListener() {
        return playerMovementListener;
    }

    @Override
    public void onDisable() {
        getLogger().info("VoxelEarth has been disabled");
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        if (voxelChunkGenerator == null) {
            getLogger().info("VoxelEarth making new Chunk Generator");
            voxelChunkGenerator = new VoxelChunkGenerator(this);
        }
        getLogger().info("VoxelEarth is returning Default World Generator");
        return voxelChunkGenerator;
    }

    public VoxelChunkGenerator getVoxelChunkGenerator() {
        if (voxelChunkGenerator == null) {
            getLogger().info("VoxelEarth making new Chunk Generator");
            voxelChunkGenerator = new VoxelChunkGenerator(this);
        }
        return voxelChunkGenerator;
    }

    // Store movement radius (used for onPlayerMove loads)
    public void setMovementRadius(UUID playerId, double radius) {
        movementRadiusMap.put(playerId, radius);
    }

    // Store visit radius (used for initial "visit" loads)
    public void setVisitRadius(UUID playerId, double radius) {
        visitRadiusMap.put(playerId, radius);
    }

    public double getMovementRadius(UUID playerId) {
        // default to 50 if none set
        return movementRadiusMap.getOrDefault(playerId, 50.0);
    }

    public double getVisitRadius(UUID playerId) {
        // default to 250 if none set
        return visitRadiusMap.getOrDefault(playerId, 250.0);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("radius")) {
            // /radius <value>
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can set radius.");
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage("Usage: /radius <distance>");
                return true;
            }
            try {
                double val = Double.parseDouble(args[0]);
                Player p = (Player) sender;
                setMovementRadius(p.getUniqueId(), val);
                p.sendMessage("Your normal movement radius is now " + val);
            } catch (NumberFormatException e) {
                sender.sendMessage("Invalid number: " + args[0]);
            }
            return true;

        } else if (command.getName().equalsIgnoreCase("visitradius")) {
            // /visitradius <value>
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can set visit radius.");
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage("Usage: /visitradius <distance>");
                return true;
            }
            try {
                double val = Double.parseDouble(args[0]);
                Player p = (Player) sender;
                setVisitRadius(p.getUniqueId(), val);
                p.sendMessage("Your visit radius is now " + val);
            } catch (NumberFormatException e) {
                sender.sendMessage("Invalid number: " + args[0]);
            }
            return true;
        } 
        else if (command.getName().equalsIgnoreCase("createcustomworld")) {
            if (args.length == 1) {
                String worldName = args[0];
                WorldCreator worldCreator = new WorldCreator(worldName);
                worldCreator.generator(new VoxelChunkGenerator(this));
                World world = Bukkit.createWorld(worldCreator);
                sender.sendMessage("Custom world '" + worldName + "' created!");
                return true;
            } else {
                sender.sendMessage("Usage: /createcustomworld <worldname>");
                return false;
            }
        } else if (command.getName().equalsIgnoreCase("regenchunks")) {
            if (args.length == 6) {  // Updated to expect 6 arguments
                double scaleX = Double.parseDouble(args[0]);
                double scaleY = Double.parseDouble(args[1]);
                double scaleZ = Double.parseDouble(args[2]);
                double newOffsetX = Double.parseDouble(args[3]);
                double newOffsetY = Double.parseDouble(args[4]);
                double newOffsetZ = Double.parseDouble(args[5]);

                World world = Bukkit.getWorld("world"); // Replace with your world name
                if (world == null) {
                    sender.sendMessage("World not found!");
                    return false;
                }

                if (voxelChunkGenerator == null) {
                    getLogger().info("VoxelEarth making new Chunk Generator");
                    voxelChunkGenerator = new VoxelChunkGenerator(this);
                }

                // Call regenChunks with individual scaling and offsets for each axis
                voxelChunkGenerator.regenChunks(world, scaleX, scaleY, scaleZ, newOffsetX, newOffsetY, newOffsetZ);

                sender.sendMessage("Chunks regenerated with new parameters.");
                return true;
            } else {
                sender.sendMessage("Usage: /regenchunks <scaleX> <scaleY> <scaleZ> <offsetX> <offsetY> <offsetZ>");
                return false;
            }
        } else if (command.getName().equalsIgnoreCase("loadjson")) {
            if (args.length == 7) {
                String filename = args[0];
                double scaleX = Double.parseDouble(args[1]);
                double scaleY = Double.parseDouble(args[2]);
                double scaleZ = Double.parseDouble(args[3]);
                double offsetX = Double.parseDouble(args[4]);
                double offsetY = Double.parseDouble(args[5]);
                double offsetZ = Double.parseDouble(args[6]);

                World world = Bukkit.getWorld("world"); // future - player's world or custom?
                if (world == null) {
                    sender.sendMessage("World not found!");
                    return false;
                }

                if (voxelChunkGenerator == null) {
                    getLogger().info("Creating new VoxelChunkGenerator");
                    voxelChunkGenerator = new VoxelChunkGenerator(this);
                }

                try {
                    voxelChunkGenerator.loadMaterialColors();
                    voxelChunkGenerator.loadJson(filename, scaleX, scaleY, scaleZ, offsetX, offsetY, offsetZ);
                    // voxelChunkGenerator.regenChunks(world);
                    sender.sendMessage("JSON file loaded and chunks regenerated.");
                } catch (Exception e) {
                    sender.sendMessage("Failed to load JSON file: " + filename);
                    e.printStackTrace();
                }
                return true;
            } else {
                sender.sendMessage("Usage: /loadjson <filename> <scaleX> <scaleY> <scaleZ> <offsetX> <offsetY> <offsetZ>");
                return false;
            }
        } else     if (command.getName().equalsIgnoreCase("visit")) {
            if (args.length == 0) {
                sender.sendMessage("Usage: /visit <location>");
                return false;
            }

            String location = String.join(" ", args);
            Player player = (Player) sender;

            // Geocode the location asynchronously
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    double[] latLng = geocodeLocation(location);
                    if (latLng == null) {
                        sender.sendMessage("Failed to find location: " + location);
                        return;
                    } else {
                        sender.sendMessage("Found location: " + latLng[0] + ", " + latLng[1]);

                    }

                    if (voxelChunkGenerator == null) {
                        getLogger().info("Creating new VoxelChunkGenerator");
                        voxelChunkGenerator = new VoxelChunkGenerator(this);
                    }

                    // Here's where we reset the player's origin
                    // so the next time they move, a fresh origin is chosen
                    PlayerMovementListener pml = getPlayerMovementListener();
                    if (pml != null) {
                        pml.resetPlayerOrigin(player.getUniqueId());
                    }

                    voxelChunkGenerator.resetOriginForVisit(player.getUniqueId());

                    // Convert lat/lng to Minecraft coordinates
                    int[] chunkCoords = voxelChunkGenerator.latLngToMinecraft(latLng[0], latLng[1]);
                    
                    // Multiply the x coord by 64 and the z by 10
                    // chunkCoords[0] = (int) (chunkCoords[0] * 64.5860077);
                    // chunkCoords[1] = (int) (chunkCoords[1] * 9.60032897);
                    
                    int[] playerCoords = voxelChunkGenerator.latLngToBlock(latLng[0], latLng[1]);


                    // Teleport and load the chunk
                    Bukkit.getScheduler().runTask(this, () -> {
                        World world = player.getWorld();
                        teleportAndLoadChunk(player, world, chunkCoords[0], chunkCoords[1], playerCoords[0], playerCoords[1]);
                    });

                } catch (Exception e) {
                    sender.sendMessage("An error occurred while processing the location.");
                    e.printStackTrace();
                }
            });

            return true;
        }
        return false;
    }


private double[] geocodeLocation(String location) throws IOException {
    String apiUrl = "https://maps.googleapis.com/maps/api/geocode/json";
    String apiKey = "AIzaSyDV0rBF5y2f_xsSNj32fxvhqj3ZErTt6HQ"; // Replace with your API key

    String requestUrl = apiUrl + "?address=" + location.replace(" ", "+") + "&key=" + apiKey;
    HttpURLConnection connection = (HttpURLConnection) new URL(requestUrl).openConnection();
    connection.setRequestMethod("GET");

    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
    StringBuilder response = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
        response.append(line);
    }
    reader.close();

    JSONObject jsonResponse = new JSONObject(response.toString());
    if (!jsonResponse.getString("status").equals("OK")) {
        return null;
    }

    JSONObject locationObject = jsonResponse
            .getJSONArray("results")
            .getJSONObject(0)
            .getJSONObject("geometry")
            .getJSONObject("location");

    double lat = locationObject.getDouble("lat");
    double lng = locationObject.getDouble("lng");

    return new double[]{lat, lng};
}

private void teleportAndLoadChunk(Player player, World world, int x, int z, int playerX, int playerZ) {
    int chunkX = x; //>> 4;
    int chunkZ = z; //>> 4;

    // Load the chunk to trigger generation if needed
    if (!world.isChunkLoaded(chunkX, chunkZ)) {
        System.out.println("Loading chunk: " + chunkX + ", " + chunkZ);
        // world.loadChunk(chunkX, chunkZ, true);
        // voxelChunkGenerator.loadChunk(chunkX, chunkZ);
        voxelChunkGenerator.loadChunk(player.getUniqueId(), chunkX, chunkZ, true, (blockLocation) -> {
            Bukkit.getScheduler().runTask(this, () -> {
                blockLocation[1] += 50;
                System.out.println("Block location: " + blockLocation[0] + ", " + blockLocation[1]);
                Location location = new Location(world, blockLocation[0], blockLocation[1], blockLocation[2]);
                player.sendMessage("You are now at: " + blockLocation[0] + ", " + blockLocation[1] + ", " + blockLocation[2]);
                player.teleport(location);
                player.sendMessage("Welcome to your destination!");
                Bukkit.dispatchCommand(player, "paper fixlight 32");
                getLogger().info("Teleported player to: " + blockLocation[0] + ", " + blockLocation[1] + ", " + blockLocation[2]);
            });
        });
    } else {
        System.out.println("Chunk already loaded: " + chunkX + ", " + chunkZ);
        Location location = new Location(world, playerX, 100, playerZ);
        player.sendMessage("Chunk preloaded. You are now at: " + playerX + ", 100, " + playerZ);
        player.teleport(location);
        player.sendMessage("Welcome to your destination!");
        getLogger().info("Teleported player to: " + playerX + ", " + playerZ);
    }
}


private void reattachGeneratorToWorld(String worldName) {
    World world = Bukkit.getWorld(worldName);
    if (world != null) {
        getLogger().info("Re-attaching VoxelChunkGenerator to existing world: " + worldName);

        // Since the world exists, we need to set its generator
        // Unfortunately, Bukkit doesn't provide a direct method to set a generator on an existing world
        // So we need to access the world's generator field via reflection (this is a workaround)

        try {
            Field generatorField = World.class.getDeclaredField("generator");
            getLogger().info("generatorField: " + generatorField);

            generatorField.setAccessible(true);
            generatorField.set(world, getVoxelChunkGenerator());

            getLogger().info("Successfully re-attached VoxelChunkGenerator to world: " + worldName);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            getLogger().severe("Failed to re-attach VoxelChunkGenerator to world: " + worldName);
            e.printStackTrace();
        }
    } else {
        getLogger().info("World '" + worldName + "' does not exist.");
    }
}

// Called from TileDownloader parseCopyrightLine => plugin.addCopyrightAttributions()
public synchronized void addCopyrightAttributions(UUID playerId, List<String> names) {
    // 1) get or init the player's map
    Map<String, Integer> map = attributionMap.computeIfAbsent(playerId, k-> new HashMap<>());

    // 2) First decrement everyone's TTL
    for (Iterator<Map.Entry<String, Integer>> it = map.entrySet().iterator(); it.hasNext(); ) {
        Map.Entry<String, Integer> e = it.next();
        int newTtl = e.getValue() - 1;
        if (newTtl <= 0) {
            // remove
            it.remove();
        } else {
            e.setValue(newTtl);
        }
    }

    // 3) For each newly mentioned name, set TTL back to 5
    for (String name : names) {
        name = name.trim();
        if (name.isEmpty()) continue;
        map.put(name, 5); 
    }

    // 4) Now update the boss bar text
    updateAttributionBossBar(playerId);
}

/** Creates or updates the player's boss bar with the aggregated names. */
private synchronized void updateAttributionBossBar(UUID playerId) {
    Player p = getServer().getPlayer(playerId);
    if (p == null) return; // offline?

    // Get or create the BossBar
    BossBar bar = playerBossBars.get(playerId);
    if (bar == null) {
        bar = Bukkit.createBossBar("Voxel Earth - Attribution", BarColor.BLUE, BarStyle.SOLID);
        // so player can see it
        bar.addPlayer(p);
        playerBossBars.put(playerId, bar);
    }

    // Build text: "Voxel Earth - Attribution <name1>, <name2>, ..."
    Map<String, Integer> map = attributionMap.getOrDefault(playerId, new HashMap<>());
    if (map.isEmpty()) {
        // If nothing left, we default attribution to Google
        bar.setTitle("Voxel Earth - Attribution Google");
    } else {
        StringJoiner joiner = new StringJoiner(", ");
        for (String s : map.keySet()) {
            joiner.add(s);
        }
        String line = "Voxel Earth - Attribution " + joiner.toString();
        bar.setTitle(line);
        bar.setVisible(true);
    }

    // You could also set bar progress to 1.0, or some other dynamic info
    bar.setProgress(1.0);
}


}
