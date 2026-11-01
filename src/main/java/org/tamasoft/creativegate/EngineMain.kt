package org.tamasoft.creativegate

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.*
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.player.*
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.tamasoft.creativegate.gate.Gate
import org.tamasoft.creativegate.gate.GatesCollector
import org.tamasoft.creativegate.teleport.BlockLocation
import org.tamasoft.creativegate.teleport.Destination
import org.tamasoft.creativegate.teleport.Heading
import org.tamasoft.creativegate.util.*
import java.util.*


class EngineMain : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun stabilizePortalContent(event: BlockPhysicsEvent) {
        // If a portal block is running physics ...
        val block = event.block
        if (block.type != Material.NETHER_PORTAL) return

        // ... and we are filling or that block is stable according to our algorithm ...
        if (!(State.isFilling || isPortalBlockStable(block))) return

        // ... then block the physics to stop the portal from disappearing.
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun stabilizePortalContent(event: BlockFromToEvent) {
        // Fires for every liquid flow on the server: bail out before doing any lookups when this world has no gates.
        if (!GatesCollector.hasPortalsIn(event.block.world.name)) return
        if (GatesCollector.Portals[event.block] == null && GatesCollector.Portals[event.toBlock] == null) return
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun stabilizePortalContent(event: BlockPlaceEvent) {
        stabilizePortalContentBlock(event.block, event)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun stabilizePortalContent(event: PlayerBucketFillEvent) {
        stabilizePortalContentBlock(event.blockClicked, event)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun stabilizePortalContent(event: PlayerBucketEmptyEvent) {
        stabilizePortalContentBlock(event.blockClicked, event)
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun disableVanillaGates(event: PlayerPortalEvent) {
        // End portals and end gateways are not ours to cancel.
        if (event.cause != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return
        disableVanillaGates(event.from, event)
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun disableVanillaGates(event: EntityPortalEvent) {
        disableVanillaGates(event.from, event)
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun noZombiePigmanPortalSpawn(event: CreatureSpawnEvent) {
        // If a zombie pigman is spawning ...
        if (event.entityType != EntityType.ZOMBIFIED_PIGLIN) return

        // ... because of a nether portal ...
        if (event.spawnReason != CreatureSpawnEvent.SpawnReason.NETHER_PORTAL) return

        // ... near a gate ...
        val location = event.location
        if (!isGateNearby(location.block)) return

        // ... and we are blocking zombie pigman portal spawn ...
        if (CreativeGate.configuration.pigmanPortalSpawnAllowed) return

        // ... then block the spawn event.
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun useGate(event: PlayerMoveEvent) {
        // If a player ...
        val player = event.player

        // ... is moving from one block to another ...
        if (LocationComparatorUtil.isSameBlock(event)) return

        // ... and there is a gate in the new block ...
        val gate = GatesCollector.Portals[event.to]
        gate ?: return

        // ... and if the gate is intact ...
        if (!gate.isIntact) {
            // We try to detect that a gate was destroyed once it happens by listening to a few events.
            // However, there will always be cases we miss and by checking at use we catch those as well.
            // Examples could be map corruption or use of WorldEdit.
            gate.destroy()
            return
        }

        // ... and the gate has enter enabled ...
        if (!gate.enterEnabled) {
            TxtUtil.sendTranslation(player, CreativeGate.configuration.gateEnterDisabledMessage)
            return
        }

        // ... and the player is alive ...
        if (player.isDead) return

        // ... then transport the player. Travel commands run only once the player has really arrived.
        gate.transport(player) { runTravelCommands(player, gate) }
    }

    private fun runTravelCommands(player: Player, gate: Gate) {
        val config = CreativeGate.configuration
        if (config.travelCommands.isEmpty()) return

        val creator = gate.resolveCreator()
        val ownGate = creator != null && creator == player.uniqueId
        if (ownGate && !config.travelCommandsOwnGates) return

        val creatorName = creator?.let { Bukkit.getOfflinePlayer(it).name } ?: ""
        CommandHook.run(
            config.travelCommands,
            mapOf(
                "player" to player.name,
                "uuid" to player.uniqueId.toString(),
                "network" to gate.networkId,
                "gate" to gate.id.toString(),
                "creator" to creatorName,
                "creator_uuid" to (creator?.toString() ?: "")
            )
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun destroyGate(event: BlockBreakEvent) {
        destroyGate(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun destroyGate(event: EntityChangeBlockEvent) {
        destroyGate(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun destroyGate(event: EntityExplodeEvent) {
        for (block in event.blockList()) {
            destroyGate(block)
        }
    }

    // Beds, respawn anchors and similar. Before this, those explosions only destroyed a gate on its next use.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun destroyGate(event: BlockExplodeEvent) {
        for (block in event.blockList()) {
            destroyGate(block)
        }
    }

    // This one looks weird since it needs to handle beds as well
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun destroyGate(event: BlockPistonExtendEvent) {
        val blocks: MutableSet<Block> = HashSet()
        val piston = event.block
        val extension = piston.getRelative(event.direction)
        blocks.add(extension)
        for (block in event.blocks) {
            blocks.add(block)
            blocks.add(block.getRelative(event.direction))
        }
        for (block in blocks) {
            destroyGate(block)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun destroyGate(event: BlockPistonRetractEvent) {
        destroyGate(event.block.getRelative(event.direction, 1))
        if (event.isSticky) {
            destroyGate(event.block.getRelative(event.direction, 2))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun destroyGate(event: BlockFadeEvent) {
        destroyGate(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun destroyGate(event: BlockBurnEvent) {
        destroyGate(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun tools(event: PlayerInteractEvent) {
        val config = CreativeGate.configuration

        val player = event.player
        val clickedBlock = event.clickedBlock ?: return
        val currentItem = event.item ?: return
        val material = currentItem.type

        if (!isToolMaterial(material)) return

        // The event fires once per hand. When a tool is also held in the main hand, the main-hand pass handles it,
        // otherwise e.g. a mode tool in both hands would toggle twice and cancel itself out.
        val hand = event.hand ?: return
        if (hand == EquipmentSlot.OFF_HAND && isToolMaterial(player.inventory.itemInMainHand.type)) return

        if (material == config.materialCreate && event.action == Action.RIGHT_CLICK_BLOCK) {
            createGate(event, player, clickedBlock, currentItem, material)
            return
        }

        val currentGates: MutableSet<Gate> = LinkedHashSet(GatesCollector.Frames[clickedBlock])
        if (currentGates.isEmpty()) {
            GatesCollector.Portals[clickedBlock]?.let { currentGates.add(it) }
        }

        if (currentGates.isEmpty()) {
            if (isGateNearby(clickedBlock)) {
                val reqMaterial = TxtUtil.getMaterialName(material)
                val blockMaterial = TxtUtil.getMaterialName(clickedBlock.type)
                val tagResolver = arrayOf<TagResolver>(
                    Placeholder.unparsed("reqMaterial", reqMaterial),
                    Placeholder.unparsed("blockMaterial", blockMaterial)
                )
                TxtUtil.sendTranslation(player, config.noGateMessage, tagResolver)
            }
            return
        }

        if (!config.usingWater) {
            currentGates.forEach(Gate::fill)
        }

        val reqMaterial = TxtUtil.getMaterialName(material)
        val blockMaterial = TxtUtil.getMaterialName(clickedBlock.type)
        val tagResolver = arrayOf<TagResolver>(
            Placeholder.unparsed("reqmaterial", reqMaterial),
            Placeholder.unparsed("blockmaterial", blockMaterial)
        )
        TxtUtil.sendTranslation(player, config.gateUsingMessage, tagResolver)

        for (currentGate in currentGates) {
            if (currentGate.restricted) {
                if (currentGate.isCreator(player)) {
                    TxtUtil.sendTranslation(player, config.gateRestrictedCreatorMessage)
                } else {
                    TxtUtil.sendTranslation(player, config.gateRestrictedMessage)
                    return
                }
            }
            if (material == config.materialInspect) {
                TxtUtil.sendTranslation(player, config.gateInspect1Message)
                var resolver = arrayOf<TagResolver>(Placeholder.unparsed("networkid", currentGate.networkId))
                TxtUtil.sendTranslation(player, config.gateInspect2Message, resolver)
                resolver = arrayOf<TagResolver>(Placeholder.unparsed("chainsize", (currentGate.calcGatesInChainAfterThis().size + 1).toString()))
                TxtUtil.sendTranslation(player, config.gateInspect3Message, resolver)
                currentGate.resolveCreator()?.let {
                    resolver = arrayOf<TagResolver>(Placeholder.unparsed("creatorname", Bukkit.getOfflinePlayer(it).name ?: "unknown"))
                    TxtUtil.sendTranslation(player, config.gateInspect4Message, resolver)
                }
            } else if (material == config.materialSecret) {
                if (currentGate.isCreator(player)) {
                    val secret: Boolean = !currentGate.restricted
                    currentGate.restricted = secret
                    if (secret)
                        TxtUtil.sendTranslation(player, config.onlyYouCanReadMessage)
                    else
                        TxtUtil.sendTranslation(player, config.everyoneCanReadMessage)
                } else {
                    TxtUtil.sendTranslation(player, config.onlyOwnerCanChangeMessage)
                }
            } else if (material == config.materialMode) {
                currentGate.toggleMode()
                val enter: String =
                    if (currentGate.enterEnabled) config.enterEnabledMessage else config.enterDisabledMessage
                val exit: String =
                    if (currentGate.exitEnabled) config.exitEnabledMessage else config.exitDisabledMessage

                val resolver = arrayOf<TagResolver>(
                    Placeholder.unparsed("enter", enter),
                    Placeholder.unparsed("exit", exit)
                )
                TxtUtil.sendTranslation(player, config.gateNowHasMessage, resolver)
            }
        }
    }

    private fun createGate(event: PlayerInteractEvent, player: Player, clickedBlock: Block, currentItem: ItemStack, material: Material) {
        val config = CreativeGate.configuration

        // The gate limit only applies to creating gates. It used to run before every tool, so a player at the limit
        // could not even inspect or toggle the gates they already had.
        val limit = PermUtil.highestLevel(player)
        if (GatesCollector.getByCreator(player.uniqueId).size >= limit) {
            val resolver = arrayOf<TagResolver>(Placeholder.unparsed("limit", limit.toString()))
            TxtUtil.sendTranslation(player, config.maxGatesMessage, resolver)
            return
        }

        val currentItemMeta = currentItem.itemMeta
        if (currentItemMeta == null || !currentItemMeta.hasDisplayName()) {
            val reqMaterial = TxtUtil.getMaterialName(material)
            val tagResolver = arrayOf<TagResolver>(
                Placeholder.unparsed("reqMaterial", reqMaterial),
                Placeholder.unparsed("material", reqMaterial)
            )
            TxtUtil.sendTranslation(player, config.mustNameItemMessage, tagResolver)
            return
        }

        val networkId = PlainTextComponentSerializer.plainText().serialize(currentItemMeta.displayName()!!)

        // Anyone naming an item like an existing network used to join that network and become a destination of its
        // gates. With networkOwnership enabled only the owner (creator of the oldest gate) can extend a network.
        if (config.networkOwnership && !player.hasPermission("creativegates.network.bypass")) {
            val owner = GatesCollector.getNetworkOwner(networkId)
            if (owner != null && owner != player.uniqueId) {
                TxtUtil.sendTranslation(player, config.networkTakenMessage)
                return
            }
        }

        val gateFloodInfo: FloodUtil.FloodInfo? = FloodUtil.getGateFloodInfo(clickedBlock, event.blockFace)
        if (gateFloodInfo == null) {
            val tagResolver = arrayOf<TagResolver>(Placeholder.unparsed("material", TxtUtil.getMaterialName(material)))
            TxtUtil.sendTranslation(player, config.noFrameOrTooBigMessage, tagResolver)
            return
        }
        val gateOrientation = gateFloodInfo.gateOrientation
        val frameBlocks = gateFloodInfo.frameBlocks
        val portalBlocks = gateFloodInfo.portalBlocks

        val materialCounts: Map<Material, Long> = MaterialCountUtil.count(frameBlocks)
        if (!MaterialCountUtil.has(materialCounts, config.blocksRequired)) {
            val reqBlocks = MaterialCountUtil.desc(config.blocksRequired)
            val tagResolver = arrayOf<TagResolver>(Placeholder.unparsed("blocks", reqBlocks))
            TxtUtil.sendTranslation(player, config.frameMustContainMessage, tagResolver)
            return
        }

        val world = player.world.name
        val playerLocation = BlockLocation.fromLocation(player.location)
        val gateLocation = BlockLocation.fromBlock(portalBlocks.iterator().next())
        val heading = Heading(0f, gateOrientation.getExitYaw(playerLocation, gateLocation))
        val exit = Destination(world, playerLocation, heading)

        val frameCoords = frameBlocks.map { BlockLocation.fromBlock(it) }.toSet()
        val portalCoords = portalBlocks.map { BlockLocation.fromBlock(it) }.toSet()

        val newGate = Gate(networkId, exit, frameCoords, portalCoords, player.uniqueId)
        GatesCollector.register(newGate)

        // also save creator in the creators index used for limits and JSON persistence
        GatesCollector.setCreator(newGate, player.uniqueId)

        newGate.fill()
        newGate.fxKitCreate()

        CommandHook.run(
            config.createCommands,
            mapOf(
                "player" to player.name,
                "uuid" to player.uniqueId.toString(),
                "network" to networkId,
                "gate" to newGate.id.toString(),
                "gates" to GatesCollector.getByCreator(player.uniqueId).size.toString()
            )
        )

        if (config.removingCreateToolItem) {
            decreaseOne(event)
        } else if (config.removingCreateToolName) {
            decreaseOne(event)

            val newItemUnnamed = ItemStack(currentItem)
            val newItemUnnamedMeta = newItemUnnamed.itemMeta
            newItemUnnamedMeta.displayName(null)
            newItemUnnamed.itemMeta = newItemUnnamedMeta
            newItemUnnamed.amount = 1

            // A full inventory used to silently delete the returned item.
            player.inventory.addItem(newItemUnnamed).values.forEach {
                player.world.dropItemNaturally(player.location, it)
            }

            InventoryUtil.updateSoon(player)
        }
    }

    companion object {
        private fun isToolMaterial(material: Material): Boolean {
            val config = CreativeGate.configuration
            return material == config.materialInspect
                    || material == config.materialMode
                    || material == config.materialSecret
                    || material == config.materialCreate
        }

        fun isGateNearby(block: Block): Boolean {
            if (!GatesCollector.hasPortalsIn(block.world.name)) return false
            val radius = 3
            for (dx in -radius..radius) {
                for (dy in -radius..radius) {
                    for (dz in -radius..radius) {
                        if (GatesCollector.Portals[block.getRelative(dx, dy, dz)] != null) return true
                    }
                }
            }
            return false
        }

        fun isPortalBlockStable(block: Block): Boolean {
            if (VoidUtil.isVoid(block.getRelative(+0, +1, +0))) return false
            if (VoidUtil.isVoid(block.getRelative(+0, -1, +0))) return false
            if (!VoidUtil.isVoid(block.getRelative(+1, +0, +0))
                && !VoidUtil.isVoid(block.getRelative(-1, +0, +0))
            ) return true
            return !VoidUtil.isVoid(block.getRelative(+0, +0, +1))
                    && !VoidUtil.isVoid(block.getRelative(+0, +0, -1))
        }

        fun stabilizePortalContentBlock(block: Block?, cancellable: Cancellable) {
            block ?: return
            if (GatesCollector.Portals[block] == null) return
            cancellable.isCancelled = true
        }

        fun disableVanillaGates(location: Location, cancellable: Cancellable) {
            if (isGateNearby(location.block)) {
                cancellable.isCancelled = true
            }
        }

        fun destroyGate(block: Block?) {
            block ?: return
            GatesCollector.Frames[block].forEach(Gate::destroy)
        }
    }

    // Removes one item from the hand the event was fired for. The old version compared item stacks to guess which
    // slot to change, and threw after the gate was already created when it could not find it.
    private fun decreaseOne(event: PlayerInteractEvent) {
        val item = event.item ?: return
        val inventory = event.player.inventory
        val remaining = item.amount - 1
        val replacement = if (remaining <= 0) null else ItemStack(item).also { it.amount = remaining }
        if (event.hand == EquipmentSlot.OFF_HAND) inventory.setItemInOffHand(replacement)
        else inventory.setItemInMainHand(replacement)
    }
}
