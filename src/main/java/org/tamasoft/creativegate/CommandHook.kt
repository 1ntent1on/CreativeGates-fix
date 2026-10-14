package org.tamasoft.creativegate

import org.bukkit.Bukkit
import java.util.logging.Level

/** Spouští příkazy z configu za konzoli (pro napojení na Badges a podobně). */
object CommandHook {

    /**
     * Hodnoty od hráče (název sítě = jméno itemu z kovadliny) se nesmí dostat do příkazu
     * s mezerami nebo speciálními znaky, jinak by si hráč mohl do příkazu z konzole
     * připsat vlastní argumenty. Povolená jsou jen písmena, číslice, _ a -.
     */
    private fun sanitize(value: String): String =
        value.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(64)

    fun run(commands: List<String>, placeholders: Map<String, String>) {
        if (commands.isEmpty()) return
        val task = Runnable {
            for (raw in commands) {
                var cmd = raw
                for ((key, value) in placeholders) {
                    cmd = cmd.replace("{$key}", sanitize(value))
                }
                if (cmd.startsWith("/")) cmd = cmd.substring(1)
                if (cmd.isBlank()) continue
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
                } catch (e: Exception) {
                    CreativeGate.instance?.logger?.log(Level.WARNING, "Prikaz '$cmd' selhal", e)
                }
            }
        }
        val plugin = CreativeGate.instance
        if (Bukkit.isPrimaryThread() || plugin == null) task.run()
        else Bukkit.getScheduler().runTask(plugin, task)
    }
}
