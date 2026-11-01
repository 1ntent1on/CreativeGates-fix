package org.tamasoft.creativegate.gate

import com.fasterxml.jackson.annotation.JsonIdentityInfo
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.ObjectIdGenerators
import org.apache.commons.lang3.builder.HashCodeBuilder
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Orientable
import org.bukkit.entity.Player
import org.tamasoft.creativegate.CreativeGate
import org.tamasoft.creativegate.State
import org.tamasoft.creativegate.teleport.BlockLocation
import org.tamasoft.creativegate.teleport.Destination
import org.tamasoft.creativegate.teleport.TeleporterException
import org.tamasoft.creativegate.util.SmokeUtil
import org.tamasoft.creativegate.util.TeleportUtil
import org.tamasoft.creativegate.util.TxtUtil
import org.tamasoft.creativegate.util.VoidUtil
import java.util.*
import kotlin.collections.ArrayList

private val NIL_UUID = UUID(0L, 0L)

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator::class, property = "id")
class Gate() {

    lateinit var networkId: String
    lateinit var exit: Destination
    lateinit var creatorId: UUID
    var creationTimeMillis: Long = 0
    var id: Int = 0

    constructor(
        networkId: String,
        exit: Destination,
        frameCoords: Set<BlockLocation>,
        portalCoords: Set<BlockLocation>,
        creatorId: UUID
    ) : this() {
        this.networkId = networkId
        this.exit = exit
        this.frameCoords = frameCoords
        this.portalCoords = portalCoords
        this.creatorId = creatorId
        this.creationTimeMillis = System.currentTimeMillis()
    }


    fun calcId() : Int {
        val builder = HashCodeBuilder()
        frameCoords.stream()
            .sorted(Comparator.comparingInt(BlockLocation::blockX)
                .thenComparingInt(BlockLocation::blockY)
                .thenComparingInt(BlockLocation::blockZ)
            )
            .forEach(builder::append)
        return builder.hashCode()
    }


    // Mutations flag the registry so the periodic save only writes when something changed.
    var restricted = false
        set(value) {
            field = value
            GatesCollector.markDirty()
        }
    var enterEnabled = true
        set(value) {
            field = value
            GatesCollector.markDirty()
        }
    var exitEnabled = true
        set(value) {
            field = value
            GatesCollector.markDirty()
        }
    var frameCoords: Set<BlockLocation> = HashSet()
        get() {
            return Collections.unmodifiableSet(field)
        }
        set(coords) {
            field = HashSet(coords)
            id = calcId()
        }
    var portalCoords: Set<BlockLocation> = HashSet()
        get() {
            return Collections.unmodifiableSet(field)
        }
        set(coords) {
            field = HashSet(coords)
            id = calcId()
        }

    /** creatorId is lateinit and missing in old gates.json files; never touch it directly outside this class. */
    fun creatorOrNull(): UUID? {
        if (!::creatorId.isInitialized) return null
        return if (creatorId == NIL_UUID) null else creatorId
    }

    /** Creator from the registry (which also knows creators restored from the creators file), else creatorId. */
    fun resolveCreator(): UUID? {
        return GatesCollector.getCreator(this) ?: creatorOrNull()
    }

    fun isCreator(sender: Player): Boolean {
        return sender.uniqueId == resolveCreator()
    }

    fun destroy() {
        GatesCollector.remove(this)
        empty()
        fxKitDestroy()
    }

    fun toggleMode() {
        val enter = enterEnabled
        val exit = exitEnabled
        if (enter) {
            if (exit) {
                enterEnabled = false
                exitEnabled = false
            } else {
                enterEnabled = false
                exitEnabled = true
            }
        } else {
            if (exit) {
                enterEnabled = true
                exitEnabled = true
            } else {
                enterEnabled = true
                exitEnabled = false
            }
        }
    }

    /**
     * Sends the player to the first usable exit in the network chain. The teleport is asynchronous (the destination
     * chunk may still have to load), so the result is reported through [onSuccess], which runs on the main thread
     * only after the player has really arrived.
     */
    fun transport(player: Player, onSuccess: () -> Unit = {}) {
        if (!TeleportUtil.begin(player)) return // a previous teleport of this player is still in flight
        tryExits(player, calcGatesInChainAfterThis().iterator(), onSuccess)
    }

    private fun tryExits(player: Player, exits: Iterator<Gate>, onSuccess: () -> Unit) {
        while (exits.hasNext()) {
            val gate = exits.next()
            if (!gate.exitEnabled) continue

            val future = try {
                TeleportUtil.teleport(player, gate.exit)
            } catch (e: TeleporterException) {
                e.message?.let { player.sendMessage(it) }
                continue
            }

            future.whenComplete { arrived, _ ->
                runSync {
                    if (arrived == true) {
                        TeleportUtil.end(player)
                        fxKitUse()
                        onSuccess()
                    } else {
                        // Cancelled by another plugin (combat tag, claim rules, ...): try the next exit.
                        tryExits(player, exits, onSuccess)
                    }
                }
            }
            return
        }
        TeleportUtil.end(player)
        TxtUtil.sendTranslation(player, CreativeGate.configuration.gateNoDestinationMessage)
    }

    private fun runSync(task: () -> Unit) {
        val plugin = CreativeGate.instance
        if (Bukkit.isPrimaryThread() || plugin == null || !plugin.isEnabled) {
            task()
        } else {
            Bukkit.getScheduler().runTask(plugin, Runnable { task() })
        }
    }

    fun calcGatesInChainAfterThis(): List<Gate> {
        val rawChain = GatesCollector.getByNetworkId(networkId)
        val myIndex = rawChain.indexOf(this)
        // A gate that was removed in the same tick has no chain; that must not throw out of an event handler.
        if (myIndex < 0) return emptyList()

        val ret: MutableList<Gate> = ArrayList()

        // Add what is after me
        ret.addAll(rawChain.subList(myIndex + 1, rawChain.size))

        // Add what is before me
        ret.addAll(rawChain.subList(0, myIndex))

        return ret
    }

    /** Old gates.json files have no creatorId; give them a placeholder so serialising never throws. */
    fun ensureCreatorId() {
        if (!::creatorId.isInitialized) creatorId = NIL_UUID
    }

    // These blocks are sorted since the coords are sorted
    val frameBlocks: List<Block>?
        @JsonIgnore
        get() {
            return findBlocks(frameCoords)
        }

    val portalBlocks: List<Block>?
        @JsonIgnore
        get() {
            return findBlocks(portalCoords)
        }

    private fun findBlocks(coords: Set<BlockLocation>): MutableList<Block>? {
        val ret: MutableList<Block> = ArrayList()
        val world: World = try {
            exit.getBukkitWorld()
        } catch (e: IllegalStateException) {
            return null
        }
        for (coord in coords) {
            val bx = coord.blockX
            val by = coord.blockY
            val bz = coord.blockZ
            val block = world.getBlockAt(bx, by, bz)
            ret.add(block)
        }
        return ret
    }

    val centerBlock: Block?
        @JsonIgnore
        get() {
            val blocks = portalBlocks ?: return null
            return blocks[blocks.size / 2]
        }
    val isIntact: Boolean
        @JsonIgnore
        get() {
            val blocks = frameBlocks ?: return true
            for (block in blocks) {
                if (VoidUtil.isVoid(block)) {
                    return false
                }
            }
            return true
        }

    fun setContent(material: Material) {
        val blocks = portalBlocks ?: return
        var axis: Axis = Axis.X

        // Orientation check
        if (material == Material.NETHER_PORTAL) {
            val origin = blocks[0]
            val blockSouth = origin.getRelative(BlockFace.SOUTH)
            val blockNorth = origin.getRelative(BlockFace.NORTH)
            if (blocks.contains(blockNorth) || blocks.contains(blockSouth)) {
                axis = Axis.Z
            }
        }
        for (block in blocks) {
            val blockMaterial = block.type
            if (blockMaterial != Material.NETHER_PORTAL
                && blockMaterial != Material.WATER
                && !VoidUtil.isVoid(blockMaterial)
            ) continue
            block.type = material

            // Apply orientation
            if (material != Material.NETHER_PORTAL) continue
            val orientable = block.blockData as Orientable
            orientable.axis = axis
            block.blockData = orientable
        }
    }

    fun fill() {
        State.isFilling = true
        setContent(if (CreativeGate.configuration.usingWater) Material.WATER else Material.NETHER_PORTAL)
        State.isFilling = false
    }

    fun empty() {
        setContent(Material.AIR)
    }

    fun fxKitCreate() {
        fxShootSound()
    }

    fun fxKitUse() {
        if (!CreativeGate.configuration.teleportationSoundActive) return
        fxShootSound()
    }

    fun fxKitDestroy() {
        fxExplode()
    }

    fun fxExplode() {
        val block = centerBlock ?: return
        val location = block.location
        SmokeUtil.fakeExplosion(location)
    }

    fun fxShootSound() {
        val block = centerBlock ?: return
        val location = block.location
        location.world?.playEffect(location, Effect.GHAST_SHOOT, 0)
    }
}