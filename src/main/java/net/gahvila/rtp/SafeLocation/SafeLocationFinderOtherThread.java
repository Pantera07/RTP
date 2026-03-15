package net.gahvila.rtp.SafeLocation;

import net.gahvila.rtp.Exceptions.JrtpBaseException;
import net.gahvila.rtp.RTP;
import org.bukkit.*;
import org.bukkit.block.Biome;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

import static net.gahvila.rtp.RTP.infoLog;
import static net.gahvila.rtp.RTP.plugin;
import static net.gahvila.rtp.SafeLocation.SafeLocationUtils.chunkXZ;


public class SafeLocationFinderOtherThread extends SafeLocationFinder {

    private final Map<String, ChunkSnapshot> chunkSnapshotMap = new HashMap<>();
    private final int                        timeout;

    /**
     * Just constructs the {@code SafeLocationFinder}, use {@code checkSafety} to check if
     * the current location is safe, and use {@code nextInSpiral} to move on to the next location.
     *
     * @param loc The location that will be checked for safety, and potentially modified.
     */
    public SafeLocationFinderOtherThread(Location loc) {
        super(loc);
        timeout = 5;
    }

    /**
     * This constructs a fully managed {@code SafeLocationFinder}. Because the bounds for the check operations are
     * supplied, this object can check the safety of the location itself by calling instance method
     * {@code tryAndMakeSafe()}. The given location <b>will</b> be modified.
     *
     * @param loc             The location to try and make safe. This <b>will</b> be modified.
     * @param checkRadiusXZ   The distance out from the center that the location cam move.
     * @param checkRadiusVert The distance up and down that the location can move.
     * @param lowBound        The lowest Y value the location can have.
     * @param timeout         The max number of seconds to wait for data from another thread
     */
    public SafeLocationFinderOtherThread(
        Location loc, int checkRadiusXZ, int checkRadiusVert,
        int lowBound, int highBound, int timeout
    ) {
        super(loc, checkRadiusXZ, checkRadiusVert, lowBound, highBound);
        this.timeout = timeout;
    }

    /**
     * Gets the material of the location as if by {@code loc.getBlock().getType()}.<p>
     * Since this is the overridden version, we can not get the material the easy way.
     * Instead, we need to get and use a {@code ChunkSnapshot}.
     *
     * @param loc The location to get the material for.
     */
    @Override
    protected Material getLocMaterial(Location loc) throws JrtpBaseException.PluginDisabledException, TimeoutException {
        return SafeLocationUtils.util.locMatFromSnapshot(loc, getChunkForLocation(loc));
    }

    @Override
    protected Biome getLocBiome(Location loc) throws JrtpBaseException.PluginDisabledException, TimeoutException {
        return SafeLocationUtils.util.locBiomeFromSnapshot(loc, getChunkForLocation(loc));
    }

    @Override
    protected void dropToGround() throws JrtpBaseException.PluginDisabledException, TimeoutException {
        SafeLocationUtils.util.dropToGround(loc, lowBound, highBound, getChunkForLocation(loc));
    }

    @Override
    protected void dropToMiddle() throws JrtpBaseException.PluginDisabledException, TimeoutException {
        SafeLocationUtils.util.dropToMiddle(loc, lowBound, highBound, getChunkForLocation(loc));
    }

    private ChunkSnapshot getChunkForLocation(Location loc)
            throws JrtpBaseException.PluginDisabledException, IllegalStateException, TimeoutException {
        String chunkKey = chunkXZ(loc.getX()) + " " + chunkXZ(loc.getZ());
        ChunkSnapshot chunkSnapshot = chunkSnapshotMap.get(chunkKey);


        if (chunkSnapshot != null) return chunkSnapshot;
        try {
            CompletableFuture<ChunkSnapshot> chunkFuture = loc.getWorld().getChunkAtAsync(loc)
                    .thenApply(chunk -> chunk.getChunkSnapshot(false, true, false, false));

            chunkSnapshot = chunkFuture.get(timeout, TimeUnit.SECONDS);

            if (chunkSnapshot == null) {
                throw new TimeoutException("Essentially timed out ~");
            }

            if (plugin.locCache()) {
                chunkSnapshotMap.put(chunkKey, chunkSnapshot);
            } else {
                throw new JrtpBaseException.PluginDisabledException();
            }

        } catch (java.util.concurrent.TimeoutException e) {
            throw new TimeoutException("Essentially timed out ~");
        } catch (CancellationException e) {
            throw new JrtpBaseException.PluginDisabledException();
        } catch (InterruptedException e) {
            infoLog("Caught an unexpected interrupt.");
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
        return chunkSnapshot;
    }
}
