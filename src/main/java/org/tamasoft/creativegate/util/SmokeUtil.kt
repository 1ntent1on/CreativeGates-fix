package org.tamasoft.creativegate.util

import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound

object SmokeUtil {

    /**
     * Visual/audio "explosion" effect for a destroyed gate.
     *
     * This used to call World#createExplosion, which is a real explosion: it damaged and knocked back nearby
     * players (including whoever just broke the frame) and destroyed dropped items. Particles and a sound
     * give the same look without touching any entity.
     */
    @JvmOverloads
    fun fakeExplosion(location: Location, @Suppress("UNUSED_PARAMETER") power: Float = 4f) {
        val world = location.world ?: return
        try {
            world.spawnParticle(Particle.EXPLOSION, location, 1)
            world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f)
        } catch (_: Throwable) {
            // Constant renamed/removed in a newer API: skip the effect rather than break gate removal.
        }
    }
}
