package com.example.voxelearth;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.generator.ChunkGenerator.ChunkData;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.json.JSONTokener;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.Chunk;
import java.util.function.BiConsumer;

import java.io.InputStream;
import java.awt.Color;
import java.util.Iterator;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import org.bukkit.Material;

// player
import java.util.UUID;
import org.bukkit.entity.Player;

// VXCH binary format
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

// location
import org.bukkit.Location;

/**
 * A profiled version of VoxelChunkGenerator that times major operations
 * to identify performance bottlenecks.
 */
public class VoxelChunkGenerator extends ChunkGenerator {

    private static final double LAT_ORIGIN = 50.081033020810736;
    private static final double LNG_ORIGIN = 14.451093643141064;
    private static final String API_KEY = "AIzaSyDV0rBF5y2f_xsSNj32fxvhqj3ZErTt6HQ"; // Your API key here
    private TileDownloader tileDownloader;
    private ConcurrentHashMap<String, Map<String, Object>> indexedBlocks = new ConcurrentHashMap<>();
    private static final List<MaterialColor> MATERIAL_COLORS = new ArrayList<>();
    private Map<Integer, Material> colorToMaterialCache = new HashMap<>();
    private Map<UUID, double[]> playerOrigins = new ConcurrentHashMap<>();

    private Map<UUID, Integer> playerXOffsets = new ConcurrentHashMap<>();
    private Map<UUID, Integer> playerYOffsets = new ConcurrentHashMap<>();
    private Map<UUID, Integer> playerZOffsets = new ConcurrentHashMap<>();

    // origin ecef
    private double[] originEcef; // [x0, y0, z0]

    // Default values for scale and offsets
    private double scaleFactor = 2.1;
    private double metersPerBlock = scaleFactor;

    private double offsetX = 0.0;
    private double offsetY = 0.0;
    private double offsetZ = 0.0;
    private double scaleX = scaleFactor;
    private double scaleY = scaleFactor;
    private double scaleZ = scaleFactor;

    private static final String SESSION_DIR = "./session";
    private static final long CLEANUP_INTERVAL = TimeUnit.HOURS.toMillis(1); // 1 hour

    // Removed or commented out debug prints; replaced with [PERF] timers where needed

    public VoxelChunkGenerator() {
        long start = System.currentTimeMillis();
        // System.out.println("[DEBUG] VoxelChunkGenerator initialized");
        // System.out.println("[DEBUG] LAT_ORIGIN: " + LAT_ORIGIN + ", LNG_ORIGIN: " + LNG_ORIGIN);
        // System.out.println("[DEBUG] Default scaleFactor (metersPerBlock): " + scaleFactor);
        // System.out.println("[DEBUG] Using a tile radius of 25 for single tile loading.");

        tileDownloader = new TileDownloader(API_KEY, LNG_ORIGIN, LAT_ORIGIN, 25);
        initializeSessionDirectory();
        scheduleSessionCleanup();
        loadMaterialColors();
        long end = System.currentTimeMillis();
        // System.out.println("[PERF] VoxelChunkGenerator constructor took " + (end - start) + " ms");
    }

    public void resetOriginForVisit(UUID playerUUID) {
        playerOrigins.remove(playerUUID);
        tileDownloader.setOrigin(null);
    }

    private void initializeSessionDirectory() {
        File sessionDir = new File(SESSION_DIR);
        if (!sessionDir.exists()) {
            sessionDir.mkdirs();
        }
        // debug removed
    }

    private void scheduleSessionCleanup() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(
                Bukkit.getPluginManager().getPlugin("VoxelEarth"),
                this::clearSessionDirectory,
                CLEANUP_INTERVAL, CLEANUP_INTERVAL
        );
    }

    private void clearSessionDirectory() {
        // debug removed
        try {
            Files.walk(Paths.get(SESSION_DIR))
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                            // debug removed
                        } catch (IOException e) {
                            // debug removed
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void regenChunks(World world,
                            double scaleX, double scaleY, double scaleZ,
                            double newOffsetX, double newOffsetY, double newOffsetZ) {

        long start = System.currentTimeMillis();
        indexedBlocks = new ConcurrentHashMap<>();
        loadMaterialColors(); // Might want to avoid reloading each time if not needed

        this.offsetX = newOffsetX;
        this.offsetY = newOffsetY;
        this.offsetZ = newOffsetZ;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.scaleZ = scaleZ;

        List<Chunk> chunksToProcess = new ArrayList<>();

        // We remove the debug prints
        for (int chunkX = -20; chunkX <= 20; chunkX++) {
            for (int chunkZ = -20; chunkZ <= 20; chunkZ++) {
                Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                if (!chunk.isLoaded()) chunk.load();
                chunksToProcess.add(chunk);
            }
        }

        try {
            long startDownload = System.currentTimeMillis();
            downloadAndProcessTiles(0, 0);
            long endDownload = System.currentTimeMillis();
            // System.out.println("[PERF] downloadAndProcessTiles took " + (endDownload - startDownload) + " ms");
        } catch (Exception e) {
            e.printStackTrace();
        }

        processChunksInBatches(chunksToProcess, world);

        long end = System.currentTimeMillis();
        // System.out.println("[PERF] regenChunks total took " + (end - start) + " ms");
    }

    private void processChunksInBatches(List<Chunk> chunks, World world) {
        long start = System.currentTimeMillis();
        AtomicInteger currentIndex = new AtomicInteger(0);

        Bukkit.getScheduler().runTaskTimer(Bukkit.getPluginManager().getPlugin("VoxelEarth"), task -> {
            if (currentIndex.get() >= chunks.size()) {
                task.cancel();
                long end = System.currentTimeMillis();
                // System.out.println("[PERF] processChunksInBatches total took " + (end - start) + " ms");
                return;
            }

            Chunk chunk = chunks.get(currentIndex.getAndIncrement());

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < world.getMaxHeight(); y++) {
                    for (int z = 0; z < 16; z++) {
                        Block block = chunk.getBlock(x, y, z);

                        if (block.getType() != Material.AIR) {
                            BlockChanger.setSectionBlockAsynchronously(block.getLocation(), new ItemStack(Material.AIR), false);
                        }

                        placeNewBlock(chunk, x, y, z, world);
                    }
                }
            }
        }, 0L, 2L);
    }

    private void placeNewBlock(Chunk chunk, int x, int y, int z, World world) {
        String blockKey = x + "," + y + "," + z;

        for (ConcurrentHashMap.Entry<String, Map<String, Object>> tileEntry : indexedBlocks.entrySet()) {
            Map<String, Object> indexMap = tileEntry.getValue();
            if (indexMap == null || (boolean) indexMap.getOrDefault("isPlaced", false)) continue;

            Map<String, Material> blockMap = (Map<String, Material>) indexMap.get("blocks");
            Material material = blockMap.get(blockKey);

            if (material != null) {
                BlockChanger.setSectionBlockAsynchronously(chunk.getBlock(x, y, z).getLocation(), new ItemStack(material), false);
                indexMap.put("isPlaced", true);
            }
        }
    }

    public void loadMaterialColors() {
        // Optionally measure time for loading
        long start = System.currentTimeMillis();
        InputStream is = null;
        try {
            is = getClass().getResourceAsStream("/vanilla.atlas");
            if (is == null) {
                return;
            }
            JSONObject atlasJson = new JSONObject(new JSONTokener(is));
            JSONArray blocksArray = atlasJson.getJSONArray("blocks");

            for (int i = 0; i < blocksArray.length(); i++) {
                JSONObject blockObject = blocksArray.getJSONObject(i);
                String blockName = blockObject.getString("name");

                Material material = getMaterial(blockName);

                JSONObject colourObject = blockObject.getJSONObject("colour");
                int r = (int) (colourObject.getDouble("r") * 255);
                int g = (int) (colourObject.getDouble("g") * 255);
                int b = (int) (colourObject.getDouble("b") * 255);

                MATERIAL_COLORS.add(new MaterialColor(material, new Color(r, g, b)));
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
        long end = System.currentTimeMillis();
        // System.out.println("[PERF] loadMaterialColors took " + (end - start) + " ms");
    }

    private Material getMaterial(String blockName) {
        String formattedName = blockName.replace("minecraft:", "").toUpperCase()
                .replace("_", " ").replace(" ", "_");

        Material material = Material.matchMaterial(formattedName);
        if (material == null) {
            if (formattedName.contains("LOG_HORIZONTAL")) {
                material = Material.matchMaterial(formattedName.replace("_HORIZONTAL", ""));
            } else if (formattedName.matches("FROSTED_ICE_\\d")) {
                material = Material.ICE;
            } else {
                material = Material.STONE;
            }
        }
        return material;
    }

    private void downloadAndProcessTiles(int chunkX, int chunkZ) {
        // Timed
        long start = System.currentTimeMillis();
        try {
            String outputDirectory = SESSION_DIR;
            tileDownloader.setCoordinates(LNG_ORIGIN, LAT_ORIGIN);

            List<String> downloadedTileFiles = tileDownloader.downloadTiles(outputDirectory);

            if (!downloadedTileFiles.isEmpty()) {
                // runGpuVoxelizer(outputDirectory, downloadedTileFiles);
                // loadIndexedJson(new File(outputDirectory), downloadedTileFiles, chunkX, chunkZ);
                runGpuVoxelizerBatch(outputDirectory, downloadedTileFiles);
                loadIndexedVxch(new File(outputDirectory), downloadedTileFiles, chunkX, chunkZ);
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        long end = System.currentTimeMillis();
        // System.out.println("[PERF] downloadAndProcessTiles took " + (end - start) + " ms");
    }

    private void runGpuVoxelizer(String directory, List<String> tileFiles) throws IOException, InterruptedException {
        long start = System.currentTimeMillis();
        int numThreads = Runtime.getRuntime().availableProcessors() * 3;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        List<Future<?>> futures = new ArrayList<>();
        for (String tileFileName : tileFiles) {
            Future<?> future = executor.submit(() -> {
                try {
                    processVoxelizerFile(directory, tileFileName);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            futures.add(future);
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        executor.shutdown();
        long end = System.currentTimeMillis();
        // System.out.println("[PERF] runGpuVoxelizer took " + (end - start) + " ms");
    }

    private void processVoxelizerFile(String directory, String tileFileName) throws IOException, InterruptedException {
        long start = System.currentTimeMillis();
        File file = new File(directory, tileFileName);
        String baseName = file.getName();
        String outputJson = baseName + "_128.json";
        File outputFile = new File(directory, outputJson);

        if (outputFile.exists()) {
            return; // skip
        }

        ProcessBuilder processBuilder = new ProcessBuilder(
                "./cuda_voxelizer",
                "-f", file.getAbsolutePath(),
                // "-o", "json",
                "-o", "vxch",
                "-s", "128",
                "-3dtiles",
                "-output", directory
        );

        processBuilder.directory(new File(System.getProperty("user.dir")));
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Voxelizer process failed with exit code " + exitCode);
        }

        long end = System.currentTimeMillis();
        // System.out.println("[PERF] processVoxelizerFile(" + tileFileName + ") took " + (end - start) + " ms");
    }

    private static final double MAX_COLOR_DISTANCE = 30.0;
    private Map<Integer, Material> colorIndexToMaterialCache = new HashMap<>();

    private Material mapRgbaToMaterial(JSONArray rgbaArray) {
        int r = rgbaArray.getInt(0);
        int g = rgbaArray.getInt(1);
        int b = rgbaArray.getInt(2);

        Color voxelColor = new Color(r, g, b);
        double[] voxelLab = ColorUtils.rgbToLab(voxelColor);

        MaterialColor bestMatch = null;
        double minDeltaE = Double.MAX_VALUE;

        for (MaterialColor mc : MATERIAL_COLORS) {
            Color materialColor = mc.getColor();
            double[] materialLab = ColorUtils.rgbToLab(materialColor);
            double deltaE = ColorUtils.deltaE(voxelLab, materialLab);

            if (deltaE < minDeltaE) {
                minDeltaE = deltaE;
                bestMatch = mc;
            }
        }

        if (bestMatch != null && minDeltaE <= 10.0) {
            return bestMatch.getMaterial();
        } else {
            return Material.STONE;
        }
    }

    private void loadIndexedJson(File directory, List<String> tileFiles, int chunkX, int chunkZ) throws IOException {
        long start = System.currentTimeMillis();
        int numThreads = Runtime.getRuntime().availableProcessors() * 3;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        List<Future<?>> futures = new ArrayList<>();

        for (String tileFileName : tileFiles) {
            String baseName = tileFileName;
            File jsonFile = new File(directory, baseName + "_128.json");

            if (indexedBlocks.containsKey(baseName)) {
                continue;
            }

            Future<?> future = executor.submit(() -> {
                try {
                    processJsonFile(jsonFile, baseName, chunkX, chunkZ);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            futures.add(future);
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        executor.shutdown();
        long end = System.currentTimeMillis();
        // System.out.println("[PERF] loadIndexedJson took " + (end - start) + " ms");
    }

    private void processJsonFile(File jsonFile, String baseName, int chunkX, int chunkZ) throws IOException {
        long start = System.currentTimeMillis();
        try (FileReader reader = new FileReader(jsonFile)) {
            JSONObject json = new JSONObject(new JSONTokener(reader));

            // File positionFile = new File(jsonFile.getParent(), baseName.replaceFirst("\\.glb.*$", "") + "_position.json");
            // double[] tileTranslation = new double[3];
            // if (positionFile.exists()) {
            //     try (FileReader posReader = new FileReader(positionFile)) {
            //         JSONArray positionArray = new JSONArray(new JSONTokener(posReader));
            //         if (positionArray.length() > 0) {
            //             JSONObject positionData = positionArray.getJSONObject(0);
            //             JSONArray translationArray = positionData.getJSONArray("translation");

            //             double rawX = translationArray.getDouble(0);
            //             double rawY = translationArray.getDouble(1);
            //             double rawZ = translationArray.getDouble(2);

            //             tileTranslation[0] = (rawX * scaleX) + offsetX + (chunkX * 16);
            //             tileTranslation[1] = (rawY * scaleY) + offsetY;
            //             tileTranslation[2] = (rawZ * scaleZ) + offsetZ + (chunkZ * 16);
            //         }
            //     }
            // }

            Map<String, double[]> tileTranslations = tileDownloader.getTileTranslations();
            double[] tileTranslation = new double[3];
            if (tileTranslations != null) {
                double[] translation = tileTranslations.get(baseName);
                if (translation != null) {
                    double rawX = translation[0];
                    double rawY = translation[1];
                    double rawZ = translation[2];

                    tileTranslation[0] = (rawX * scaleX) + offsetX + (chunkX * 16);
                    tileTranslation[1] = (rawY * scaleY) + offsetY;
                    tileTranslation[2] = (rawZ * scaleZ) + offsetZ + (chunkZ * 16);
                }
            }

            if (!json.has("blocks") || !json.has("xyzi")) {
                return;
            }

            JSONObject blocksObject = json.getJSONObject("blocks");
            JSONArray xyziArray = json.getJSONArray("xyzi");
            Map<String, Material> blockMap = new HashMap<>();

            Map<Integer, Material> colorIndexToMaterial = new HashMap<>();
            Iterator<String> keys = blocksObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                int colorIndex = Integer.parseInt(key);
                JSONArray rgbaArray = blocksObject.getJSONArray(key);

                Material material = mapRgbaToMaterial(rgbaArray);
                colorIndexToMaterial.put(colorIndex, material);
            }

            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

            for (int i = 0; i < xyziArray.length(); i++) {
                JSONArray xyziEntry = xyziArray.getJSONArray(i);
                int x = xyziEntry.getInt(0);
                int y = xyziEntry.getInt(1);
                int z = xyziEntry.getInt(2);
                int colorIndex = xyziEntry.getInt(3);

                int translatedX = (int) ((x + tileTranslation[0]));
                int translatedY = (int) ((y + tileTranslation[1]));
                int translatedZ = (int) ((z + tileTranslation[2]));

                String blockName = translatedX + "," + translatedY + "," + translatedZ;
                Material material = colorIndexToMaterial.get(colorIndex);

                if (material != null) {
                    blockMap.put(blockName, material);

                    if (translatedX < minX) minX = translatedX;
                    if (translatedY < minY) minY = translatedY;
                    if (translatedZ < minZ) minZ = translatedZ;
                    if (translatedX > maxX) maxX = translatedX;
                    if (translatedY > maxY) maxY = translatedY;
                    if (translatedZ > maxZ) maxZ = translatedZ;
                }
            }

            HashMap<String, Object> indexMap = new HashMap<>();
            indexMap.put("isPlaced", false);
            indexMap.put("blocks", blockMap);

            indexedBlocks.putIfAbsent(baseName, indexMap);
        }
        long end = System.currentTimeMillis();
        // System.out.println("[PERF] processJsonFile(" + jsonFile.getName() + ") took " + (end - start) + " ms");
    }

    public void loadJson(String filename, double scaleX, double scaleY, double scaleZ, double offsetX, double offsetY, double offsetZ) throws IOException {
        long start = System.currentTimeMillis();

        File file = new File(filename);
        if (!file.exists()) {
            return;
        }
        indexedBlocks = new ConcurrentHashMap<>();

        String baseName = file.getName().replace(".json", "");
        try (FileReader reader = new FileReader(file)) {
            JSONObject json = new JSONObject(new JSONTokener(reader));

            if (!json.has("blocks") || !json.has("xyzi")) {
                return;
            }

            JSONObject blocksObject = json.getJSONObject("blocks");
            JSONArray xyziArray = json.getJSONArray("xyzi");
            Map<String, Material> blockMap = new HashMap<>();
            Map<Integer, Material> colorIndexToMaterial = new HashMap<>();

            Iterator<String> keys = blocksObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                int colorIndex = Integer.parseInt(key);
                JSONArray rgbaArray = blocksObject.getJSONArray(key);

                Material material = mapRgbaToMaterial(rgbaArray);
                colorIndexToMaterial.put(colorIndex, material);
            }

            for (int i = 0; i < xyziArray.length(); i++) {
                JSONArray xyziEntry = xyziArray.getJSONArray(i);
                int x = xyziEntry.getInt(0);
                int y = xyziEntry.getInt(1);
                int z = xyziEntry.getInt(2);
                int colorIndex = xyziEntry.getInt(3);

                int translatedX = (int) ((x * scaleX + offsetX));
                int translatedY = (int) ((y * scaleY + offsetY));
                int translatedZ = (int) ((z * scaleZ + offsetZ));

                String blockName = translatedX + "," + translatedY + "," + translatedZ;
                Material material = colorIndexToMaterial.get(colorIndex);

                if (material != null) {
                    blockMap.put(blockName, material);
                }
            }

            HashMap<String, Object> indexMap = new HashMap<>();
            indexMap.put("isPlaced", false);
            indexMap.put("blocks", blockMap);

            indexedBlocks.put(baseName, indexMap);

            Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("VoxelEarth"), () -> {
                for (ConcurrentHashMap.Entry<String, Map<String, Object>> tileEntry : indexedBlocks.entrySet()) {
                    Map<String, Object> indexMap1 = tileEntry.getValue();

                    if (indexMap1 != null && !(boolean) indexMap1.get("isPlaced")) {
                        indexMap1.put("isPlaced", true);
                    } else {
                        continue;
                    }

                    Map<String, Material> blockMap1 = (Map<String, Material>) indexMap1.get("blocks");
                    for (Map.Entry<String, Material> blockEntry : blockMap1.entrySet()) {
                        String[] parts = blockEntry.getKey().split(",");
                        int originalX = Integer.parseInt(parts[0]);
                        int originalY = Integer.parseInt(parts[1]);
                        int originalZ = Integer.parseInt(parts[2]);

                        int newX = originalX;
                        int newY = originalY;
                        int newZ = originalZ;

                        int blockChunkX = newX >> 4;
                        int blockChunkZ = newZ >> 4;

                        World world = Bukkit.getWorld("world");
                        if (world == null) {
                            continue;
                        }

                        Chunk chunk = world.getChunkAt(blockChunkX, blockChunkZ);
                        if (!chunk.isLoaded()) {
                            chunk.load();
                        }

                        int localX = newX & 15;
                        int localZ = newZ & 15;

                        BlockChanger.setSectionBlockAsynchronously(
                                chunk.getBlock(localX, newY, localZ).getLocation(),
                                new ItemStack(blockEntry.getValue()),
                                false
                        );
                    }
                }
            });
        }
        long end = System.currentTimeMillis();
        // System.out.println("[PERF] loadJson() total took " + (end - start) + " ms");
    }

    @Override
    public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        // Not generating anything directly here
    }

    public void loadChunk(UUID playerUUID, int tileX, int tileZ, boolean isVisit, Consumer<int[]> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("VoxelEarth"), () -> {
            long start = System.currentTimeMillis();
            try {
                // File originTranslationFile = new File("origin_translation.json");
                // if (originTranslationFile.exists()) {
                //     try (FileReader reader = new FileReader(originTranslationFile)) {
                //         JSONArray originArray = new JSONArray(new JSONTokener(reader));
                //         originEcef = new double[3];
                //         originEcef[0] = originArray.getDouble(0);
                //         originEcef[1] = -originArray.getDouble(2);
                //         originEcef[2] = originArray.getDouble(1);
                //     }
                // }

                // Get origin from TileDownloadear and set to originEcef
                double[] origin = tileDownloader.getOrigin();
                if (origin != null) {
                    originEcef = new double[3];
                    originEcef[0] = origin[0];
                    originEcef[1] = -origin[2];
                    originEcef[2] = origin[1];
                }

                // double[] playerOrigin = playerOrigins.get(playerUUID);
                // if (playerOrigin != null) {
                //     tileDownloader.setOrigin(playerOrigin);
                // } else {
                //     tileDownloader.setOrigin(null);
                // }

                int[] blockLocation = new int[]{210, 70, 0};

                String outputDirectory = SESSION_DIR;

                double[] latLng = minecraftToLatLng(tileX, tileZ);

                tileDownloader.setCoordinates(latLng[1], latLng[0]);
                tileDownloader.setRadius(25);

                List<String> downloadedTileFiles = tileDownloader.downloadTiles(outputDirectory);
                if (downloadedTileFiles.isEmpty()) {
                    callback.accept(blockLocation);
                    return;
                }

                // runGpuVoxelizer(outputDirectory, downloadedTileFiles);
                runGpuVoxelizerBatch(outputDirectory, downloadedTileFiles);

                Set<String> previousKeys = new HashSet<>(indexedBlocks.keySet());

                int adjustedTileX = tileX;
                int adjustedTileZ = tileZ;

                if (isVisit) {
                    playerXOffsets.put(playerUUID, tileX);
                    playerZOffsets.put(playerUUID, tileZ);
                } else {
                    Integer storedXOffset = playerXOffsets.get(playerUUID);
                    Integer storedZOffset = playerZOffsets.get(playerUUID);
                    if (storedXOffset != null) adjustedTileX = storedXOffset;
                    if (storedZOffset != null) adjustedTileZ = storedZOffset;
                }

                // loadIndexedJson(new File(outputDirectory), downloadedTileFiles, adjustedTileX, adjustedTileZ);
                loadIndexedVxch(new File(outputDirectory), downloadedTileFiles, adjustedTileX, adjustedTileZ);
                Set<String> currentKeys = new HashSet<>(indexedBlocks.keySet());
                currentKeys.removeAll(previousKeys);

                String initialTileKey = null;
                if (!currentKeys.isEmpty()) {
                    initialTileKey = currentKeys.iterator().next();
                } else if (!indexedBlocks.isEmpty()) {
                    initialTileKey = indexedBlocks.keySet().iterator().next();
                } else {
                    callback.accept(blockLocation);
                    return;
                }

                AtomicInteger yOffset = new AtomicInteger(0);
                final int desiredY = 70;

                World world = Bukkit.getWorld("world");
                if (world == null) {
                    callback.accept(blockLocation);
                    return;
                }

                Map<String, Object> indexMap1 = indexedBlocks.get(initialTileKey);
                if (indexMap1 != null) {
                    Map<String, Material> blockMap1 = (Map<String, Material>) indexMap1.get("blocks");

                    int minYInTile = blockMap1.keySet().stream()
                            .mapToInt(key -> Integer.parseInt(key.split(",")[1]))
                            .min()
                            .orElse(0);

                    yOffset.set(desiredY - minYInTile);

                    if (isVisit) {
                        // String positionFileName = initialTileKey.replaceFirst("\\.glb.*$", "") + "_position.json";
                        // File positionFile = new File(outputDirectory, positionFileName);
                        // if (positionFile.exists()) {
                        //     try (FileReader posReader = new FileReader(positionFile)) {
                        //         JSONArray positionArray = new JSONArray(new JSONTokener(posReader));
                        //         if (positionArray.length() > 0) {
                        //             JSONObject positionData = positionArray.getJSONObject(0);
                        //             JSONArray originArray = positionData.getJSONArray("origin");
                        //             double[] origin = new double[3];
                        //             origin[0] = originArray.getDouble(0);
                        //             origin[1] = originArray.getDouble(1);
                        //             origin[2] = originArray.getDouble(2);
                        //             playerOrigins.put(playerUUID, origin);
                        //         }
                        //     } catch (Exception e) {
                        //         e.printStackTrace();
                        //     }
                        // }
                        Map<String, double[]> tileTranslations = tileDownloader.getTileTranslations();
                        if (tileTranslations != null) {
                            double[] translation = tileTranslations.get(initialTileKey);
                            if (translation != null) {
                                playerOrigins.put(playerUUID, new double[]{translation[0], translation[2], translation[1]});
                            }
                        }

                        playerYOffsets.put(playerUUID, yOffset.get());
                    } else {
                        Integer storedYOffset = playerYOffsets.get(playerUUID);
                        if (storedYOffset != null) {
                            yOffset.set(storedYOffset);
                        }
                    }

                    if (!(boolean) indexMap1.get("isPlaced")) {
                        placeBlocks(world, blockMap1, yOffset.get());
                        indexMap1.put("isPlaced", true);
                    }

                    if (!blockMap1.isEmpty()) {
                        String firstBlock = blockMap1.keySet().iterator().next();
                        String[] coords = firstBlock.split(",");
                        blockLocation[0] = Integer.parseInt(coords[0]);
                        blockLocation[1] = Integer.parseInt(coords[1]) + yOffset.get();
                        blockLocation[2] = Integer.parseInt(coords[2]);
                    }
                }

                callback.accept(blockLocation);

                final String finalInitialTileKey = initialTileKey;
                indexedBlocks.forEach((tileKey, indexMap) -> {
                    if (!tileKey.equals(finalInitialTileKey) && indexMap != null && !(boolean) indexMap.get("isPlaced")) {
                        Map<String, Material> blockMap = (Map<String, Material>) indexMap.get("blocks");
                        placeBlocks(world, blockMap, yOffset.get());
                        indexMap.put("isPlaced", true);
                    }
                });

                // callback.accept(blockLocation);
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                int[] blockLocation = new int[]{210, 70, 0};
                callback.accept(blockLocation);
            }
            long end = System.currentTimeMillis();
            // System.out.println("[PERF] loadChunk() total took " + (end - start) + " ms");
        });
    }

    // private void placeBlocks(World world, Map<String, Material> blockMap, int yOffset) {
    //     long start = System.currentTimeMillis();
    //     Set<Chunk> modifiedChunks = ConcurrentHashMap.newKeySet();

    //     for (Map.Entry<String, Material> blockEntry : blockMap.entrySet()) {
    //         String[] parts = blockEntry.getKey().split(",");
    //         int originalX = Integer.parseInt(parts[0]);
    //         int originalY = Integer.parseInt(parts[1]);
    //         int originalZ = Integer.parseInt(parts[2]);

    //         int newX = originalX;
    //         int newY = originalY + yOffset;
    //         int newZ = originalZ;

    //         int blockChunkX = newX >> 4;
    //         int blockChunkZ = newZ >> 4;

    //         if (world == null) {
    //             continue;
    //         }

    //         if (newY < world.getMinHeight() || newY >= world.getMaxHeight()) {
    //             continue;
    //         }

    //         Chunk chunk = world.getChunkAt(blockChunkX, blockChunkZ);
    //         if (!chunk.isLoaded()) {
    //             chunk.load();
    //         }

    //         int localX = newX & 15;
    //         int localZ = newZ & 15;

    //         Material material = blockEntry.getValue();
    //         if (material == null || !material.isBlock()) {
    //             throw new IllegalArgumentException("Invalid block material: " + material);
    //         }

    //         ItemStack itemStack = new ItemStack(material);
    //         BlockChanger.setSectionBlockAsynchronously(
    //             chunk.getBlock(localX, newY, localZ).getLocation(),
    //             itemStack,
    //             false
    //         );

    //         Block pos = world.getBlockAt(newX, newY, newZ);
    //         Chunk realChunk = pos.getChunk();
    //         modifiedChunks.add(realChunk);
    //     }

    //     updateLighting(world, modifiedChunks);
    //     long end = System.currentTimeMillis();
    //     System.out.println("[PERF] placeBlocks() took " + (end - start) + " ms for " + blockMap.size() + " blocks");
    // }

    // private void updateLighting(World world, Set<Chunk> modifiedChunks) {
    //     long start = System.currentTimeMillis();
    //     for (Chunk chunk : modifiedChunks) {
    //         int chunkX = chunk.getX();
    //         int chunkZ = chunk.getZ();
    //         world.refreshChunk(chunkX, chunkZ);
    //     }
    //     long end = System.currentTimeMillis();
    //     System.out.println("[PERF] updateLighting() took " + (end - start) + " ms for " + modifiedChunks.size() + " chunks");
    // }

    private void placeBlocks(World world, Map<String, Material> blockMap, int yOffset) {
        long start = System.currentTimeMillis();
        
        // Group locations by block material.
        Map<Material, List<Location>> groups = new HashMap<>();
        
        // Also compute the overall bounding box (in block coordinates)
        int globalMinX = Integer.MAX_VALUE;
        int globalMaxX = Integer.MIN_VALUE;
        int globalMinZ = Integer.MAX_VALUE;
        int globalMaxZ = Integer.MIN_VALUE;
        
        // Process each coordinate key (formatted as "x,y,z")
        for (Map.Entry<String, Material> entry : blockMap.entrySet()) {
            String key = entry.getKey();
            Material material = entry.getValue();
        
            // Split the key into x,y,z values and apply the Y offset.
            String[] parts = key.split(",");
            int originalX = Integer.parseInt(parts[0]);
            int originalY = Integer.parseInt(parts[1]);
            int originalZ = Integer.parseInt(parts[2]);
        
            int newX = originalX;
            int newY = originalY + yOffset;
            int newZ = originalZ;
        
            // Validate world and height limits.
            if (world == null || newY < world.getMinHeight() || newY >= world.getMaxHeight()) {
                continue;
            }
        
            // Update the bounding box.
            if (newX < globalMinX) { globalMinX = newX; }
            if (newX > globalMaxX) { globalMaxX = newX; }
            if (newZ < globalMinZ) { globalMinZ = newZ; }
            if (newZ > globalMaxZ) { globalMaxZ = newZ; }
        
            // Create the target location.
            Location loc = new Location(world, newX, newY, newZ);
        
            // Group locations by their material.
            groups.computeIfAbsent(material, m -> new ArrayList<>()).add(loc);
        }
        
        // Place blocks asynchronously per material group.
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map.Entry<Material, List<Location>> groupEntry : groups.entrySet()) {
            Material material = groupEntry.getKey();
            List<Location> locations = groupEntry.getValue();
        
            if (material == null || !material.isBlock()) {
                throw new IllegalArgumentException("Invalid block material: " + material);
            }
        
            // Prepare an ItemStack for this material.
            ItemStack itemStack = new ItemStack(material);
        
            // Call the batch asynchronous update.
            CompletableFuture<Void> future = BlockChanger.setSectionBlocksAsynchronously(world, locations, itemStack);
            futures.add(future);
        }
        
        // Wait until all asynchronous placements complete.
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // Calculate the chunk coordinates overlapping the bounding box.
        // Each chunk is 16x16 blocks.
        int chunkMinX = globalMinX >> 4;
        int chunkMaxX = globalMaxX >> 4;
        int chunkMinZ = globalMinZ >> 4;
        int chunkMaxZ = globalMaxZ >> 4;
        
        // Refresh every chunk that overlaps the placement area.
        for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
            for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
                // Ensure the chunk is loaded.
                Chunk chunk = world.getChunkAt(cx, cz);
                if (!chunk.isLoaded()) {
                    chunk.load();
                }
                // Refresh the chunk to force a lighting update.
                world.refreshChunk(cx, cz);
            }
        }
        
        long end = System.currentTimeMillis();
        // Optionally log performance.
        // System.out.println("[PERF] placeBlocks() took " + (end - start) + " ms for " + blockMap.size() + " blocks");
    }
    
    
    // Helper method to update lighting for modified chunks
    private void updateLighting(World world, Set<Chunk> chunks) {
        for (Chunk chunk : chunks) {
            world.refreshChunk(chunk.getX(), chunk.getZ());
        }
    }

    private static final double EARTH_RADIUS = 6378137.0;
    private static final int CHUNK_SIZE = 16;

    int oldOffsetXX = 7677201 - 7677296; // -95
    int oldOffsetZZ = -11936601 - (-11937070); // 468

    int newOffsetXX = oldOffsetXX * 5;
    int newOffsetZZ = oldOffsetZZ * 5;

    public double getPlayerLatitude(UUID playerUUID) {
        Player player = Bukkit.getPlayer(playerUUID);
        if (player == null) {
            return 0.0;
        }
        double x = player.getLocation().getX();
        double z = player.getLocation().getZ();
        double[] latLng = minecraftToLatLng((int) x, (int) z);
        return latLng[0];
    }

    public double getPlayerLongitude(UUID playerUUID) {
        Player player = Bukkit.getPlayer(playerUUID);
        if (player == null) {
            return 0.0;
        }
        double x = player.getLocation().getX();
        double z = player.getLocation().getZ();
        double[] latLng = minecraftToLatLng((int) x, (int) z);
        return latLng[1];
    }

    public void updateOffsetsForLocation(UUID playerUUID) {
        double latitude = getPlayerLatitude(playerUUID);
        double longitude = getPlayerLongitude(playerUUID);

        int signAdjustmentX = (longitude >= 0) ? 1 : -1;
        int signAdjustmentZ = (latitude >= 0) ? 1 : -1;

        newOffsetXX = (int) (oldOffsetXX * signAdjustmentX);
        newOffsetZZ = (int) (oldOffsetZZ * signAdjustmentZ);
    }

    public double[] minecraftToLatLng(int chunkX, int chunkZ) {
        double metersPerChunk = CHUNK_SIZE * (metersPerBlock / 2.625);
        double metersZ = (chunkX * metersPerChunk + newOffsetXX);
        double metersX = ((chunkZ * metersPerChunk + newOffsetZZ));

        metersX = -metersX;
        metersZ = -metersZ;

        double lng = (metersX / EARTH_RADIUS) * (180 / Math.PI);
        double lat = (2 * Math.atan(Math.exp(metersZ / EARTH_RADIUS)) - Math.PI / 2) * (180 / Math.PI);

        return new double[]{lat, lng};
    }

    public int[] latLngToMinecraft(double lat, double lng) {
        double[] meters = latLngToMeters(lat, lng);
        double metersPerChunk = CHUNK_SIZE * (metersPerBlock / 2.625);

        double metersZ = meters[0];
        double metersX = meters[1];

        metersX = -metersX;
        metersZ = -metersZ;

        int chunkX = (int)((metersX - newOffsetXX) / metersPerChunk);
        int chunkZ = (int)((metersZ - newOffsetZZ) / metersPerChunk);

        return new int[]{chunkX, chunkZ};
    }

    private double[] latLngToMeters(double lat, double lng) {
        double x = lng * (Math.PI / 180) * EARTH_RADIUS;
        double z = Math.log(Math.tan((Math.PI / 4) + Math.toRadians(lat) / 2)) * EARTH_RADIUS;
        return new double[]{x, z};
    }

    private double sinLat0, cosLat0, sinLon0, cosLon0;

    private static final double a = 6378137.0; 
    private static final double f = 1 / 298.257223563; 
    private static final double b = a * (1 - f); 
    private static final double eSq = (a * a - b * b) / (a * a); 
    private static final double eSqPrime = (a * a - b * b) / (b * b); 

    public int[] latLngToBlock(double lat, double lng) {
        if(originEcef == null) {
            System.out.println("[DEBUG] Origin ECEF not set.");
            return new int[]{0, 0};
        }

        double lat0Rad = Math.toRadians(LAT_ORIGIN);
        double lon0Rad = Math.toRadians(LNG_ORIGIN);
        sinLat0 = Math.sin(lat0Rad);
        cosLat0 = Math.cos(lat0Rad);
        sinLon0 = Math.sin(lon0Rad);
        cosLon0 = Math.cos(lon0Rad);

        double[] ecef = latLngToEcef(lat, lng);
        double[] enu = ecefToEnu(ecef[0], ecef[1], ecef[2]);

        int x = (int) enu[0];
        int z = (int) enu[1];

        return new int[]{x, z};
    }

    private double[] ecefToLatLng(double x, double y, double z) {
        double longitude = Math.atan2(y, x);

        double p = Math.sqrt(x * x + y * y);
        double theta = Math.atan2(z * a, p * b);

        double sinTheta = Math.sin(theta);
        double cosTheta = Math.cos(theta);

        double latitude = Math.atan2(
            z + eSqPrime * b * sinTheta * sinTheta * sinTheta,
            p - eSq * a * cosTheta * cosTheta * cosTheta
        );

        latitude = Math.toDegrees(latitude);
        longitude = Math.toDegrees(longitude);

        return new double[]{latitude, longitude};
    }

    private double[] latLngToEcef(double lat, double lon) {
        double latRad = Math.toRadians(lat);
        double lonRad = Math.toRadians(lon);

        double N = a / Math.sqrt(1 - eSq * Math.sin(latRad) * Math.sin(latRad));

        double x = N * Math.cos(latRad) * Math.cos(lonRad);
        double y = N * Math.cos(latRad) * Math.sin(lonRad);
        double z = (N * (1 - eSq)) * Math.sin(latRad);

        return new double[]{x, y, z};
    }

    private double[] ecefToEnu(double xEcef, double yEcef, double zEcef) {
        double sinLat0 = Math.sin(Math.toRadians(LAT_ORIGIN));
        double cosLat0 = Math.cos(Math.toRadians(LAT_ORIGIN));
        double sinLon0 = Math.sin(Math.toRadians(LNG_ORIGIN));
        double cosLon0 = Math.cos(Math.toRadians(LNG_ORIGIN));

        double dx = xEcef - originEcef[0];
        double dy = yEcef - originEcef[1];
        double dz = zEcef - originEcef[2];

        double east = -sinLon0 * dx + cosLon0 * dy;
        double north = -sinLat0 * cosLon0 * dx - sinLat0 * sinLon0 * dy + cosLat0 * dz;
        double up = cosLat0 * cosLon0 * dx + cosLat0 * sinLon0 * dy + sinLat0 * dz;

        return new double[]{east, north, up};
    }

    // ECEF/ENU omitted debug

    // Replace runGpuVoxelizer with batch version
    private void runGpuVoxelizerBatch(String directory, List<String> tileFiles) 
            throws IOException, InterruptedException {
        long start = System.currentTimeMillis();
        
        List<String> cmd = new ArrayList<>();
        cmd.add("./cuda_voxelizer");
        for (String tile : tileFiles) {
            cmd.add("-f");
            cmd.add(new File(directory, tile).getAbsolutePath());
        }
        cmd.add("-o");
        cmd.add("vxch");
        // cmd.add("json");
        cmd.add("-3dtiles");
        cmd.add("-s");
        cmd.add("128");
        cmd.add("-output");
        cmd.add(directory);

        // read out command
        System.out.println("Running voxelizer batch: " + cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(System.getProperty("user.dir")));
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        int exitCode = process.waitFor();
        
        if (exitCode != 0) {
            throw new RuntimeException("Voxelizer batch failed: exit=" + exitCode);
        }
        // System.out.println("[PERF] Batch voxelizer took " + (System.currentTimeMillis() - start) + "ms");
    }

    private static class ChunkRecord {
        long offset;
        int compressedSize;
        int uncompressedSize;
        short chunkType;
        short reserved;
    }

    private void loadIndexedVxch(File directory, List<String> tileFiles, int chunkX, int chunkZ) throws IOException {
        long start = System.currentTimeMillis();
    
        // Strip .glb extensions from tile names
        for (int i = 0; i < tileFiles.size(); i++) {
            String tile = tileFiles.get(i);
            if (tile.endsWith(".glb")) {
                tileFiles.set(i, tile.substring(0, tile.length() - 4));
            }
        }
    
        int numThreads = Runtime.getRuntime().availableProcessors() * 3;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<?>> futures = new ArrayList<>();
    
        for (String tileFileName : tileFiles) {
            String vxchName = tileFileName + "_128.vxch";
            File vxchFile = new File(directory, vxchName);
    
            if (indexedBlocks.containsKey(tileFileName) || !vxchFile.exists()) continue;
    
            // Submit task to executor to process each tile file concurrently
            futures.add(executor.submit(() -> {
                try {
                    processVxchFile(vxchFile, tileFileName, chunkX, chunkZ);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }));
        }
    
        // Wait for all tasks to complete
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
    
        executor.shutdown();
        // System.out.println("[PERF] loadIndexedVxch took " + (System.currentTimeMillis() - start) + "ms");
    }
    
    private void processVxchFile(File vxchFile, String tileFileName, int chunkX, int chunkZ) throws IOException {
        byte[] fileData = Files.readAllBytes(vxchFile.toPath());
        ByteBuffer buf = ByteBuffer.wrap(fileData).order(ByteOrder.LITTLE_ENDIAN);
    
        // Parse header
        byte[] sig = new byte[4];
        buf.get(sig);
        if (!"VXCH".equals(new String(sig, StandardCharsets.US_ASCII))) {
            System.out.println("[ERROR] Invalid VXCH file: " + vxchFile);
            return;
        }
    
        // Read version (uint32) and other details
        int version = buf.getInt();
        if (version != 1) {
            System.out.println("[WARN] Unsupported VXCH version: " + version);
            return;
        }
    
        int chunkSize = buf.getShort() & 0xFFFF;
        int sizeX = buf.getShort() & 0xFFFF;
        int sizeY = buf.getShort() & 0xFFFF;
        int sizeZ = buf.getShort() & 0xFFFF;
        int chunkCountX = buf.getShort() & 0xFFFF;
        int chunkCountY = buf.getShort() & 0xFFFF;
        int chunkCountZ = buf.getShort() & 0xFFFF;
        int colorCount = buf.getInt();
    
        // Read color palette
        List<Color> palette = new ArrayList<>(colorCount + 1);
        palette.add(null); // Index 0 unused
        for (int i = 0; i < colorCount; i++) {
            int r = buf.get() & 0xFF;
            int g = buf.get() & 0xFF;
            int b = buf.get() & 0xFF;
            int a = buf.get() & 0xFF;
            palette.add(new Color(r, g, b, a));
        }
    
        // Read chunk table
        int totalChunks = chunkCountX * chunkCountY * chunkCountZ;
        List<ChunkRecord> chunkTable = new ArrayList<>(totalChunks);
        for (int i = 0; i < totalChunks; i++) {
            ChunkRecord rec = new ChunkRecord();
            rec.offset = buf.getLong();
            rec.compressedSize = buf.getInt();
            rec.uncompressedSize = buf.getInt();
            rec.chunkType = (short)(buf.getShort() & 0xFFFF);
            rec.reserved = (short)(buf.getShort() & 0xFFFF);
            chunkTable.add(rec);
        }
    
        // Translate tile using tileDownloader translation
        double[] tileTranslation = getTileTranslation(tileFileName + ".glb", chunkX, chunkZ);
    
        // Process chunks
        Map<String, Material> blockMap = processChunks(fileData, chunkTable,
            chunkSize, sizeX, sizeY, sizeZ, chunkCountX, chunkCountY, chunkCountZ, palette, tileTranslation);
    
        // Store in indexedBlocks
        Map<String, Object> indexMap = new HashMap<>();
        indexMap.put("isPlaced", false);
        indexMap.put("blocks", blockMap);
        indexedBlocks.put(tileFileName, indexMap);
    }
    
    private double[] getTileTranslation(String baseName, int chunkX, int chunkZ) {
        Map<String, double[]> tileTranslations = tileDownloader.getTileTranslations();
        double[] tileTranslation = new double[3];
        if (tileTranslations != null) {
            double[] translation = tileTranslations.get(baseName);
            if (translation != null) {
                double rawX = translation[0];
                double rawY = translation[1];
                double rawZ = translation[2];
    
                tileTranslation[0] = (rawX * scaleX) + offsetX + (chunkX * 16);
                tileTranslation[1] = (rawY * scaleY) + offsetY;
                tileTranslation[2] = (rawZ * scaleZ) + offsetZ + (chunkZ * 16);
            }
        }
        return tileTranslation;
    }
    
    private Map<String, Material> processChunks(byte[] fileData, List<ChunkRecord> chunkTable,
        int chunkSize, int sizeX, int sizeY, int sizeZ,
        int chunkCountX, int chunkCountY, int chunkCountZ,
        List<Color> palette, double[] tileTranslation) {
    
        Map<String, Material> blockMap = new HashMap<>();
        int chunkIdx = 0;
    
        for (int cz = 0; cz < chunkCountZ; cz++) {
            for (int cy = 0; cy < chunkCountY; cy++) {
                for (int cx = 0; cx < chunkCountX; cx++) {
                    ChunkRecord rec = chunkTable.get(chunkIdx++);
                    if (rec.chunkType == 0) continue;
    
                    if (rec.offset + rec.compressedSize > fileData.length) {
                        System.out.println("[ERROR] Invalid chunk offset/size");
                        continue;
                    }
    
                    byte[] compData = Arrays.copyOfRange(
                        fileData,
                        (int) rec.offset,
                        (int) rec.offset + rec.compressedSize
                    );
    
                    byte[] uncompressed = decompressChunkData(compData, rec.uncompressedSize);
                    if (uncompressed.length != rec.uncompressedSize) {
                        System.out.println("[WARN] Decompression size mismatch");
                        continue;
                    }
    
                    int x0 = cx * chunkSize;
                    int y0 = cy * chunkSize;
                    int z0 = cz * chunkSize;
                    int xMax = Math.min(x0 + chunkSize, sizeX);
                    int yMax = Math.min(y0 + chunkSize, sizeY);
                    int zMax = Math.min(z0 + chunkSize, sizeZ);
    
                    // if (rec.chunkType == 1) {
                    //     processUniformChunk(uncompressed, x0, y0, z0, xMax, yMax, zMax, palette, blockMap, tileTranslation);
                    // } else if (rec.chunkType == 2) {
                        processSparseChunk(uncompressed, x0, y0, z0,
                            xMax - x0, yMax - y0, zMax - z0, palette, blockMap, tileTranslation);
                    // }
                }
            }
        }
    
        return blockMap;
    }
    
    private void processUniformChunk(byte[] data,
    int x0, int y0, int z0,
    int xMax, int yMax, int zMax,
    List<Color> palette, Map<String, Material> blockMap, double[] tileTranslation) {

if (data.length < 2) return;

// Read color index as little-endian (matching C++ code on little-endian systems)
int colorIndex = ByteBuffer.wrap(data, 0, 2)
                           .order(ByteOrder.LITTLE_ENDIAN)
                           .getShort() & 0xFFFF;

if (colorIndex == 0 || colorIndex >= palette.size()) {
    System.out.println("[WARN] Invalid color index: " + colorIndex);
    return;
}

Material mat = getMaterialFromColor(palette.get(colorIndex));

for (int x = x0; x < xMax; x++) {
    for (int y = y0; y < yMax; y++) {
        for (int z = z0; z < zMax; z++) {
            int translatedX = (int) (x + tileTranslation[0]);
            int translatedY = (int) (y + tileTranslation[1]);
            int translatedZ = (int) (z + tileTranslation[2]);
            blockMap.put(translatedX + "," + translatedY + "," + translatedZ, mat);
        }
    }
}
}
    
    // Similar modifications for processSparseChunk...
    private void processSparseChunk(byte[] data,
        int x0, int y0, int z0,
        int dx, int dy, int dz,
        List<Color> palette, Map<String, Material> blockMap, double[] tileTranslation) {

    int totalCells = dx * dy * dz;
    int bitmaskBytes = (totalCells + 7) / 8;

    if (data.length < bitmaskBytes + 2) {
        System.out.println("[WARN] Sparse chunk data too small: expected at least " + (bitmaskBytes + 2) + ", got " + data.length);
        return;
    }

    ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

    byte[] bitmask = new byte[bitmaskBytes];
    buf.get(bitmask);

    // Read color indices as little-endian
    int colorCount = (data.length - bitmaskBytes) / 2;
    short[] colors = new short[colorCount];
    for (int i = 0; i < colorCount; i++) {
        colors[i] = buf.getShort();
    }

    int colorIdx = 0;
    for (int cell = 0; cell < totalCells; cell++) {
        int bytePos = cell / 8;
        int bitPos = cell % 8;
        if ((bitmask[bytePos] & (1 << bitPos)) == 0) continue;

        if (colorIdx >= colors.length) {
            System.out.println("[WARN] Color index out of bounds: " + colorIdx);
            break;
        }

        int colorIndex = colors[colorIdx++] & 0xFFFF;
        if (colorIndex == 0 || colorIndex >= palette.size()) {
            System.out.println("[WARN] Invalid color index: " + colorIndex);
            continue;
        }

        int lz = cell / (dx * dy);
        int rem = cell % (dx * dy);
        int ly = rem / dx;
        int lx = rem % dx;

        int translatedX = (int) (x0 + lx + tileTranslation[0]);
        int translatedY = (int) (y0 + ly + tileTranslation[1]);
        int translatedZ = (int) (z0 + lz + tileTranslation[2]);

        String key = translatedX + "," + translatedY + "," + translatedZ;
        blockMap.put(key, getMaterialFromColor(palette.get(colorIndex)));
    }
}

    private Material getMaterialFromColor(Color color) {
        JSONArray rgba = new JSONArray()
            .put(color.getRed())
            .put(color.getGreen())
            .put(color.getBlue())
            .put(color.getAlpha());
        return mapRgbaToMaterial(rgba);
    }

    private byte[] decompressChunkData(byte[] compData, int uncompressedSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(uncompressedSize);
        int pos = 0;
        
        try {
            while (pos < compData.length && out.size() < uncompressedSize) {
                int header = compData[pos++] & 0xFF;
                
                if (header <= 127) { // Literal run
                    int len = header + 1;
                    if (pos + len > compData.length) break;
                    out.write(compData, pos, len);
                    pos += len;
                } else { // RLE run
                    // Convert to signed byte equivalent
                    int signedHeader = (byte) header;
                    int runLength = 1 - signedHeader;
                    
                    if (pos >= compData.length) break;
                    byte val = compData[pos++];
                    for (int i = 0; i < runLength; i++) {
                        if (out.size() >= uncompressedSize) break;
                        out.write(val);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[ERROR] Decompression failed: " + e.getMessage());
        }
        
        if (out.size() != uncompressedSize) {
            System.out.println("[WARN] Decompression size mismatch: expected=" 
                + uncompressedSize + ", got=" + out.size());
        }
        
        return out.toByteArray();
    }

    @Override
    public boolean shouldGenerateNoise() { return false; }
    @Override
    public boolean shouldGenerateSurface() { return true; }
    @Override
    public boolean shouldGenerateBedrock() { return false; }
    @Override
    public boolean shouldGenerateCaves() { return false; }
    @Override
    public boolean shouldGenerateDecorations() { return false; }
    @Override
    public boolean shouldGenerateMobs() { return false; }
    @Override
    public boolean shouldGenerateStructures() { return false; }
}
