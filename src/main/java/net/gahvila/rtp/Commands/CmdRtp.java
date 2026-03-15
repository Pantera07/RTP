package net.gahvila.rtp.Commands;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.gahvila.rtp.Exceptions.JrtpBaseException;
import net.gahvila.rtp.Messages.Messages;
import net.gahvila.rtp.Teleportation.RandomTeleportAction;
import net.gahvila.rtp.Teleportation.RandomTeleporter;
import net.gahvila.rtp.Teleportation.RtpProfile;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static net.gahvila.rtp.RTP.plugin;

public class CmdRtp implements TabExecutor {

    private final RandomTeleporter randomTeleporter;

    public CmdRtp(RandomTeleporter randomTeleporter) { this.randomTeleporter = randomTeleporter; }

    /**
     * This is called when a player runs the in-game "/rtp" command.
     * Anything (except errors) that directly deals with the player is done here.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if ((args.length == 0 || args.length == 1) && sender instanceof Player) {
                Player player = (Player) sender;
                if (args.length == 1 && !sender.hasPermission("jakesrtp.usebyname"))
                    return false;
                RtpProfile relSettings = args.length == 0
                    ? randomTeleporter.getRtpSettingsByWorldForPlayer(player)
                    : randomTeleporter.getRtpSettingsByNameForPlayer(player, args[0]);
                if (player.hasPermission("jakesrtp.nocooldown") // If the player has permission to skip cooldown
                    || player.hasPermission("jakesrtp.nocooldown." + relSettings.name.toLowerCase())
                    || relSettings.coolDown.check(player.getName())) { // Or they are not on cooldown
                    if (!randomTeleporter.playersInWarmup.containsKey(player.getUniqueId())) {
                        final boolean warmup =
                            relSettings.warmupEnabled && // Obvious...
                            !player.hasPermission("jakesrtp.nowarmup") && // Or if they have the perm to avoid it
                            !player.hasPermission("jakesrtp.nowarmup." + relSettings.name.toLowerCase());
                        if (!plugin.canUseEconomy() || relSettings.cost <= 0 ||
                            plugin.getEconomy().getBalance(player) >= relSettings.cost) {
                            // ==== By this point, all checks are done and the player WILL be teleported. ====
                            final Consumer<ScheduledTask> execRtp = makeFoliaTask(player, relSettings, warmup);
                            if (warmup) { // If there is a warmup, schedule the runnable
                                ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, execRtp, null, 2, 20);
                                if (task == null) // This should only really happen during shutdown.
                                    throw new JrtpBaseException("Could not schedule rtp-after-warmup.");
                                randomTeleporter.playersInWarmup.put(player.getUniqueId(), task); // Needed for canceling.
                            } else execRtp.accept(null); // No warmup, just run the teleport.
                        } else player.sendRichMessage(Messages.ECON_NOT_ENOUGH_MONEY.format(
                            relSettings.cost, plugin.getEconomy().getBalance(player)));
                    } else player.sendRichMessage(Messages.WARMUP_RTP_ALREADY_CALLED.format());
                } else player.sendRichMessage(Messages.NEED_WAIT_COOLDOWN.format(
                    relSettings.coolDown.timeLeftWords(player.getName())));
            }
        } catch (JrtpBaseException.NotPermittedException npe) {
            sender.sendRichMessage(Messages.NP_GENERIC.format(npe.getMessage()));
        } catch (JrtpBaseException e) {
            sender.sendRichMessage(e.getMessage());
            e.printStackTrace();
        }
        return true;
    }

    private Consumer<ScheduledTask> makeFoliaTask(final Player player, final RtpProfile rtpProfile, boolean calculatedWarmup) {
        return new Consumer<ScheduledTask>() {
            private final Location startLoc = player.getLocation().clone();
            private final boolean warmup = calculatedWarmup;
            private final long startTime = System.currentTimeMillis();
            private int done = 0;

            @Override
            public void accept(ScheduledTask task) {
                // The annoying error message, lets hope we never need use this...
                if (done > 1) taskError(task);
                    // If There should be no warmup, we teleport the user immediately.
                else if (!warmup) teleport(task);
                    // If we want the user to stand still AND they move, we cancel this runnable / future rtp.
                else if (rtpProfile.warmupCancelOnMove &&
                        (startLoc.getWorld() != player.getWorld() || startLoc.distance(player.getLocation()) > 1))
                    cancel(task);
                    // If we have waited enough time, we teleport the user.
                else if (timeDifInSeconds() >= rtpProfile.warmup) teleport(task);
                    // If we got to this point, the user still has to wait, and if wanted, we let them know how long.
                else if (rtpProfile.warmupCountDown) countDown();
                // If none of these were called, we just silently wait until the next time run() is called.
            }

            private int timeDifInSeconds() { return (int) ((System.currentTimeMillis() - startTime) / 1000); }

            private void countDown() {
                player.sendRichMessage(Messages.
                    WARMUP_TELEPORTING_IN_X.format(
                    rtpProfile.warmup - timeDifInSeconds()
                ));
            }

            private void teleport(ScheduledTask task) {
                try {
                    if (rtpProfile.cost > 0 && plugin.getEconomy().getBalance(player) < rtpProfile.cost) {
                        player.sendRichMessage(Messages.ECON_NO_LONGER_ENOUGH_MONEY.format());
                        return;
                    }
                    // Do the teleport action
                    var rtpAction = new RandomTeleportAction(
                        randomTeleporter,
                        rtpProfile,
                        player.getLocation(),
                        true,
                        true,
                        randomTeleporter.logRtpOnCommand, "Rtp-from-command triggered!"
                    );
                    rtpAction.teleportAsync(player);
                    // Log in the cooldown list
                    rtpProfile.coolDown.log(player.getName(), System.currentTimeMillis());
                    // Charge the player
                    if (rtpProfile.cost > 0) {
                        EconomyResponse er = plugin.getEconomy().withdrawPlayer(player, rtpProfile.cost);
                        if (er.transactionSuccess()) player.sendRichMessage(Messages.ECON_YOU_WERE_CHARGED_X.format(
                            plugin.getEconomy().format(er.amount),
                            plugin.getEconomy().format(er.balance)));
                        else player.sendRichMessage(Messages.ECON_ERROR.format(
                            "An economy error occurred: {0}", er.errorMessage));
                    }
                } catch (Exception e) {
                    player.sendRichMessage(Messages.NP_UNEXPECTED_EXCEPTION.format(e.getMessage()));
                    e.printStackTrace();
                } finally {
                    cancelTask(task);
                }
            }

            private void cancel(ScheduledTask task) {
                player.sendRichMessage(Messages.WARMUP_CANCEL_BECAUSE_MOVE.format());
                cancelTask(task);
            }

            private void cancelTask(ScheduledTask task) {
                done++;
                ScheduledTask storedTask = randomTeleporter.playersInWarmup.remove(player.getUniqueId());

                if (task != null) {
                    task.cancel(); // Only cancel if task existed.
                } else if (storedTask != null) {
                    storedTask.cancel();
                }
            }

            private void taskError(ScheduledTask task) {
                if (task != null) task.cancel();
                if (done < 10 || done % 100 == 0) throw new RuntimeException("RTP task run twice?? Please report.");
                done++; // This is meant to be annoying, but not *too* annoying.
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        ArrayList<String> validSearches = new ArrayList<>();
        if (args.length == 0 ||
            !(sender instanceof Player) ||
            !sender.hasPermission("jakesrtp.usebyname")
        ) return validSearches;
        for (String name : randomTeleporter.getRtpSettingsNamesForPlayer((Player) sender))
            if (name.contains(args[0]))
                validSearches.add(name);
        return validSearches;
    }
}
