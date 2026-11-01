package org.tamasoft.creativegate.util

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.tamasoft.creativegate.teleport.Destination
import org.tamasoft.creativegate.teleport.TeleporterException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object TeleportUtil {

    // Players whose gate teleport is still in flight. Without this, standing in the portal while the
    // destination chunk loads would start a second teleport.
    private val inFlight: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun begin(player: Player): Boolean = inFlight.add(player.uniqueId)

    fun end(player: Player) {
        inFlight.remove(player.uniqueId)
    }

    fun clear() {
        inFlight.clear()
    }

    /**
     * Resolves the exit location: centre of the stored block (the old code used the block corner, which put the
     * hitbox into up to four neighbouring blocks) with the stored heading.
     */
    @Throws(TeleporterException::class)
    fun resolveLocation(destination: Destination): Location {
        try {
            val world = destination.getBukkitWorld()
            val bx = destination.location.blockX
            val by = destination.location.blockY
            val bz = destination.location.blockZ

            // Only inspect blocks of an already loaded chunk, so this check never forces a synchronous chunk load.
            if (world.isChunkLoaded(bx shr 4, bz shr 4)) {
                val feet = world.getBlockAt(bx, by, bz)
                val head = world.getBlockAt(bx, by + 1, bz)
                if (!feet.isPassable || !head.isPassable) {
                    throw TeleporterException(TxtUtil.parse("<b>The exit of this gate is blocked."))
                }
            }

            return Location(
                world,
                bx + 0.5,
                by.toDouble(),
                bz + 0.5,
                destination.heading.yaw,
                destination.heading.pitch
            )
        } catch (e: TeleporterException) {
            throw e
        } catch (e: Exception) {
            throw TeleporterException(TxtUtil.parse("<b>Could not calculate the location: ${e.message}"))
        }
    }

    /**
     * Starts the teleport. The returned future completes with true once the player has arrived and with false when
     * the teleport was cancelled (for example by another plugin).
     */
    @Throws(TeleporterException::class)
    fun teleport(teleportee: Player, destination: Destination): CompletableFuture<Boolean> {
        val location = resolveLocation(destination)

        // Eject passengers and unmount before transport.
        teleportee.eject()
        teleportee.vehicle?.eject()

        return teleportee.teleportAsync(location, PlayerTeleportEvent.TeleportCause.PLUGIN)
    }
}
