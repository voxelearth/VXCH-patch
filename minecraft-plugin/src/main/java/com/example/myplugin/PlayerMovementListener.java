package com.example.voxelearth;

import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.entity.Player;
import org.bukkit.Location;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerMovementListener implements Listener {

    private final VoxelEarth plugin;

    /**
     * We store each player's "origin chunk" coordinates,
     * chosen the first time we see them move (or after a /visit reset).
     */
    private final Map<UUID, Integer> originTileX = new HashMap<>();
    private final Map<UUID, Integer> originTileZ = new HashMap<>();

    /** 
     * We only load tiles if the player has moved more than this threshold
     * since last load to avoid spamming.
     */
    private static final double LOAD_THRESHOLD = 50.0;

    /** 
     * Last location where each player triggered a tile load.
     */
    private final Map<Player, Location> lastLoadedLocations = new HashMap<>();

    public PlayerMovementListener(VoxelEarth plugin) {
        this.plugin = plugin;
        plugin.getLogger().info("PlayerMovementListener has been created");
    }

    /**
     * Reset the player's origin so that next time they move,
     * we pick a fresh origin chunk coordinate.
     */
    public void resetPlayerOrigin(UUID playerId) {
        originTileX.remove(playerId);
        originTileZ.remove(playerId);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();

        // Ignore tiny movements (same block)
        if (to == null ||
            (from.getBlockX() == to.getBlockX()
             && from.getBlockY() == to.getBlockY()
             && from.getBlockZ() == to.getBlockZ())) {
            return;
        }

        VoxelChunkGenerator generator = plugin.getVoxelChunkGenerator();

        // 1) Compute absolute tile coords from player's position
        double x = to.getX();
        double z = to.getZ();
        int absTileX = (int) Math.floor(x / 16);
        int absTileZ = (int) Math.floor(z / 16);

        // 2) If we have no origin for this player, set it now
        UUID pid = player.getUniqueId();
        if (!originTileX.containsKey(pid) || !originTileZ.containsKey(pid)) {
            originTileX.put(pid, absTileX);
            originTileZ.put(pid, absTileZ);
            player.sendMessage("[DEBUG] Setting your origin tile to (" + absTileX + ", " + absTileZ + ")");
        }

        // 3) Convert those absolute chunk coords to lat/lng to see which hemisphere we're in
        double[] latLng = generator.minecraftToLatLng(absTileX, absTileZ);
        double lat = latLng[0];
        double lng = latLng[1];

        // 4) Compute the relative movement from the origin
        int originX = originTileX.get(pid);
        int originZ = originTileZ.get(pid);

        int dx = absTileX - originX;
        int dz = absTileZ - originZ;

        // player.sendMessage("[DEBUG] lat/lng: (" + lat + ", " + lng + ") dx/dz: (" + dx + ", " + dz + ")");

        // 5) Quadrant-based flipping (meridian, equator)
        //    NE quadrant: lat>=0,lng>=0 => no flips
        //    NW quadrant: lat>=0,lng<0  => interchange XZ, flip X only
        //    SE quadrant: lat<0 ,lng>=0 => no flips
        //    SW quadrant: lat<0 ,lng<0  => interchange XZ, flip X only
        if (lat >= 0 && lng < 0) {
            // NW quadrant
            int temp = dx;
            dx = dz;
            dz = -temp;
        } else if (lat < 0 && lng >= 0) {
            // SE quadrant
        } else if (lng < 0 && lat < 0) {
            // SW quadrant
            int temp = dx;
            dx = dz;
            dz = -temp;
        }

        // 5) The final tile coords we pass to loadChunk
        int finalTileX = originX + dx;
        int finalTileZ = originZ + dz;

        // 6) Check if we've moved enough to load new tiles
        Location lastLoc = lastLoadedLocations.get(player);
        if (lastLoc == null || lastLoc.distance(to) >= LOAD_THRESHOLD) {
            lastLoadedLocations.put(player, to.clone());

            // player.sendMessage("[DEBUG] Now loading chunk at final coords ("
            //         + finalTileX + ", " + finalTileZ + ") for lat/lng: (" + lat + ", " + lng + ")");
            
            // 7) Actually load that chunk asynchronously
            generator.loadChunk(pid, finalTileX, finalTileZ, false, (blockLocation) -> {
                // Teleport or do something if needed
            });
        }
    }
}
