package org.tamasoft.creativegate

import org.bukkit.Bukkit
import java.util.logging.Level

/**
 * Runs the configurable console commands (createCommands / travelCommands).
 * Placeholder values are sanitised so a player or network name can never inject extra command arguments.
 */
object CommandHook {

    private val unsafeChars = Regex("[^\\p{L}\\p{N}_-]")

    private fun sanitize(value: String): String = unsafeChars.replace(value, "_").take(64)

    fun run(commands: List<String>, placeholders: Map<String, String>) {
        if (commands.isEmpty()) return

        val task = Runnable {
            for (raw in commands) {
                var command = raw
                for ((key, value) in placeholders) {
                    command = command.replace("{$key}", sanitize(value))
                }
                if (command.startsWith("/")) command = command.substring(1)
                if (command.isBlank()) continue

                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
                } catch (e: Exception) {
                    CreativeGate.instance?.logger?.log(Level.WARNING, "Prikaz '$command' selhal", e)
                }
            }
        }

        val plugin = CreativeGate.instance
        if (Bukkit.isPrimaryThread() || plugin == null) {
            task.run()
        } else {
            Bukkit.getScheduler().runTask(plugin, task)
        }
    }
}
