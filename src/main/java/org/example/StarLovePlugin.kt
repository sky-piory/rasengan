package org.example

import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin

class StarLovePlugin : JavaPlugin(), Listener {

    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)
        logger.info("StarLovePlugin이 활성화되었습니다!")
    }

    override fun onDisable() {
        logger.info("StarLovePlugin이 비활성화되었습니다.")
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return

        val action = event.action
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            val player = event.player

            if (player.inventory.itemInMainHand.type == Material.NETHER_STAR) {
                val message = "${ChatColor.LIGHT_PURPLE}${ChatColor.BOLD}♥ 벼리야 사랑해 ♥"
                player.sendMessage(message)

                val location = player.location.add(0.0, 1.0, 0.0)
                player.world.spawnParticle(Particle.HEART, location, 10, 0.5, 0.5, 0.5)

                player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f)
            }
        }
    }
}