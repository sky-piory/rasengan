
package org.rasengan

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.trait.trait.Equipment
import net.citizensnpcs.trait.SkinTrait
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Citizens가 활성화된 경우에만 reflection으로 로드되는 구현체
 */
class CitizensShadowController(
    private val plugin: JavaPlugin
) : ShadowCloneController, Listener {

    private val shadowNpcs = mutableMapOf<UUID, MutableList<NPC>>()
    private val shadowTasks = mutableMapOf<UUID, BukkitTask>()
    private val shadowTargets = mutableMapOf<Int, UUID>()
    private val shadowAttackTimes = mutableMapOf<Int, Long>()

    // 플레이어가 공격했을 때의 방어 상태
    private data class Threat(
        val attackerId: UUID,
        val location: Location,
        val expiresAt: Long
    )

    private val threats = mutableMapOf<UUID, Threat>()
    private val warningTimes = mutableMapOf<UUID, Long>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    override fun summon(owner: Player, cloneCount: Int): Boolean {
        val ownerId = owner.uniqueId
        remove(ownerId)

        val created = mutableListOf<NPC>()
        val registry = CitizensAPI.getTemporaryNPCRegistry()

        for (index in 0 until cloneCount) {
            val npc = registry.createNPC(
                EntityType.PLAYER,
                "분신${index + 1}"
            )

            npc.setProtected(true)
            npc.setUseMinecraftAI(false)
            npc.getOrAddTrait(SkinTrait::class.java)
                .setSkinPersistent(owner)

            val equipment = npc.getOrAddTrait(Equipment::class.java)
            val inventory = owner.inventory

            equipment.set(
                Equipment.EquipmentSlot.HAND,
                inventory.itemInMainHand.clone()
            )
            equipment.set(
                Equipment.EquipmentSlot.OFF_HAND,
                inventory.itemInOffHand.clone()
            )
            equipment.set(
                Equipment.EquipmentSlot.HELMET,
                inventory.helmet?.clone() ?: ItemStack(Material.AIR)
            )
            equipment.set(
                Equipment.EquipmentSlot.CHESTPLATE,
                inventory.chestplate?.clone() ?: ItemStack(Material.AIR)
            )
            equipment.set(
                Equipment.EquipmentSlot.LEGGINGS,
                inventory.leggings?.clone() ?: ItemStack(Material.AIR)
            )
            equipment.set(
                Equipment.EquipmentSlot.BOOTS,
                inventory.boots?.clone() ?: ItemStack(Material.AIR)
            )

            val angle = 2.0 * PI * index / cloneCount

            val spawnLocation = owner.location.clone().add(
                cos(angle) * 1.5,
                0.0,
                sin(angle) * 1.5
            )

            spawnLocation.yaw = owner.location.yaw

            if (npc.spawn(spawnLocation)) {
                created.add(npc)
            } else {
                npc.destroy()
            }
        }

        if (created.isEmpty()) return false

        shadowNpcs[ownerId] = created
        startControl(owner, created)

        return true
    }

    /**
     * 주인이 다른 플레이어에게 공격받았는지 감지
     */
    @EventHandler(
        priority = EventPriority.MONITOR,
        ignoreCancelled = true
    )
    fun onOwnerAttacked(event: EntityDamageByEntityEvent) {
        val owner = event.entity as? Player ?: return

        // 직접 공격 또는 플레이어가 발사한 투사체
        val attacker = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        } ?: return

        if (attacker.uniqueId == owner.uniqueId) return

        // 분신이 없는 플레이어는 무시
        if (shadowNpcs[owner.uniqueId].isNullOrEmpty()) return

        val now = System.currentTimeMillis()

        // 마지막 공격 위치와 방어 상태 갱신
        threats[owner.uniqueId] = Threat(
            attacker.uniqueId,
            attacker.location.clone(),
            now + 5_000L
        )

        // 경고 메시지 도배 방지
        val previousWarning = warningTimes[owner.uniqueId] ?: 0L

        if (now - previousWarning >= 1_000L) {
            owner.sendMessage(
                "§c[분신] 다른 플레이어의 공격을 감지했습니다! 주인을 보호합니다."
            )
            warningTimes[owner.uniqueId] = now
        }
    }

    private fun startControl(
        owner: Player,
        npcs: List<NPC>
    ) {
        val ownerId = owner.uniqueId
        val expiresAt = System.currentTimeMillis() + 30_000L

        val task = object : BukkitRunnable() {

            override fun run() {

                if (
                    !owner.isOnline ||
                    owner.isDead ||
                    System.currentTimeMillis() >= expiresAt
                ) {
                    remove(
                        ownerId,
                        showEffect = owner.isOnline && !owner.isDead
                    )
                    cancel()
                    return
                }

                val now = System.currentTimeMillis()
                var threat = threats[ownerId]

                // 방어 시간이 끝나면 일반 행동으로 복귀
                if (threat != null && now >= threat.expiresAt) {
                    threats.remove(ownerId)
                    threat = null
                }

                for (npc in npcs) {
                    if (!npc.isSpawned) continue

                    val cloneEntity = npc.entity as? LivingEntity ?: continue
                    val navigator = npc.navigator

                    if (cloneEntity.world != owner.world) {
                        npc.teleport(
                            owner.location,
                            PlayerTeleportEvent.TeleportCause.PLUGIN
                        )
                        shadowTargets.remove(npc.id)
                        continue
                    }

                    // ==================================
                    // 플레이어 공격 감지 후 방어 행동
                    // ==================================

                    if (threat != null) {

                        val threatLocation = threat.location

                        // 주인 주변으로 이동
                        val distanceToOwner =
                            cloneEntity.location.distanceSquared(owner.location)

                        if (distanceToOwner > 9.0) {
                            if (
                                shadowTargets[npc.id] != ownerId ||
                                !navigator.isNavigating
                            ) {
                                navigator.setTarget(owner, false)
                                shadowTargets[npc.id] = ownerId
                            }
                        } else {
                            navigator.cancelNavigation()
                            shadowTargets.remove(npc.id)

                            // 공격자가 같은 월드에 있으면 해당 방향을 바라봄
                            if (threatLocation.world == owner.world) {
                                npc.faceLocation(threatLocation)
                            }
                        }

                        // 방어 상태에서는 일반 몬스터 대응을 잠시 중단
                        continue
                    }

                    // ==================================
                    // 기존 몬스터 대응 기능
                    // ==================================

                    val target = owner.world.getNearbyEntities(
                        cloneEntity.location,
                        12.0,
                        6.0,
                        12.0
                    )
                        .filterIsInstance<Monster>()
                        .filter {
                            it.isValid && !it.isDead
                        }
                        .filter {
                            !CitizensAPI.getNPCRegistry().isNPC(it)
                        }
                        .minByOrNull {
                            it.location.distanceSquared(cloneEntity.location)
                        }

                    if (target != null) {
                        val targetId = target.uniqueId

                        val closeEnough =
                            cloneEntity.location.distanceSquared(
                                target.location
                            ) <= 6.25

                        if (closeEnough) {
                            navigator.cancelNavigation()
                            shadowTargets[npc.id] = targetId
                            npc.faceLocation(target.location)

                            val lastAttack =
                                shadowAttackTimes[npc.id] ?: 0L

                            if (now - lastAttack >= 1_200L) {
                                target.damage(6.0, cloneEntity)

                                target.world.spawnParticle(
                                    Particle.CRIT,
                                    target.location.clone().add(0.0, 1.0, 0.0),
                                    5,
                                    0.25,
                                    0.35,
                                    0.25,
                                    0.05
                                )

                                target.world.playSound(
                                    target.location,
                                    Sound.ENTITY_PLAYER_ATTACK_STRONG,
                                    0.7f,
                                    1.2f
                                )

                                shadowAttackTimes[npc.id] = now
                            }
                        } else if (
                            shadowTargets[npc.id] != targetId ||
                            !navigator.isNavigating
                        ) {
                            navigator.setTarget(target, false)
                            shadowTargets[npc.id] = targetId
                        }
                    } else {
                        // ==================================
                        // 기존 주인 따라다니기
                        // ==================================

                        val distanceSquared =
                            cloneEntity.location.distanceSquared(owner.location)

                        if (distanceSquared > 16.0) {
                            if (
                                shadowTargets[npc.id] != ownerId ||
                                !navigator.isNavigating
                            ) {
                                navigator.setTarget(owner, false)
                                shadowTargets[npc.id] = ownerId
                            }
                        } else {
                            navigator.cancelNavigation()
                            shadowTargets.remove(npc.id)
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 10L)

        shadowTasks[ownerId] = task
    }

    override fun remove(
        ownerId: UUID,
        showEffect: Boolean
    ) {
        shadowTasks.remove(ownerId)?.cancel()

        val npcs = shadowNpcs.remove(ownerId).orEmpty()

        for (npc in npcs) {
            val location: Location? = npc.entity?.location

            if (showEffect && location != null) {
                location.world?.spawnParticle(
                    Particle.CLOUD,
                    location.clone().add(0.0, 1.0, 0.0),
                    18,
                    0.35,
                    0.5,
                    0.35,
                    0.03
                )

                location.world?.playSound(
                    location,
                    Sound.ENTITY_PLAYER_TELEPORT,
                    0.6f,
                    0.8f
                )
            }

            shadowTargets.remove(npc.id)
            shadowAttackTimes.remove(npc.id)

            runCatching {
                npc.navigator.cancelNavigation()
                npc.destroy()
            }
        }

        threats.remove(ownerId)
        warningTimes.remove(ownerId)
    }

    override fun shutdown() {
        shadowTasks.values.forEach {
            it.cancel()
        }

        shadowNpcs.values.flatten().forEach { npc ->
            runCatching {
                npc.destroy()
            }
        }

        shadowNpcs.clear()
        shadowTasks.clear()
        shadowTargets.clear()
        shadowAttackTimes.clear()
        threats.clear()
        warningTimes.clear()
    }
}
