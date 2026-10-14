package org.tamasoft.creativegate

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.EntityType
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
import org.bukkit.inventory.ItemStack
import org.tamasoft.creativegate.gate.Gate
import org.tamasoft.creativegate.gate.GatesCollector
import org.tamasoft.creativegate.gate.GatesCollector.getByCreator
import org.tamasoft.creativegate.teleport.BlockLocation
import org.tamasoft.creativegate.teleport.Destination
import org.tamasoft.creativegate.teleport.Heading
import org.tamasoft.creativegate.util.*
import java.util.*
import java.util.stream.Collectors


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

        // ... then transport the player.
        if (gate.transport(player)) {
            runTravelCommands(player, gate)
        }
    }

    private fun runTravelCommands(player: org.bukkit.entity.Player, gate: Gate) {
        val cfg = CreativeGate.configuration
        if (cfg.travelCommands.isEmpty()) return
        val creator = gate.resolveCreator()
        val own = creator == null || creator == player.uniqueId
        if (own && !cfg.travelCommandsOwnGates) return
        val creatorName = creator?.let { Bukkit.getOfflinePlayer(it).name } ?: ""
        CommandHook.run(cfg.travelCommands, mapOf(
            "player" to player.name,
            "uuid" to player.uniqueId.toString(),
            "network" to gate.networkId,
            "gate" to gate.id.toString(),
            "creator" to creatorName,
            "creator_uuid" to (creator?.toString() ?: "")
        ))
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

        val player = event.player
        val clickedBlock = event.clickedBlock ?: return
        val currentItem = event.item ?: return
        val material = currentItem.type

        if (material != CreativeGate.configuration.materialInspect
            && material != CreativeGate.configuration.materialMode
            && material != CreativeGate.configuration.materialSecret
            && material != CreativeGate.configuration.materialCreate
        ) {
            return
        }

        val gates = getByCreator(player.identity().uuid())
        if (gates.size >= PermUtil.highestLevel(player)) {
            val reqLevel = PermUtil.highestLevel(player)
            player.sendMessage(TxtUtil.parse("<b>${CreativeGate.configuration.maxGatesMessage} <h>$reqLevel<g>."))
            return
        }

        val currentGates: MutableSet<Gate> = GatesCollector.Frames[clickedBlock]
        if (currentGates.isEmpty()) {
            Optional.ofNullable(GatesCollector.Portals[clickedBlock]).ifPresent(currentGates::add)
        }

        if (material == CreativeGate.configuration.materialCreate && event.action == Action.RIGHT_CLICK_BLOCK) {
            val currentItemMeta = currentItem.itemMeta
            if (!currentItemMeta.hasDisplayName()) {
                val reqMaterial = TxtUtil.getMaterialName(material)

                val tagResolver: Array<TagResolver> = arrayOf<TagResolver>(Placeholder.unparsed("reqMaterial", reqMaterial))
                TxtUtil.sendTranslation(player, CreativeGate.configuration.mustNameItemMessage, tagResolver)
                return
            }

            val networkId = PlainTextComponentSerializer.plainText().serialize(currentItemMeta.displayName()!!)

            val gateFloodInfo: FloodUtil.FloodInfo? = FloodUtil.getGateFloodInfo(clickedBlock, event.blockFace)
            if (gateFloodInfo == null) {
                val tagResolver = arrayOf<TagResolver>(Placeholder.unparsed("material", TxtUtil.getMaterialName(material)))
                TxtUtil.sendTranslation(player, CreativeGate.configuration.noFrameOrTooBigMessage, tagResolver)
                return
            }
            val gateOrientation = gateFloodInfo.gateOrientation
            val frameBlocks = gateFloodInfo.frameBlocks
            val portalBlocks = gateFloodInfo.portalBlocks

            val materialCounts: Map<Material, Long> = MaterialCountUtil.count(frameBlocks)
            if (!MaterialCountUtil.has(materialCounts, CreativeGate.configuration.blocksRequired)) {
                val reqBlocks = MaterialCountUtil.desc(CreativeGate.configuration.blocksRequired)
                val tagResolver = arrayOf<TagResolver>(Placeholder.unparsed("blocks", reqBlocks))
                TxtUtil.sendTranslation(player, CreativeGate.configuration.frameMustContainMessage, tagResolver)
                return
            }

            val world = player.world.name
            val playerLocation = BlockLocation.fromLocation(player.location)
            val gateLocation = BlockLocation.fromBlock(portalBlocks.iterator().next())
            val heading = Heading(0f, gateOrientation.getExitYaw(playerLocation, gateLocation))
            val exit = Destination(world, playerLocation, heading)

            val frameCoords = frameBlocks.stream()
                .map(BlockLocation::fromBlock)
                .collect(Collectors.toSet())
            val portalCoords = portalBlocks.stream()
                .map(BlockLocation::fromBlock)
                .collect(Collectors.toSet())

            val newGate = Gate(networkId, exit, frameCoords, portalCoords, player.identity().uuid())
            GatesCollector.register(newGate)

            // also save creator in in-memory index for JSON persistence
            GatesCollector.setCreator(newGate, player.identity().uuid())

            newGate.fill()
            newGate.fxKitCreate()

            CommandHook.run(CreativeGate.configuration.createCommands, mapOf(
                "player" to player.name,
                "uuid" to player.uniqueId.toString(),
                "network" to networkId,
                "gate" to newGate.id.toString(),
                "gates" to getByCreator(player.uniqueId).size.toString()
            ))

            if (CreativeGate.configuration.removingCreateToolItem) {
                decreaseOne(event)
                val reqMaterial = TxtUtil.getMaterialName(material)
            } else if (CreativeGate.configuration.removingCreateToolName) {
                decreaseOne(event)

                val newItemUnnamed = ItemStack(currentItem)
                val newItemUnnamedMeta = newItemUnnamed.itemMeta
                newItemUnnamedMeta.displayName(null)
                newItemUnnamed.itemMeta = newItemUnnamedMeta
                newItemUnnamed.amount = 1
                player.inventory.addItem(newItemUnnamed)

                InventoryUtil.updateSoon(player)

                val reqMaterial = TxtUtil.getMaterialName(material)
            }
        } else {
            if (currentGates.isEmpty()) {
                if (isGateNearby(clickedBlock)) {
                    val reqMaterial = TxtUtil.getMaterialName(material)
                    val blockMaterial = TxtUtil.getMaterialName(clickedBlock.type)
                    val tagResolver = arrayOf<TagResolver>(
                        Placeholder.unparsed("reqMaterial", reqMaterial),
                        Placeholder.unparsed("blockMaterial", blockMaterial)
                    )
                    TxtUtil.sendTranslation(player, CreativeGate.configuration.noGateMessage, tagResolver)
                    return
                } else {
                    return
                }
            }

            if (!CreativeGate.configuration.usingWater) {
                currentGates.forEach(Gate::fill)
            }

            val reqMaterial = TxtUtil.getMaterialName(material)
            val blockMaterial = TxtUtil.getMaterialName(clickedBlock.type)
            val tagResolver = arrayOf<TagResolver>(
                Placeholder.unparsed("reqmaterial", reqMaterial),
                Placeholder.unparsed("blockmaterial", blockMaterial)
            )
            TxtUtil.sendTranslation(player, CreativeGate.configuration.gateUsingMessage, tagResolver)

            for (currentGate in currentGates) {
                if (currentGate.restricted) {
                    if (currentGate.isCreator(player)) {
                        TxtUtil.sendTranslation(player, CreativeGate.configuration.gateRestrictedCreatorMessage)
                    } else {
                        TxtUtil.sendTranslation(player, CreativeGate.configuration.gateRestrictedMessage)
                        return
                    }
                }
                if (material == CreativeGate.configuration.materialInspect) {
                    TxtUtil.sendTranslation(player, CreativeGate.configuration.gateInspect1Message)
                    var tagResolver = arrayOf<TagResolver>(Placeholder.unparsed("networkid", currentGate.networkId))
                    TxtUtil.sendTranslation(player, CreativeGate.configuration.gateInspect2Message, tagResolver)
                    tagResolver = arrayOf<TagResolver>(Placeholder.unparsed("chainsize", (currentGate.calcGatesInChainAfterThis().size + 1).toString()))
                    TxtUtil.sendTranslation(player, CreativeGate.configuration.gateInspect3Message, tagResolver)
                    GatesCollector.getCreator(currentGate)?.let {
                        tagResolver = arrayOf<TagResolver>(Placeholder.unparsed("creatorname", Bukkit.getOfflinePlayer(it).name ?: "unknown"))
                        TxtUtil.sendTranslation(player, CreativeGate.configuration.gateInspect4Message, tagResolver)
                    }
                } else if (material == CreativeGate.configuration.materialSecret) {
                    val creator = currentGate.isCreator(player)
                    if (creator) {
                        val secret: Boolean = !currentGate.restricted
                        currentGate.restricted = secret
                        if (secret)
                            TxtUtil.sendTranslation(player, CreativeGate.configuration.onlyYouCanReadMessage)
                        else
                            TxtUtil.sendTranslation(player, CreativeGate.configuration.everyoneCanReadMessage)
                    } else {
                        TxtUtil.sendTranslation(player, CreativeGate.configuration.onlyOwnerCanChangeMessage)
                    }
                } else if (material == CreativeGate.configuration.materialMode) {
                    currentGate.toggleMode()
                    val enter: String =
                        if (currentGate.enterEnabled) CreativeGate.configuration.enterEnabledMessage else CreativeGate.configuration.enterDisabledMessage
                    val exit: String =
                        if (currentGate.exitEnabled) CreativeGate.configuration.exitEnabledMessage else CreativeGate.configuration.exitDisabledMessage

                    var tagResolver = arrayOf<TagResolver>(
                        Placeholder.unparsed("enter", enter),
                        Placeholder.unparsed("exit", exit)
                    )
                    TxtUtil.sendTranslation(player, CreativeGate.configuration.gateNowHasMessage, tagResolver)
                }
            }
        }
    }

    companion object {
        fun isGateNearby(block: Block): Boolean {
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

    // Note we cannot use event.getHand() because it is too new
    private fun decreaseOne(event: PlayerInteractEvent) {
        val currentItem = event.item
        val player = event.player
        val newItem = ItemStack(currentItem!!)
        newItem.amount = newItem.amount - 1
        val weapon = currentItem == InventoryUtil.getWeapon(player)
        val shield = currentItem == InventoryUtil.getShield(player)
        if (weapon) InventoryUtil.setWeapon(player, newItem)
        else if (shield) InventoryUtil.setShield(player, newItem)
        else {
            // This is not to be expected, but it is an attempt future-proofing, in case the API changes.
            var success = false
            val it = player.inventory.iterator()
            while (it.hasNext()) {
                val itemStack = it.next()

                // If the item stack is equal to the old item stack, then they are probably the same.
                if (currentItem != itemStack) {
                    continue
                }

                // So just set
                it.set(newItem)
                success = true
                break
            }
            if (!success) throw RuntimeException()
        }
    }
}