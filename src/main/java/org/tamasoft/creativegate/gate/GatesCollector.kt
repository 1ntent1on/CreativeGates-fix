package org.tamasoft.creativegate.gate

import org.bukkit.Location
import org.bukkit.block.Block
import org.tamasoft.creativegate.teleport.BlockLocation
import org.tamasoft.creativegate.util.JsonFileUtil
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

object GatesCollector {

    val gates: MutableSet<Gate> = HashSet()

    // creator index (in-mem)
    private val creators: MutableMap<String, UUID> = ConcurrentHashMap()

    // stable key by world + sorted portal coords
    private fun gateKey(gate: Gate): String {
        val world = gate.exit.world
        val coordsSource = if (gate.portalCoords.isNotEmpty()) gate.portalCoords else gate.frameCoords
        val coords = coordsSource
            .map { "${it.blockX},${it.blockY},${it.blockZ}" }
            .sorted()
            .joinToString("|")
        return "$world#$coords"
    }

    fun setCreator(gate: Gate, creator: UUID) {
        creators[gateKey(gate)] = creator
    }

    fun getCreator(gate: Gate): UUID? = creators[gateKey(gate)]

    fun getByCreator(creator: UUID): List<Gate> =
        gates.filter { creators[gateKey(it)] == creator }

    open class WorldToLocationToGateMap {
        private val map: MutableMap<String, MutableMap<BlockLocation, Gate>> = LinkedHashMap()
        private operator fun get(world: String): MutableMap<BlockLocation, Gate> {
            return map.computeIfAbsent(world) { LinkedHashMap() }
        }
        operator fun get(startBlock: Block): Gate? {
            return this[startBlock.location]
        }
        operator fun get(location: Location): Gate? {
            val world = location.world.name
            val blockLocation = BlockLocation.fromLocation(location)
            return this[world][blockLocation]
        }
        operator fun set(world: String, location: BlockLocation, gate: Gate) {
            this[world][location] = gate
        }
        fun remove(world: String, location: BlockLocation) {
            this[world].remove(location)
        }
    }

    open class WorldToLocationToGatesMap {
        private val map: MutableMap<String, MutableMap<BlockLocation, MutableSet<Gate>>> = LinkedHashMap()
        operator fun get(startBlock: Block): MutableSet<Gate> {
            return this[startBlock.location]
        }
        operator fun get(location: Location): MutableSet<Gate> {
            return this[location.world.name, BlockLocation.fromLocation(location)]
        }
        private fun getRaw(world: String, location: BlockLocation): MutableSet<Gate> {
            return map
                .computeIfAbsent(world) { LinkedHashMap() }
                .computeIfAbsent(location) { LinkedHashSet() }
        }
        operator fun get(world: String, location: BlockLocation): MutableSet<Gate> {
            return getRaw(world, location).toMutableSet()
        }
        fun add(world: String, location: BlockLocation, gate: Gate) {
            getRaw(world, location).add(gate)
        }
        fun remove(world: String, location: BlockLocation, gate: Gate) {
            getRaw(world, location).remove(gate)
        }
    }

    object Frames : WorldToLocationToGatesMap()
    object Portals : WorldToLocationToGateMap()

    fun register(gate: Gate) {
        gate.frameCoords.forEach { Frames.add(gate.exit.world, it, gate) }
        gate.portalCoords.forEach { Portals[gate.exit.world, it] = gate }
        gates.add(gate)
    }

    fun remove(gate: Gate) {
        gate.frameCoords.forEach { Frames.remove(gate.exit.world, it, gate) }
        gate.portalCoords.forEach { Portals.remove(gate.exit.world, it) }
        creators.remove(gateKey(gate))
        gates.remove(gate)
    }

    fun getUniqueGates(): List<Gate> {
        return gates.toList()
    }

    fun getByNetworkId(networkId: String): List<Gate> {
        val ret: MutableList<Gate> = ArrayList()
        gates.stream()
            .filter { g: Gate -> g.networkId == networkId }
            .distinct()
            .sorted(Comparator.comparingLong(Gate::creationTimeMillis))
            .forEach(ret::add)
        return ret
    }

    fun loadFromFile(gatesFile: File) {
        if (gatesFile.exists()) {
            val gateList = JsonFileUtil.read(gatesFile, GateList::class.java)
            gateList.gates.forEach { register(it) }
            // load creators map (separate JSON)
            val creatorsFile = File(gatesFile.parentFile, "${gatesFile.nameWithoutExtension}_creators.json")
            if (creatorsFile.exists()) {
                val store = JsonFileUtil.read(creatorsFile, CreatorsStore::class.java)
                creators.clear()
                store.creators.forEach { (k, v) ->
                    try {
                        creators[k] = UUID.fromString(v)
                    } catch (_: IllegalArgumentException) { /* ignore bad */ }
                }
            }
        } else {
            val gateList = GateList(getUniqueGates())
            JsonFileUtil.write(gatesFile, gateList)
        }
    }

    /**
     * Snapshot se bere na hlavním vlákně (kolekce nejsou thread-safe), zápis pak může běžet
     * asynchronně. Soubor se zapisuje přes .tmp a přesun, takže pád uprostřed zápisu nepoškodí data.
     */
    fun saveGates(gatesFile: File, async: Boolean = true) {
        val gateList = GateList(getUniqueGates())
        val creatorsMap = HashMap<String, String>()
        gates.forEach { g ->
            val k = gateKey(g)
            creators[k]?.let { creatorsMap[k] = it.toString() }
        }
        val creatorsFile = File(gatesFile.parentFile, "${gatesFile.nameWithoutExtension}_creators.json")
        val write = {
            synchronized(this) {
                JsonFileUtil.writeAtomic(gatesFile, gateList)
                JsonFileUtil.writeAtomic(creatorsFile, CreatorsStore(creatorsMap))
            }
        }
        if (async) thread(start = true, name = "CreativeGate-save") { write() } else write()
    }

    // creators JSON DTO
    data class CreatorsStore(
        val creators: Map<String, String> = emptyMap()
    )
}