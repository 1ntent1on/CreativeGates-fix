package org.tamasoft.creativegate

import org.bukkit.Material
import java.util.*

class Config {
    val teleportationSoundActive = true
    val usingWater = false
    val pigmanPortalSpawnAllowed = true
    val maxArea = 200
    val removingCreateToolName = true
    val removingCreateToolItem = false
    val blocksRequired: Map<Material, Long> = mapOf(Material.EMERALD_BLOCK to 2)
    val materialCreate: Material = Material.CLOCK
    val materialInspect = Material.BLAZE_POWDER
    val materialSecret = Material.MAGMA_CREAM
    val materialMode = Material.BLAZE_ROD
    val voidMaterials: Set<Material> = EnumSet.of(Material.AIR)

    // Console commands run when a gate is created. Placeholders: {player} {uuid} {network} {gate} {gates}
    val createCommands: List<String> = emptyList()

    // Console commands run after a player successfully travels through a gate.
    // Placeholders: {player} {uuid} {network} {gate} {creator} {creator_uuid}
    val travelCommands: List<String> = emptyList()

    // Run travelCommands also when the traveller is the creator of the gate they enter.
    val travelCommandsOwnGates: Boolean = false

    // When true, only the creator of the oldest gate in a network (or players with
    // creativegates.network.bypass) can add further gates to that network.
    val networkOwnership: Boolean = false


    // Translations
    val maxGatesMessage : String = "You have reached the maximum number of gates you can create <h><limit><g>."
    val networkTakenMessage: String = "<b>This network belongs to another player. Use a different network name."
    val noGateMessage: String = "You use the <reqmaterial> on the <blockmaterial> but there seem to be no gate."
    val gateRestrictedCreatorMessage: String = "<i>... the gate is restricted but you are the creator ..."
    val gateRestrictedMessage: String = "<b>... the gate is restricted and you are not the creator."

    val gateInspect1Message: String = "<i>Some gate inscriptions are revealed:"
    val gateInspect2Message: String = "<i>Network ID: <aqua><networkid>"
    val gateInspect3Message: String = "<k>gates: <v><chainsize>"
    val gateInspect4Message: String = "<i>Created by <aqua><creatorname>"

    val onlyYouCanReadMessage: String = "<h>Only you <i>can read the gate inscriptions now."
    val everyoneCanReadMessage: String = "<h>Everyone <i>can read the gate inscriptions now."

    val onlyOwnerCanChangeMessage: String = "<i>It seems <h>only the gate creator <i>can change inscription readability."

    val gateEnterDisabledMessage: String = "<i>This gate has enter disabled."
    val mustNameItemMessage: String = "<b>You must name the <material> before creating a gate with it."
    val noFrameOrTooBigMessage: String = "<b>There is no frame for the gate, or it's too big."
    val frameMustContainMessage: String = "<b>The frame must contain <blocks><b>."
        val gateUsingMessage: String = "<i>You use the <reqmaterial> on the <blockmaterial>..."
    val gateNowHasMessage: String = "<i>The gate now has <enter> <i>and <exit><i>."
    val enterEnabledMessage: String = "<g>enter enabled"
    val enterDisabledMessage: String = "<b>enter disabled"
    val exitEnabledMessage: String = "<g>exit enabled"
    val exitDisabledMessage: String = "<b>exit disabled"
    val gateNoDestinationMessage: String = "<i>This gate does not seem to lead anywhere."
}