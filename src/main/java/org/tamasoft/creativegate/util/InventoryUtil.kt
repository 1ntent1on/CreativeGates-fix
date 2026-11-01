package org.tamasoft.creativegate.util

import org.bukkit.Material
import org.bukkit.entity.HumanEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.scheduler.BukkitRunnable
import org.tamasoft.creativegate.CreativeGate

object InventoryUtil {
    private const val SIZE_PLAYER_STORAGE = 36
    private const val SIZE_PLAYER_ARMOR = 4

    // 0 --> 36 (35 exclusive)
    private const val INDEX_PLAYER_STORAGE_FROM = 0
    private const val INDEX_PLAYER_STORAGE_TO = INDEX_PLAYER_STORAGE_FROM + SIZE_PLAYER_STORAGE

    // 36 --> 40 (39 exclusive)
    private const val INDEX_PLAYER_ARMOR_FROM = INDEX_PLAYER_STORAGE_TO
    private const val INDEX_PLAYER_ARMOR_TO = INDEX_PLAYER_ARMOR_FROM + SIZE_PLAYER_ARMOR

    // 40 --> 41 (40 exclusive)
    private const val INDEX_PLAYER_EXTRA_FROM = INDEX_PLAYER_ARMOR_TO

    // 40
    private const val INDEX_PLAYER_SHIELD = INDEX_PLAYER_EXTRA_FROM

    private fun asPlayerInventory(inventory: Inventory?): PlayerInventory? {
        return if (inventory is PlayerInventory) inventory else null
    }

    fun getWeapon(human: HumanEntity): ItemStack? {
        val ret: ItemStack = human.inventory.itemInMainHand
        return if (ret.type == Material.AIR) null else ret
    }

    fun setWeapon(human: HumanEntity?, weapon: ItemStack?) {
        if (human == null) return
        human.inventory.setItemInMainHand(weapon)
    }

    fun getShield(playerInventory: PlayerInventory): ItemStack? {
        val contents = playerInventory.contents
        if (contents.size <= INDEX_PLAYER_SHIELD) return null
        val ret = contents[INDEX_PLAYER_SHIELD]
        return if (ret?.type == Material.AIR) null else ret
    }

    fun setShield(inventory: Inventory, shield: ItemStack?) {
        val playerInventory = asPlayerInventory(inventory) ?: return
        val contents = playerInventory.contents
        if (contents.size <= INDEX_PLAYER_SHIELD) return
        inventory.setItem(INDEX_PLAYER_SHIELD, shield)
    }

    fun getShield(human: HumanEntity): ItemStack? {
        return getShield(human.inventory)
    }

    fun setShield(human: HumanEntity?, shield: ItemStack?) {
        if (human == null) return
        setShield(human.inventory, shield)
    }

    fun updateSoon(player: Player) {
        val plugin = CreativeGate.instance ?: return
        object : BukkitRunnable() {
            override fun run() {
                player.updateInventory()
            }
        }.runTask(plugin)
    }
}