package org.tamasoft.creativegate.gate

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.tamasoft.creativegate.CreativeGate
import org.tamasoft.creativegate.teleport.BlockLocation
import org.tamasoft.creativegate.util.JsonFileUtil
import java.io.File
import java.util.UUID
import java.util.logging.Level

/**
 * Registry of all gates. Everything here is accessed from the main thread only; the only thing that leaves the
 * main thread is the already serialised JSON string handed to the writer in [saveGates].
 */
object GatesCollector {

    val gates: MutableSet<Gate> = LinkedHashSet()

    // Gate has no equals/hashCode, so these maps are identity based and need no per-lookup string keys.
    private val creators: MutableMap<Gate, UUID> = HashMap()
    private val byCreator: MutableMap<UUID, MutableList<Gate>> = HashMap()

    @Volatile
    private var dirty = false
    private val writeLock = Any()

    fun markDirty() {
        dirty = true
    }

    // Stable key by world + sorted portal coords. Only used to persist and restore the creators file.
    private fun gateKey(gate: Gate): String {
        val world = gate.exit.world
        val coordsSource = if (gate.portalCoords.isNotEmpty()) gate.portalCoords else gate.frameCoords
        val coords = coordsSource
            .map { "${it.blockX},${it.blockY},${it.blockZ}" }
            .sorted()
            .joinToString("|")
        return "$world#$coords"
    }

    private fun effectiveCreator(gate: Gate): UUID? = creators[gate] ?: gate.creatorOrNull()

    private fun index(gate: Gate) {
        val creator = effectiveCreator(gate) ?: return
        val list = byCreator.computeIfAbsent(creator) { ArrayList() }
        if (!list.contains(gate)) list.add(gate)
    }

    private fun unindex(gate: Gate) {
        val creator = effectiveCreator(gate) ?: return
        val list = byCreator[creator] ?: return
        list.remove(gate)
        if (list.isEmpty()) byCreator.remove(creator)
    }

    fun setCreator(gate: Gate, creator: UUID) {
        unindex(gate)
        creators[gate] = creator
        index(gate)
        markDirty()
    }

    fun getCreator(gate: Gate): UUID? = effectiveCreator(gate)

    fun getByCreator(creator: UUID): List<Gate> = byCreator[creator]?.toList() ?: emptyList()

    /** Creator of the oldest gate in the network, or null when the network is empty. */
    fun getNetworkOwner(networkId: String): UUID? {
        val oldest = gates.filter { it.networkId == networkId }.minByOrNull { it.creationTimeMillis } ?: return null
        return effectiveCreator(oldest)
    }

    /** True when at least one gate has portal blocks in the given world. Cheap pre-check for hot block events. */
    fun hasPortalsIn(world: String): Boolean = Portals.hasAny(world)

    open class WorldToLocationToGateMap {
        private val map: MutableMap<String, MutableMap<BlockLocation, Gate>> = HashMap()

        fun hasAny(world: String): Boolean = map[world]?.isNotEmpty() == true

        operator fun get(startBlock: Block): Gate? {
            return map[startBlock.world.name]?.get(BlockLocation(startBlock.x, startBlock.y, startBlock.z))
        }

        operator fun get(location: Location): Gate? {
            val world = location.world ?: return null
            return map[world.name]?.get(BlockLocation.fromLocation(location))
        }

        operator fun set(world: String, location: BlockLocation, gate: Gate) {
            map.computeIfAbsent(world) { HashMap() }[location] = gate
        }

        fun remove(world: String, location: BlockLocation) {
            map[world]?.remove(location)
        }
    }

    open class WorldToLocationToGatesMap {
        private val map: MutableMap<String, MutableMap<BlockLocation, MutableSet<Gate>>> = HashMap()

        // Lookups never create entries. Callers like destroyGate(block) run for every broken block, falling block,
        // explosion block and so on; creating an empty set per lookup was an unbounded memory leak.
        private fun find(world: String, location: BlockLocation): Set<Gate> {
            val set = map[world]?.get(location)
            return if (set == null || set.isEmpty()) emptySet() else set.toSet()
        }

        operator fun get(startBlock: Block): Set<Gate> {
            return find(startBlock.world.name, BlockLocation(startBlock.x, startBlock.y, startBlock.z))
        }

        operator fun get(location: Location): Set<Gate> {
            val world = location.world ?: return emptySet()
            return find(world.name, BlockLocation.fromLocation(location))
        }

        operator fun get(world: String, location: BlockLocation): Set<Gate> = find(world, location)

        fun add(world: String, location: BlockLocation, gate: Gate) {
            map.computeIfAbsent(world) { HashMap() }.computeIfAbsent(location) { LinkedHashSet() }.add(gate)
        }

        fun remove(world: String, location: BlockLocation, gate: Gate) {
            val perWorld = map[world] ?: return
            val set = perWorld[location] ?: return
            set.remove(gate)
            if (set.isEmpty()) perWorld.remove(location)
        }
    }

    object Frames : WorldToLocationToGatesMap()
    object Portals : WorldToLocationToGateMap()

    fun register(gate: Gate) {
        gate.frameCoords.forEach { Frames.add(gate.exit.world, it, gate) }
        gate.portalCoords.forEach { Portals[gate.exit.world, it] = gate }
        gates.add(gate)
        index(gate)
        markDirty()
    }

    fun remove(gate: Gate) {
        gate.frameCoords.forEach { Frames.remove(gate.exit.world, it, gate) }
        gate.portalCoords.forEach { Portals.remove(gate.exit.world, it) }
        unindex(gate)
        creators.remove(gate)
        gates.remove(gate)
        markDirty()
    }

    fun getUniqueGates(): List<Gate> {
        return gates.toList()
    }

    fun getByNetworkId(networkId: String): List<Gate> {
        return gates
            .filter { it.networkId == networkId }
            .sortedBy { it.creationTimeMillis }
    }

    private fun creatorsFile(gatesFile: File) = File(gatesFile.parentFile, "${gatesFile.nameWithoutExtension}_creators.json")

    fun loadFromFile(gatesFile: File) {
        if (!gatesFile.exists()) {
            JsonFileUtil.writeAtomic(gatesFile, GateList(getUniqueGates()))
            return
        }

        val gateList = try {
            JsonFileUtil.read(gatesFile, GateList::class.java)
        } catch (e: Exception) {
            // Never continue with an empty list and overwrite the file on the next save: keep the broken file.
            val broken = File(gatesFile.parentFile, "${gatesFile.name}.broken-${System.currentTimeMillis()}")
            val moved = gatesFile.renameTo(broken)
            Bukkit.getLogger().log(
                Level.SEVERE,
                "[CreativeGate] Could not read ${gatesFile.name} (${if (moved) "kept as ${broken.name}" else "could not be moved"}). Starting without gates.",
                e
            )
            return
        }

        // Creators are keyed by gate position, so they can only be matched after the gates are loaded.
        val storedCreators = HashMap<String, UUID>()
        val creatorsFile = creatorsFile(gatesFile)
        if (creatorsFile.exists()) {
            try {
                val store = JsonFileUtil.read(creatorsFile, CreatorsStore::class.java)
                store.creators.forEach { (k, v) ->
                    try {
                        storedCreators[k] = UUID.fromString(v)
                    } catch (_: IllegalArgumentException) { /* ignore bad entry */ }
                }
            } catch (e: Exception) {
                Bukkit.getLogger().log(Level.WARNING, "[CreativeGate] Could not read ${creatorsFile.name}, using creators stored in gates.json", e)
            }
        }

        gateList.gates.forEach { gate ->
            gate.ensureCreatorId()
            storedCreators[gateKey(gate)]?.let { creators[gate] = it }
            register(gate)
        }
        dirty = false
    }

    /**
     * Saves gates and the creators file. The JSON is built here, on the calling (main) thread, so no gate is ever
     * serialised while it is being modified, and then written atomically. Nothing is written when nothing changed
     * (async periodic saves only); shutdown always saves synchronously.
     */
    fun saveGates(gatesFile: File, async: Boolean = true) {
        if (async && !dirty) return
        dirty = false

        val gatesJson: String
        val creatorsJson: String
        try {
            gatesJson = JsonFileUtil.toJson(GateList(getUniqueGates()))
            val creatorsMap = HashMap<String, String>()
            gates.forEach { gate -> effectiveCreator(gate)?.let { creatorsMap[gateKey(gate)] = it.toString() } }
            creatorsJson = JsonFileUtil.toJson(CreatorsStore(creatorsMap))
        } catch (e: Exception) {
            dirty = true
            Bukkit.getLogger().log(Level.SEVERE, "[CreativeGate] Could not serialise gates", e)
            return
        }

        val creatorsFile = creatorsFile(gatesFile)
        val write = Runnable {
            synchronized(writeLock) {
                try {
                    JsonFileUtil.writeAtomicJson(gatesFile, gatesJson)
                    JsonFileUtil.writeAtomicJson(creatorsFile, creatorsJson)
                } catch (e: Exception) {
                    dirty = true
                    Bukkit.getLogger().log(Level.SEVERE, "[CreativeGate] Could not save gates", e)
                }
            }
        }

        val plugin = CreativeGate.instance
        if (async && plugin != null && plugin.isEnabled) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, write)
        } else {
            write.run()
        }
    }

    // creators JSON DTO
    data class CreatorsStore(
        val creators: Map<String, String> = emptyMap()
    )
}
