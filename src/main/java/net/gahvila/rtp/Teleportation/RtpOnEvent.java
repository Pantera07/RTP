package net.gahvila.rtp.Teleportation;

import net.gahvila.rtp.RTP;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.RegisteredListener;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

import java.util.WeakHashMap;
import java.util.logging.Level;

import static net.gahvila.rtp.Utils.GeneralUtil.isAnchorSpawn;

public class RtpOnEvent implements Listener {

    private final RandomTeleporter randomTeleporter;

    private final WeakHashMap<PlayerSpawnLocationEvent, Location>    oldSpawnLocationEvents    = new WeakHashMap<>();
    private final WeakHashMap<PlayerRespawnEvent, Location> oldRespawnEvents = new WeakHashMap<>();

    public RtpOnEvent(RandomTeleporter randomTeleporter) {this.randomTeleporter = randomTeleporter;}


    /**
     * When {@code firstJoinRtp} is enabled, this will RTP a player when they join the server
     * for the first time.
     *
     * @param event The PlayerSpawnLocationEvent
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void playerSpawnLocation(PlayerSpawnLocationEvent event) {
        // If any of these are true, no nothing.
        if (!randomTeleporter.firstJoinRtp ||  // Rtp-on-first-join is DISABLED
            event.getPlayer().hasPlayedBefore() || // The player HAS played
            event.getPlayer().hasPermission("jakesrtp.nofirstjoinrtp") // The player is exempt
        ) return;
        try {
            assert randomTeleporter.firstJoinSettings != null;
            assert randomTeleporter.firstJoinSettings.landingWorld != null;
            Location landingLoc = new RandomTeleportAction(
                randomTeleporter,
                randomTeleporter.firstJoinSettings,
                randomTeleporter.firstJoinSettings.landingWorld.getSpawnLocation(),
                true, true,
                randomTeleporter.logRtpOnPlayerJoin, "Rtp-on-join triggered!"
            ).requestLocation();
            event.setSpawnLocation(landingLoc);
            oldSpawnLocationEvents.put(event, landingLoc.clone());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void playerSpawnLocationMonitor(PlayerSpawnLocationEvent event) {
        Location properLoc = oldSpawnLocationEvents.get(event);
        Location actualLoc = event.getSpawnLocation();
        if (properLoc == null || locIntEqual(properLoc, actualLoc)) return;
        handlerLogging(event.getHandlers().getRegisteredListeners(), "rtp-on-join", "PlayerSpawnLocationEvent");
    }


    /**
     * When {@code onDeathRtp} is enabled, this will RTP a player when they die (with a few exceptions).<p>
     * A player will not be teleported randomly, even if {@code onDeathRtp} is true, if at least one of these
     * conditions is true:<p>
     * • {@code onDeathRequirePermission} is true, and the player does not have the correct permission<p>
     * • {@code onDeathRespectBeds} is true, and the player has a home bed
     *
     * @param event The PlayerRespawnEvent
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void playerRespawn(PlayerRespawnEvent event) {
        Location respawnLoc = event.getRespawnLocation();
        boolean isBedOrAnchorSpawn = event.isBedSpawn() || isAnchorSpawn(event);
        boolean respawnLocIsLava = isBedOrAnchorSpawn && !isSafeFromLava(respawnLoc);
        boolean runDefaultRTP =
                (!randomTeleporter.onDeathRespectBeds || !event.isBedSpawn()) &&
                        (!randomTeleporter.onDeathRespectAnchors || !isAnchorSpawn(event));
        boolean runLavaOverrideRTP = respawnLocIsLava;

        //All conditions must be met to continue
        if (randomTeleporter.onDeathRtp &&
                (!randomTeleporter.onDeathRequirePermission || event.getPlayer().hasPermission("jakesrtp.rtpondeath")) &&
                (runDefaultRTP || runLavaOverrideRTP)
        ) try {
            Location landingLoc = new RandomTeleportAction(
                randomTeleporter,
                randomTeleporter.onDeathSettings,
                event.getPlayer().getLocation(),
                true, true,
                randomTeleporter.logRtpOnRespawn, "Rtp-on-respawn triggered!"
            ).requestLocation();
            event.setRespawnLocation(landingLoc);
            oldRespawnEvents.put(event, landingLoc.clone());
            if (runLavaOverrideRTP) {
                RTP.infoLog(
                    event.getPlayer().getName() +
                        " - Lava Override: Respawn location (" +
                        respawnLoc.toVector() +
                        ") was unsafe, forcing RTP."
                );
                event.getPlayer().setRespawnLocation(null, true);
                event.getPlayer().sendMessage(Component.translatable("block.minecraft.spawn.not_valid"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void playerRespawnMonitor(PlayerRespawnEvent event) {
        Location properLoc = oldRespawnEvents.get(event);
        Location actualLoc = event.getRespawnLocation();
        if (properLoc == null || locIntEqual(properLoc, actualLoc)) return;
        handlerLogging(event.getHandlers().getRegisteredListeners(), "rtp-on-death", "PlayerRespawnEvent");
    }


    private static void handlerLogging(RegisteredListener[] registeredListeners, String settingName, String eventName) {
        int i = 0, us = 0;
        RTP.log(Level.WARNING, String.format(
            "It looks like you have %s enabled, but some plugin is messing with the results!\n" +
            "Here is a print out of every plugin that messes with the %s event, hopefully this helps.",
            settingName, eventName));
        for (RegisteredListener listener : registeredListeners) {
            String logMsg = String.format("%02d: [%s] %s", i++,
                                          listener.getPriority().toString(),
                                          listener.getPlugin().getName());
            if (listener.getPlugin() instanceof RTP) {
                if (us == 0) {
                    logMsg += " ~ Look between here ↓";
                    us++;
                } else if (us == 1) {
                    logMsg += " ~ And here ↑";
                    us++;
                }
            }
            RTP.infoLog(logMsg);
        }
    }

    private static boolean locIntEqual(Location a, Location b) {
        return a.getBlockX() == b.getBlockX() &&
               a.getBlockY() == b.getBlockY() &&
               a.getBlockZ() == b.getBlockZ();
    }

    private boolean isSafeFromLava(Location respawnLocation) {
        // Skip check if the chunk is not loaded to prevent lag/errors.
        if (!respawnLocation.getBlock().getChunk().isLoaded()) return true;

        // Create a new location with ceil(Y) to ensure we check the correct integer block height.
        int safeY = (int) Math.ceil(respawnLocation.getY());

        // Use the ceiling Y for block checking. X and Z remain the same.
        Location checkLocation = new Location(
                respawnLocation.getWorld(),
                respawnLocation.getX(),
                safeY,
                respawnLocation.getZ()
        );

        // 1. Block where the player's feet will be (at safeY).
        Material blockBelow = checkLocation.getBlock().getType();

        // 2. Block where the player's head will be (at safeY + 1).
        // Note: The player occupies two blocks (safeY and safeY + 1).
        Location headLocation = checkLocation.clone().add(0, 1, 0);
        Material blockAbove = headLocation.getBlock().getType();

        // Check if either of the two blocks is lava.
        if (blockBelow == Material.LAVA || blockAbove == Material.LAVA) {
            return false; // Not safe because lava was found.
        }

        return true; // Safe from lava.
    }
