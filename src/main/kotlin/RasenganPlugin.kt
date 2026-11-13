package org.rasengan

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.OfflinePlayer
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.TileState
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class RasenganPlugin : JavaPlugin(), Listener {

    private val defaultMaxChakra = 100
    private val chargeTicks = 40
    private val preparedTimeoutTicks = 200

    private lateinit var bookKey: NamespacedKey
    private lateinit var skillKey: NamespacedKey
    private lateinit var shurikenKey: NamespacedKey

    private val chakra = mutableMapOf<UUID, Int>()
    private val cooldowns = mutableMapOf<String, Long>()

    private val chargingTasks = mutableMapOf<UUID, BukkitTask>()
    private val preparedTasks = mutableMapOf<UUID, BukkitTask>()

    private val chargingSkills = mutableMapOf<UUID, Skill>()
    private val preparedSkills = mutableMapOf<UUID, Skill>()

    private val shurikenLastThrow = mutableMapOf<UUID, Long>()

    // 그림자 분신 관리
    private val shadowCooldowns = mutableMapOf<String, Long>()
    // Citizens 구현체는 Citizens가 활성화된 경우에만 reflection으로 로드
    private var shadowController: ShadowCloneController? = null

    // 차크라 액션바가 현재 표시 중인 플레이어
    private val chakraActionbarPlayers = mutableSetOf<UUID>()

    private enum class ShadowSkill(
        val id: String,
        val displayName: String,
        val material: Material,
        val chakraCost: Int,
        val cooldownMillis: Long,
        val cloneCount: Int
    ) {
        SHADOW(
            "shadow",
            "그림자 분신술",
            Material.AMETHYST_SHARD,
            20,
            5_000L,
            1
        ),
        MULTI(
            "multi",
            "다중 그림자 분신술",
            Material.ECHO_SHARD,
            60,
            15_000L,
            5
        );

        companion object {
            fun fromId(id: String): ShadowSkill? =
                entries.firstOrNull { it.id == id }
        }
    }

    private enum class Skill(
        val id: String,
        val displayName: String,
        val material: Material,
        val chakraCost: Int,
        val cooldownMillis: Long,
        val directDamage: Double,
        val explosionDamage: Double,
        val damageRadius: Int,
        val blockRadius: Int,
        val projectileSpeed: Double,
        val maxFlightTicks: Int
    ) {
        RASENGAN(
            "rasengan",
            "나선환",
            Material.HEART_OF_THE_SEA,
            40,
            3_000L,
            30.0,
            10.0,
            3,
            3,
            1.2,
            40
        ),

        GIANT(
            "giant",
            "초대옥 나선환",
            Material.FIRE_CHARGE,
            100,
            15_000L,
            100.0,
            40.0,
            10,
            7,
            0.85,
            50
        ),

        RASENSHURIKEN(
            "rasenshuriken",
            "나선수리검",
            Material.NETHER_STAR,
            70,
            10_000L,
            60.0,
            30.0,
            6,
            0,
            1.4,
            55
        );

        companion object {
            fun fromId(id: String): Skill? {
                return entries.firstOrNull { it.id == id }
            }
        }
    }

    // ==================================================
    // 플러그인 시작 / 종료
    // ==================================================

    override fun onEnable() {

        bookKey = NamespacedKey(this, "naruto_book")
        skillKey = NamespacedKey(this, "naruto_skill")
        shurikenKey = NamespacedKey(this, "naruto_shuriken")

        server.pluginManager.registerEvents(this, this)

        // Citizens가 없으면 그림자 분신 기능만 비활성화하고 나머지는 정상 실행
        if (server.pluginManager.isPluginEnabled("Citizens")) {
            try {
                val implementation = Class.forName("org.rasengan.CitizensShadowController")
                shadowController = implementation
                    .getConstructor(JavaPlugin::class.java)
                    .newInstance(this) as ShadowCloneController
                logger.info("Citizens 연동 활성화 완료")
            } catch (exception: Exception) {
                shadowController = null
                logger.warning("Citizens 연동을 초기화하지 못했습니다. 그림자 분신 기능은 비활성화됩니다.")
                logger.warning(exception.message ?: exception.javaClass.simpleName)
            }
        } else {
            logger.info("Citizens가 없어 그림자 분신 기능만 비활성화됩니다.")
        }

        server.onlinePlayers.forEach {
            loadPlayerData(it)
        }

        // 차크라 자동 회복: 초당 2
        server.scheduler.runTaskTimer(this, Runnable {

            server.onlinePlayers.forEach { player ->

                val max = getMaxChakra(player.uniqueId)
                val current = getCurrentChakra(player)

                chakra[player.uniqueId] =
                    (current + 2).coerceAtMost(max)
            }

        }, 20L, 20L)

        // 차크라 표시: 5틱마다 확인
        server.scheduler.runTaskTimer(this, Runnable {

            server.onlinePlayers.forEach { player ->

                val heldItem = player.inventory.itemInMainHand

                val isNarutoItem = isChakraDisplayItem(heldItem)

                if (isNarutoItem) {

                    val max = getMaxChakra(player.uniqueId)
                    val current = getCurrentChakra(player)

                    player.sendActionBar(
                        Component.text(
                            "차크라: $current/$max",
                            NamedTextColor.AQUA
                        )
                    )

                    chakraActionbarPlayers.add(player.uniqueId)

                } else {

                    // 이전에 차크라를 표시한 경우에만 지우기
                    if (chakraActionbarPlayers.remove(player.uniqueId)) {
                        player.sendActionBar(Component.empty())
                    }
                }
            }

        }, 0L, 5L)

        logger.info("RasenganPlugin 활성화 완료!")
    }

    override fun onDisable() {

        chargingTasks.values.forEach { it.cancel() }
        preparedTasks.values.forEach { it.cancel() }
        shadowController?.shutdown()
        shadowController = null

        chargingTasks.clear()
        preparedTasks.clear()

        server.onlinePlayers.forEach {
            savePlayerData(it)
        }

        saveConfig()
    }

    // ==================================================
    // 차크라 표시 대상 확인
    // ==================================================

    private fun isChakraDisplayItem(item: ItemStack): Boolean {

        if (item.type.isAir) return false

        val pdc = item.itemMeta.persistentDataContainer

        return pdc.has(skillKey, PersistentDataType.STRING) ||
                pdc.has(shurikenKey, PersistentDataType.BYTE)
    }

    // ==================================================
    // 차크라 시스템
    // ==================================================

    private fun maxPath(uuid: UUID): String {
        return "player-data.$uuid.max"
    }

    private fun currentPath(uuid: UUID): String {
        return "player-data.$uuid.current"
    }

    private fun getMaxChakra(uuid: UUID): Int {
        return config.getInt(
            maxPath(uuid),
            defaultMaxChakra
        ).coerceAtLeast(1)
    }

    private fun getCurrentChakra(player: Player): Int {

        val uuid = player.uniqueId
        val max = getMaxChakra(uuid)

        return chakra.getOrPut(uuid) { max }.coerceIn(0, max)
    }

    private fun loadPlayerData(player: Player) {

        val uuid = player.uniqueId
        val max = getMaxChakra(uuid)

        val saved = config.getInt(
            currentPath(uuid),
            max
        )

        chakra[uuid] = saved.coerceIn(0, max)
    }

    private fun savePlayerData(player: Player) {

        val uuid = player.uniqueId
        val max = getMaxChakra(uuid)

        val current = chakra.getOrDefault(
            uuid,
            max
        ).coerceIn(0, max)

        config.set(maxPath(uuid), max)
        config.set(currentPath(uuid), current)
    }

    private fun refillChakra(player: Player) {

        val max = getMaxChakra(player.uniqueId)

        chakra[player.uniqueId] = max

        savePlayerData(player)
        saveConfig()

        player.sendMessage(
            Component.text(
                "차크라가 $max/$max 으로 회복되었습니다!",
                NamedTextColor.AQUA
            )
        )
    }

    // ==================================================
    // 명령어
    // ==================================================

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {

        // /나루토 선택서
        if (command.name.equals("naruto", true)) {

            val player = sender as? Player

            if (player == null) {
                sender.sendMessage("플레이어만 사용할 수 있습니다.")
                return true
            }

            giveSelectionBook(player)
            return true
        }

        // /narutaselect
        if (command.name.equals("narutaselect", true)) {

            val player = sender as? Player

            if (player == null) {
                sender.sendMessage("플레이어만 사용할 수 있습니다.")
                return true
            }

            val selected = args.firstOrNull()?.lowercase() ?: ""

            if (selected == "shuriken") {
                giveShuriken(player, 1)
                return true
            }

            val shadowSkill = ShadowSkill.fromId(selected)
            if (shadowSkill != null) {
                giveShadowSkillItem(player, shadowSkill)
                return true
            }

            val skill = Skill.fromId(selected)

            if (skill == null) {

                player.sendMessage(
                    Component.text(
                        "사용법: /narutaselect rasengan, giant, rasenshuriken, shuriken, shadow, multi",
                        NamedTextColor.YELLOW
                    )
                )

            } else {
                giveSkillItem(player, skill)
            }

            return true
        }

        // /chakra
        if (command.name.equals("chakra", true)) {

            handleChakraCommand(sender, label, args)
            return true
        }

        return false
    }

    private fun handleChakraCommand(
        sender: CommandSender,
        label: String,
        args: Array<out String>
    ) {

        // /차크라: 자신의 차크라 회복
        if (label.equals("차크라", true)) {

            val player = sender as? Player

            if (player == null) {
                sender.sendMessage("플레이어만 사용할 수 있습니다.")
            } else {
                refillChakra(player)
            }

            return
        }

        // /chakra max 플레이어 숫자
        if (
            args.firstOrNull()?.equals("max", true) == true
        ) {

            if (!isAdmin(sender)) {
                sender.sendMessage("관리자만 사용할 수 있습니다.")
                return
            }

            if (args.size < 3) {
                sender.sendMessage(
                    "사용법: /chakra max <플레이어> <최대치>"
                )
                return
            }

            val targetName = args[1]
            val newMax = args[2].toIntOrNull()

            if (newMax == null || newMax !in 1..1_000_000) {
                sender.sendMessage(
                    "최대 차크라는 1~1,000,000 사이의 숫자여야 합니다."
                )
                return
            }

            val onlineTarget = server.getPlayerExact(targetName)

            val offlineTarget: OfflinePlayer =
                onlineTarget ?: server.getOfflinePlayer(targetName)

            if (
                onlineTarget == null &&
                !offlineTarget.hasPlayedBefore()
            ) {
                sender.sendMessage("해당 플레이어를 찾을 수 없습니다.")
                return
            }

            val uuid = offlineTarget.uniqueId

            val oldCurrent = onlineTarget?.let {
                getCurrentChakra(it)
            } ?: if (config.contains(currentPath(uuid))) {
                config.getInt(currentPath(uuid))
            } else {
                newMax
            }

            config.set(maxPath(uuid), newMax)

            val adjustedCurrent = oldCurrent.coerceIn(0, newMax)

            config.set(currentPath(uuid), adjustedCurrent)

            if (onlineTarget != null) {
                chakra[uuid] = adjustedCurrent
            }

            saveConfig()

            sender.sendMessage(
                Component.text(
                    "${targetName}의 최대 차크라가 $newMax 으로 변경되었습니다.",
                    NamedTextColor.GREEN
                )
            )

            if (oldCurrent > newMax) {
                sender.sendMessage(
                    "현재 차크라도 새 최대치에 맞춰 조정했습니다."
                )
            }

            return
        }

        // /chakra restore
        if (
            args.firstOrNull()?.equals("restore", true) == true
        ) {

            val player = sender as? Player

            if (player == null) {
                sender.sendMessage("플레이어만 사용할 수 있습니다.")
            } else {
                refillChakra(player)
            }

            return
        }

        // /chakra info [플레이어]
        if (
            args.isEmpty() ||
            args[0].equals("info", true)
        ) {

            val targetName = args.getOrNull(1)

            if (targetName == null) {

                val player = sender as? Player

                if (player == null) {
                    sender.sendMessage(
                        "사용법: /chakra info <플레이어>"
                    )
                    return
                }

                sendChakraInfo(
                    sender,
                    player.name,
                    player.uniqueId,
                    getCurrentChakra(player)
                )

                return
            }

            if (!isAdmin(sender)) {
                sender.sendMessage(
                    "다른 플레이어의 차크라를 확인하려면 관리자 권한이 필요합니다."
                )
                return
            }

            val onlineTarget = server.getPlayerExact(targetName)

            val offlineTarget =
                onlineTarget ?: server.getOfflinePlayer(targetName)

            if (
                onlineTarget == null &&
                !offlineTarget.hasPlayedBefore()
            ) {
                sender.sendMessage("해당 플레이어를 찾을 수 없습니다.")
                return
            }

            val uuid = offlineTarget.uniqueId
            val max = getMaxChakra(uuid)

            val current = onlineTarget?.let {
                getCurrentChakra(it)
            } ?: config.getInt(currentPath(uuid), max)
                .coerceIn(0, max)

            sendChakraInfo(
                sender,
                targetName,
                uuid,
                current
            )

            return
        }

        sender.sendMessage(
            "명령어: /chakra, /chakra info [플레이어], /chakra max <플레이어> <최대치>, /chakra restore"
        )
    }

    private fun sendChakraInfo(
        sender: CommandSender,
        name: String,
        uuid: UUID,
        current: Int
    ) {

        val max = getMaxChakra(uuid)

        sender.sendMessage(
            Component.text(
                "$name 차크라: $current/$max",
                NamedTextColor.AQUA
            )
        )
    }

    private fun isAdmin(sender: CommandSender): Boolean {
        return sender !is Player ||
                sender.isOp ||
                sender.hasPermission("rasengan.admin")
    }

    // ==================================================
    // 아이템 지급
    // ==================================================

    private fun giveSelectionBook(player: Player) {

        val book = ItemStack(Material.BOOK)
        val meta = book.itemMeta

        meta.displayName(
            Component.text(
                "나루토 기술 선택서",
                NamedTextColor.GOLD
            )
        )

        meta.lore(
            listOf(
                Component.text(
                    "우클릭하여 기술을 선택하세요.",
                    NamedTextColor.GRAY
                )
            )
        )

        meta.persistentDataContainer.set(
            bookKey,
            PersistentDataType.BYTE,
            1.toByte()
        )

        book.itemMeta = meta

        giveItem(player, book)
    }

    private fun giveSkillItem(
        player: Player,
        skill: Skill
    ) {

        val item = ItemStack(skill.material)
        val meta = item.itemMeta

        meta.displayName(
            Component.text(
                skill.displayName,
                NamedTextColor.AQUA
            )
        )

        meta.lore(
            listOf(
                Component.text(
                    "우클릭: 충전 / 다시 우클릭: 발사",
                    NamedTextColor.GRAY
                ),
                Component.text(
                    "차크라 ${skill.chakraCost} / 쿨타임 ${skill.cooldownMillis / 1000}초",
                    NamedTextColor.YELLOW
                )
            )
        )

        meta.persistentDataContainer.set(
            skillKey,
            PersistentDataType.STRING,
            skill.id
        )

        item.itemMeta = meta

        giveItem(player, item)

        player.sendMessage(
            Component.text(
                "${skill.displayName} 지급 완료!",
                NamedTextColor.GREEN
            )
        )
    }

    private fun giveShadowSkillItem(player: Player, skill: ShadowSkill) {
        val item = ItemStack(skill.material)
        val meta = item.itemMeta

        meta.displayName(
            Component.text(skill.displayName, NamedTextColor.LIGHT_PURPLE)
        )
        meta.lore(
            listOf(
                Component.text("우클릭하여 분신을 소환합니다.", NamedTextColor.GRAY),
                Component.text("분신 ${skill.cloneCount}명 / 지속시간 30초", NamedTextColor.YELLOW),
                Component.text("차크라 ${skill.chakraCost} / 쿨타임 ${skill.cooldownMillis / 1000}초", NamedTextColor.AQUA)
            )
        )
        meta.persistentDataContainer.set(
            skillKey,
            PersistentDataType.STRING,
            skill.id
        )
        item.itemMeta = meta

        giveItem(player, item)
        player.sendMessage(
            Component.text("${skill.displayName} 지급 완료!", NamedTextColor.GREEN)
        )
    }

    // 리소스팩 없는 일반 수리검 아이템
    private fun createShurikenItem(): ItemStack {

        val item = ItemStack(Material.IRON_NUGGET)
        val meta = item.itemMeta

        meta.displayName(
            Component.text(
                "수리검",
                NamedTextColor.WHITE
            )
        )

        meta.lore(
            listOf(
                Component.text(
                    "우클릭하여 던집니다.",
                    NamedTextColor.GRAY
                ),
                Component.text(
                    "적중하거나 멈추면 다시 회수할 수 있습니다.",
                    NamedTextColor.GRAY
                ),
                Component.text(
                    "차크라 소모 없음",
                    NamedTextColor.AQUA
                )
            )
        )

        meta.persistentDataContainer.set(
            shurikenKey,
            PersistentDataType.BYTE,
            1.toByte()
        )

        item.itemMeta = meta

        return item
    }

    private fun giveShuriken(
        player: Player,
        amount: Int
    ) {

        val item = createShurikenItem()
        item.amount = amount

        giveItem(player, item)

        player.sendMessage(
            Component.text(
                "수리검 $amount 개를 지급했습니다!",
                NamedTextColor.GREEN
            )
        )
    }

    private fun giveItem(
        player: Player,
        item: ItemStack
    ) {

        val leftovers = player.inventory.addItem(item)

        leftovers.values.forEach {
            player.world.dropItemNaturally(
                player.location,
                it
            )
        }
    }

    // ==================================================
    // 우클릭 이벤트
    // ==================================================

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {

        if (event.hand != EquipmentSlot.HAND) return

        if (
            event.action != Action.RIGHT_CLICK_AIR &&
            event.action != Action.RIGHT_CLICK_BLOCK
        ) {
            return
        }

        val player = event.player
        val item = event.item ?: return

        val pdc = item.itemMeta.persistentDataContainer

        // 기술 선택서
        if (pdc.has(bookKey, PersistentDataType.BYTE)) {
            event.isCancelled = true
            showSkillSelection(player)
            return
        }

        // 일반 수리검
        if (pdc.has(shurikenKey, PersistentDataType.BYTE)) {
            event.isCancelled = true
            throwShuriken(player)
            return
        }

        // 기술 아이템
        val skillId = pdc.get(
            skillKey,
            PersistentDataType.STRING
        ) ?: return

        val skill = Skill.fromId(skillId)

        if (skill == null) {
            val shadowSkill = ShadowSkill.fromId(skillId) ?: return
            event.isCancelled = true
            summonShadowClones(player, shadowSkill)
            return
        }

        event.isCancelled = true

        val prepared = preparedSkills[player.uniqueId]

        if (prepared != null) {

            if (prepared != skill) {
                player.sendMessage(
                    Component.text(
                        "${prepared.displayName}이 준비되어 있습니다. 같은 기술 아이템으로 우클릭해 발사하세요.",
                        NamedTextColor.YELLOW
                    )
                )
                return
            }

            launchPreparedSkill(player, skill)
            return
        }

        startCharging(player, skill)
    }

    private fun showSkillSelection(player: Player) {

        val normal = Component.text(
            "[일반 나선환]",
            NamedTextColor.AQUA
        ).clickEvent(
            ClickEvent.runCommand("/narutaselect rasengan")
        )

        val giant = Component.text(
            "[초대옥 나선환]",
            NamedTextColor.LIGHT_PURPLE
        ).clickEvent(
            ClickEvent.runCommand("/narutaselect giant")
        )

        val shuriken = Component.text(
            "[나선수리검]",
            NamedTextColor.WHITE
        ).clickEvent(
            ClickEvent.runCommand("/narutaselect rasenshuriken")
        )

        val normalShuriken = Component.text(
            "[일반 수리검]",
            NamedTextColor.GRAY
        ).clickEvent(
            ClickEvent.runCommand("/narutaselect shuriken")
        )

        player.sendMessage(
            Component.text(
                "사용할 기술을 선택하세요:",
                NamedTextColor.GOLD
            )
        )

        player.sendMessage(
            normal
                .append(Component.text("  "))
                .append(giant)
        )

        val shadow = Component.text(
            "[그림자 분신술]",
            NamedTextColor.LIGHT_PURPLE
        ).clickEvent(
            ClickEvent.runCommand("/narutaselect shadow")
        )

        val multiShadow = Component.text(
            "[다중 그림자 분신술]",
            NamedTextColor.DARK_PURPLE
        ).clickEvent(
            ClickEvent.runCommand("/narutaselect multi")
        )

        player.sendMessage(
            shuriken
                .append(Component.text("  "))
                .append(normalShuriken)
        )

        player.sendMessage(
            shadow
                .append(Component.text("  "))
                .append(multiShadow)
        )
    }

    // ==================================================
    // 충전 시작
    // ==================================================

    private fun startCharging(
        player: Player,
        skill: Skill
    ) {

        val id = player.uniqueId

        if (chargingSkills.containsKey(id)) {
            player.sendMessage(
                Component.text(
                    "이미 다른 기술을 충전하거나 발사 대기 중입니다!",
                    NamedTextColor.YELLOW
                )
            )
            return
        }

        val cooldownKey = "$id:${skill.id}"
        val now = System.currentTimeMillis()

        val cooldownUntil = cooldowns[cooldownKey] ?: 0L

        if (now < cooldownUntil) {

            val seconds = (
                    (cooldownUntil - now + 999L) / 1000L
                    ).coerceAtLeast(1L)

            player.sendMessage(
                Component.text(
                    "쿨타임이 남았습니다: ${seconds}초",
                    NamedTextColor.RED
                )
            )

            return
        }

        val current = getCurrentChakra(player)

        if (current < skill.chakraCost) {
            player.sendMessage(
                Component.text(
                    "차크라가 부족합니다! 필요 차크라: ${skill.chakraCost}",
                    NamedTextColor.RED
                )
            )
            return
        }

        chakra[id] = current - skill.chakraCost
        chargingSkills[id] = skill

        player.sendMessage(
            Component.text(
                "${skill.displayName} 충전을 시작합니다!",
                NamedTextColor.AQUA
            )
        )

        player.playSound(
            player.location,
            Sound.BLOCK_BEACON_POWER_SELECT,
            1.0f,
            if (skill == Skill.GIANT) 0.65f else 1.1f
        )

        val task = object : BukkitRunnable() {

            private var elapsed = 0

            override fun run() {

                if (!player.isOnline || player.isDead) {
                    cancelPendingSkill(player, true, false)
                    cancel()
                    return
                }

                val progress = (
                        elapsed.toDouble() / chargeTicks
                        ).coerceIn(0.0, 1.0)

                val location = getChargeLocation(player, skill)

                spawnSkillVisual(
                    location,
                    player,
                    skill,
                    elapsed * 0.2,
                    progress
                )

                if (
                    skill == Skill.GIANT &&
                    elapsed % 8 == 0
                ) {
                    player.swingMainHand()
                }

                elapsed++

                if (elapsed >= chargeTicks) {

                    chargingTasks.remove(id)
                    cancel()

                    preparedSkills[id] = skill

                    player.playSound(
                        player.location,
                        Sound.BLOCK_BEACON_ACTIVATE,
                        1.4f,
                        1.4f
                    )

                    player.sendMessage(
                        Component.text(
                            "${skill.displayName} 충전 완료! 다시 우클릭하면 발사합니다.",
                            NamedTextColor.GREEN
                        )
                    )

                    startPreparedVisual(player, skill)
                }
            }
        }.runTaskTimer(this, 1L, 1L)

        chargingTasks[id] = task
    }

    // ==================================================
    // 충전 완료 후 대기
    // ==================================================

    private fun startPreparedVisual(
        player: Player,
        skill: Skill
    ) {

        val id = player.uniqueId

        val task = object : BukkitRunnable() {

            private var elapsed = 0

            override fun run() {

                if (!player.isOnline || player.isDead) {
                    cancelPendingSkill(player, true, false)
                    cancel()
                    return
                }

                val location = getChargeLocation(player, skill)

                spawnSkillVisual(
                    location,
                    player,
                    skill,
                    elapsed * 0.35,
                    1.0
                )

                elapsed += 2

                if (elapsed >= preparedTimeoutTicks) {

                    preparedTasks.remove(id)
                    preparedSkills.remove(id)

                    val reserved = chargingSkills.remove(id)

                    if (reserved != null) {

                        val max = getMaxChakra(id)
                        val current = chakra.getOrDefault(id, 0)

                        chakra[id] = (
                                current + reserved.chakraCost
                                ).coerceAtMost(max)
                    }

                    player.sendMessage(
                        Component.text(
                            "충전 대기 시간이 끝났습니다. 차크라를 돌려받았습니다.",
                            NamedTextColor.YELLOW
                        )
                    )

                    cancel()
                }
            }
        }.runTaskTimer(this, 1L, 2L)

        preparedTasks[id] = task
    }

    // ==================================================
    // 충전 완료 후 재우클릭 발사
    // ==================================================

    private fun launchPreparedSkill(
        player: Player,
        skill: Skill
    ) {

        val id = player.uniqueId

        preparedTasks.remove(id)?.cancel()
        preparedSkills.remove(id)
        chargingSkills.remove(id)

        cooldowns["$id:${skill.id}"] =
            System.currentTimeMillis() + skill.cooldownMillis

        player.playSound(
            player.location,
            Sound.ENTITY_FIREWORK_ROCKET_LAUNCH,
            1.8f,
            if (skill == Skill.GIANT) 0.65f else 1.2f
        )

        val start = getChargeLocation(player, skill)

        if (skill == Skill.RASENSHURIKEN) {

            launchRasenshuriken(
                player,
                start
            )

        } else {

            launchRasengan(
                player,
                start,
                skill
            )
        }
    }

    // ==================================================
    // 충전 취소
    // ==================================================

    private fun cancelPendingSkill(
        player: Player,
        refund: Boolean,
        notify: Boolean
    ) {

        val id = player.uniqueId

        chargingTasks.remove(id)?.cancel()
        preparedTasks.remove(id)?.cancel()

        preparedSkills.remove(id)

        val reserved = chargingSkills.remove(id)

        if (refund && reserved != null) {

            val max = getMaxChakra(id)
            val current = chakra.getOrDefault(id, 0)

            chakra[id] = (
                    current + reserved.chakraCost
                    ).coerceAtMost(max)

            if (notify && player.isOnline) {
                player.sendMessage(
                    Component.text(
                        "기술 충전이 취소되어 차크라를 돌려받았습니다.",
                        NamedTextColor.YELLOW
                    )
                )
            }
        }
    }

    // ==================================================
    // 기술별 위치
    // ==================================================

    private fun getChargeLocation(
        player: Player,
        skill: Skill
    ): Location {

        val location = player.location.clone()
        val direction = player.location.direction

        val horizontal = Vector(
            direction.x,
            0.0,
            direction.z
        )

        if (horizontal.lengthSquared() < 0.0001) {
            horizontal.x = 0.0
            horizontal.z = -1.0
        }

        horizontal.normalize()

        val right = Vector(
            -horizontal.z,
            0.0,
            horizontal.x
        )

        return when (skill) {

            // 오른손 앞쪽
            Skill.RASENGAN -> {
                location
                    .add(0.0, 1.05, 0.0)
                    .add(horizontal.clone().multiply(0.45))
                    .add(right.clone().multiply(0.4))
            }

            // 머리 위
            Skill.GIANT -> {
                location.add(0.0, 2.25, 0.0)
            }

            // 머리 위
            Skill.RASENSHURIKEN -> {
                location.add(0.0, 2.3, 0.0)
            }
        }
    }

    // ==================================================
    // 기술별 충전 연출
    // ==================================================

    private fun spawnSkillVisual(
        location: Location,
        player: Player,
        skill: Skill,
        phase: Double,
        progress: Double
    ) {

        when (skill) {

            Skill.RASENGAN -> {
                spawnRasengan(
                    location,
                    false,
                    phase,
                    progress
                )
            }

            Skill.GIANT -> {
                spawnRasengan(
                    location,
                    true,
                    phase,
                    progress
                )
            }

            Skill.RASENSHURIKEN -> {
                spawnShurikenShape(
                    location,
                    Vector(0.0, 1.0, 0.0),
                    phase,
                    0.3 + progress * 0.85,
                    true
                )

                location.world?.spawnParticle(
                    Particle.CLOUD,
                    location,
                    5,
                    0.3,
                    0.15,
                    0.3,
                    0.01
                )
            }
        }
    }

    // 일반 나선환 / 초대옥 나선환
    private fun spawnRasengan(
        location: Location,
        giant: Boolean,
        phase: Double,
        progress: Double
    ) {

        val world = location.world ?: return

        val radius = if (giant) {
            0.45 + progress * 0.7
        } else {
            0.12 + progress * 0.22
        }

        val count = if (giant) 44 else 24

        val dust = Particle.DustOptions(
            Color.fromRGB(90, 205, 255),
            if (giant) 2.0f else 1.1f
        )

        for (i in 0 until count) {

            val angle = phase + (2.0 * PI * i / count)

            val x = cos(angle) * radius
            val y = sin(angle * 2.0) * radius * 0.65
            val z = sin(angle) * radius

            world.spawnParticle(
                Particle.DUST,
                location.clone().add(x, y, z),
                1,
                0.0,
                0.0,
                0.0,
                0.0,
                dust
            )
        }

        // 회전하는 고리
        val ringRadius = radius * 1.35

        for (i in 0 until 18) {

            val angle = -phase * 1.4 + (2.0 * PI * i / 18)

            val ringPoint = location.clone().add(
                cos(angle) * ringRadius,
                sin(angle) * 0.12,
                sin(angle) * ringRadius
            )

            world.spawnParticle(
                Particle.END_ROD,
                ringPoint,
                1,
                0.0,
                0.0,
                0.0,
                0.0
            )
        }

        world.spawnParticle(
            Particle.ELECTRIC_SPARK,
            location,
            if (giant) 14 else 5,
            radius * 0.55,
            radius * 0.55,
            radius * 0.55,
            0.02
        )
    }

    // ==================================================
    // 네 갈래 수리검 파티클
    // ==================================================

    private fun spawnShurikenShape(
        location: Location,
        direction: Vector,
        phase: Double,
        scale: Double,
        blue: Boolean
    ) {

        val world = location.world ?: return

        val forward = direction.clone().normalize()

        var right = forward.clone()
            .crossProduct(Vector(0.0, 1.0, 0.0))

        if (right.lengthSquared() < 0.0001) {
            right = forward.clone()
                .crossProduct(Vector(1.0, 0.0, 0.0))
        }

        right.normalize()

        val up = right.clone()
            .crossProduct(forward)
            .normalize()

        val color = if (blue) {
            Color.fromRGB(100, 210, 255)
        } else {
            Color.fromRGB(205, 215, 225)
        }

        val dust = Particle.DustOptions(
            color,
            if (blue) 1.5f else 1.1f
        )

        // 별의 외곽
        for (i in 0 until 40) {

            val angle = phase + (2.0 * PI * i / 40)

            val radius = scale * (
                    0.28 + 0.72 * abs(cos(angle * 2.0))
                    )

            val offset = right.clone()
                .multiply(cos(angle) * radius)

            offset.add(
                up.clone().multiply(
                    sin(angle) * radius
                )
            )

            world.spawnParticle(
                Particle.DUST,
                location.clone().add(offset),
                1,
                0.0,
                0.0,
                0.0,
                0.0,
                dust
            )
        }

        // 네 갈래 날개
        for (blade in 0 until 4) {

            val angle = phase + blade * PI / 2.0

            for (step in 1..7) {

                val distance = scale * step / 7.0

                val offset = right.clone()
                    .multiply(cos(angle) * distance)

                offset.add(
                    up.clone().multiply(
                        sin(angle) * distance
                    )
                )

                world.spawnParticle(
                    Particle.CRIT,
                    location.clone().add(offset),
                    1,
                    0.0,
                    0.0,
                    0.0,
                    0.0
                )
            }
        }

        world.spawnParticle(
            Particle.ELECTRIC_SPARK,
            location,
            5,
            scale * 0.25,
            scale * 0.25,
            scale * 0.25,
            0.02
        )
    }

    // ==================================================
    // 나선환 / 초대옥 나선환 발사
    // ==================================================

    private fun launchRasengan(
        player: Player,
        start: Location,
        skill: Skill
    ) {

        val current = start.clone()

        val velocity = player.eyeLocation.direction
            .normalize()
            .multiply(skill.projectileSpeed)

        object : BukkitRunnable() {

            private var ticks = 0

            override fun run() {

                val world = current.world

                if (
                    !player.isOnline ||
                    world == null ||
                    !world.isChunkLoaded(
                        current.blockX shr 4,
                        current.blockZ shr 4
                    )
                ) {
                    cancel()
                    return
                }

                current.add(velocity)

                spawnRasengan(
                    current,
                    skill == Skill.GIANT,
                    ticks * 0.4,
                    1.0
                )

                val target = world.getNearbyEntities(
                    current,
                    0.8,
                    0.8,
                    0.8
                )
                    .filterIsInstance<LivingEntity>()
                    .firstOrNull {
                        it.uniqueId != player.uniqueId &&
                                it !is ArmorStand &&
                                it.isValid &&
                                !it.isDead
                    }

                if (target != null) {

                    explodeRasengan(
                        player,
                        current,
                        skill,
                        target
                    )

                    cancel()
                    return
                }

                val block = current.block

                if (block.type.isSolid && !block.isPassable) {

                    explodeRasengan(
                        player,
                        current,
                        skill,
                        null
                    )

                    cancel()
                    return
                }

                ticks++

                if (ticks >= skill.maxFlightTicks) {

                    explodeRasengan(
                        player,
                        current,
                        skill,
                        null
                    )

                    cancel()
                }
            }
        }.runTaskTimer(this, 1L, 1L)
    }

    // 나선환 및 초대옥 나선환 폭발
    private fun explodeRasengan(
        player: Player,
        location: Location,
        skill: Skill,
        directTarget: LivingEntity?
    ) {

        val world = location.world ?: return

        world.spawnParticle(
            Particle.EXPLOSION_EMITTER,
            location,
            1
        )

        world.spawnParticle(
            Particle.CLOUD,
            location,
            if (skill == Skill.GIANT) 120 else 40,
            if (skill == Skill.GIANT) 2.5 else 1.0,
            if (skill == Skill.GIANT) 2.0 else 0.8,
            if (skill == Skill.GIANT) 2.5 else 1.0,
            0.08
        )

        world.spawnParticle(
            Particle.ELECTRIC_SPARK,
            location,
            if (skill == Skill.GIANT) 100 else 35,
            2.0,
            2.0,
            2.0,
            0.12
        )

        world.playSound(
            location,
            Sound.ENTITY_GENERIC_EXPLODE,
            if (skill == Skill.GIANT) 4.0f else 2.0f,
            if (skill == Skill.GIANT) 0.65f else 1.0f
        )

        val nearby = world.getNearbyEntities(
            location,
            skill.damageRadius.toDouble(),
            skill.damageRadius.toDouble(),
            skill.damageRadius.toDouble()
        )

        for (entity in nearby) {

            if (entity !is LivingEntity) continue
            if (entity is ArmorStand) continue
            if (entity.uniqueId == player.uniqueId) continue
            if (entity.isDead) continue

            if (
                entity.location.distance(location) >
                skill.damageRadius.toDouble()
            ) {
                continue
            }

            // 직접 맞은 대상에게는 피해를 합산하여 한 번에 적용
            val damage = skill.explosionDamage +
                    if (directTarget?.uniqueId == entity.uniqueId) {
                        skill.directDamage
                    } else {
                        0.0
                    }

            entity.damage(damage, player)

            val push = entity.location.toVector()
                .subtract(location.toVector())

            if (push.lengthSquared() < 0.001) {
                push.setY(1.0)
            } else {
                push.normalize()
            }

            push.multiply(
                if (skill == Skill.GIANT) 1.25 else 0.55
            )

            push.setY(
                if (skill == Skill.GIANT) 0.7 else 0.3
            )

            entity.velocity = push
        }

        // 블록 파괴
        if (skill.blockRadius > 0) {

            val radius = skill.blockRadius

            for (x in -radius..radius) {
                for (y in -radius..radius) {
                    for (z in -radius..radius) {

                        if (
                            x * x + y * y + z * z >
                            radius * radius
                        ) {
                            continue
                        }

                        val block = world.getBlockAt(
                            location.blockX + x,
                            location.blockY + y,
                            location.blockZ + z
                        )

                        if (block.type.isAir || block.isLiquid) continue
                        if (block.state is TileState) continue

                        if (
                            block.type == Material.BEDROCK ||
                            block.type == Material.BARRIER ||
                            block.type == Material.END_PORTAL ||
                            block.type == Material.END_PORTAL_FRAME
                        ) {
                            continue
                        }

                        block.setType(Material.AIR, false)
                    }
                }
            }
        }
    }

    // ==================================================
    // 나선수리검
    // ==================================================

    private fun launchRasenshuriken(
        player: Player,
        start: Location
    ) {

        val current = start.clone()

        val direction = player.eyeLocation.direction.normalize()

        val velocity = direction.clone()
            .multiply(Skill.RASENSHURIKEN.projectileSpeed)

        val hitTargets = mutableSetOf<UUID>()

        object : BukkitRunnable() {

            private var ticks = 0

            override fun run() {

                val world = current.world

                if (
                    !player.isOnline ||
                    world == null ||
                    !world.isChunkLoaded(
                        current.blockX shr 4,
                        current.blockZ shr 4
                    )
                ) {
                    cancel()
                    return
                }

                current.add(velocity)

                spawnShurikenShape(
                    current,
                    direction,
                    ticks * 0.55,
                    1.15,
                    true
                )

                // 관통한 대상에게 직접 피해
                val nearby = world.getNearbyEntities(
                    current,
                    0.9,
                    0.9,
                    0.9
                )

                for (entity in nearby) {

                    if (entity !is LivingEntity) continue
                    if (entity is ArmorStand) continue
                    if (entity.uniqueId == player.uniqueId) continue
                    if (entity.isDead) continue

                    if (!hitTargets.add(entity.uniqueId)) {
                        continue
                    }

                    // 직접 맞은 대상은 60 + 30 = 90 피해
                    // 무적시간 때문에 두 번 나누지 않고 한 번에 적용
                    entity.damage(
                        Skill.RASENSHURIKEN.directDamage +
                                Skill.RASENSHURIKEN.explosionDamage,
                        player
                    )

                    val knockback = direction.clone()
                        .multiply(0.45)

                    knockback.setY(0.2)

                    entity.velocity = entity.velocity.add(knockback)
                }

                // 블록 충돌
                val block = current.block

                if (block.type.isSolid && !block.isPassable) {

                    explodeRasenshuriken(
                        player,
                        current,
                        hitTargets
                    )

                    cancel()
                    return
                }

                ticks++

                if (ticks >= Skill.RASENSHURIKEN.maxFlightTicks) {

                    explodeRasenshuriken(
                        player,
                        current,
                        hitTargets
                    )

                    cancel()
                }
            }
        }.runTaskTimer(this, 1L, 1L)
    }

    // 나선수리검 폭발
    private fun explodeRasenshuriken(
        player: Player,
        location: Location,
        hitTargets: Set<UUID>
    ) {

        val world = location.world ?: return
        val radius = Skill.RASENSHURIKEN.damageRadius

        world.spawnParticle(
            Particle.EXPLOSION_EMITTER,
            location,
            1
        )

        world.spawnParticle(
            Particle.CLOUD,
            location,
            100,
            2.0,
            2.0,
            2.0,
            0.12
        )

        world.spawnParticle(
            Particle.SWEEP_ATTACK,
            location,
            20,
            1.5,
            1.5,
            1.5,
            0.1
        )

        world.spawnParticle(
            Particle.ELECTRIC_SPARK,
            location,
            70,
            1.5,
            1.5,
            1.5,
            0.08
        )

        world.playSound(
            location,
            Sound.ENTITY_GENERIC_EXPLODE,
            3.0f,
            1.2f
        )

        // 원형 바람 효과
        for (i in 0 until 48) {

            val angle = 2.0 * PI * i / 48

            val point = location.clone().add(
                cos(angle) * radius,
                0.2,
                sin(angle) * radius
            )

            world.spawnParticle(
                Particle.CLOUD,
                point,
                2,
                0.1,
                0.2,
                0.1,
                0.02
            )
        }

        val nearby = world.getNearbyEntities(
            location,
            radius.toDouble(),
            radius.toDouble(),
            radius.toDouble()
        )

        for (entity in nearby) {

            if (entity !is LivingEntity) continue
            if (entity is ArmorStand) continue
            if (entity.uniqueId == player.uniqueId) continue
            if (entity.isDead) continue

            if (entity.location.distance(location) > radius) {
                continue
            }

            // 이미 직접 맞은 대상은 추가로 맞지 않음
            if (entity.uniqueId in hitTargets) {
                continue
            }

            entity.damage(
                Skill.RASENSHURIKEN.explosionDamage,
                player
            )

            val push = entity.location.toVector()
                .subtract(location.toVector())

            if (push.lengthSquared() < 0.001) {
                push.setY(1.0)
            } else {
                push.normalize()
            }

            push.multiply(1.0)
            push.setY(0.5)

            entity.velocity = push
        }
    }

    // ==================================================
    // 일반 수리검
    // ==================================================

    private fun throwShuriken(player: Player) {

        val now = System.currentTimeMillis()

        val previous = shurikenLastThrow[player.uniqueId] ?: 0L

        if (now - previous < 500L) {
            return
        }

        val held = player.inventory.itemInMainHand

        if (
            held.type != Material.IRON_NUGGET ||
            !held.itemMeta.persistentDataContainer.has(
                shurikenKey,
                PersistentDataType.BYTE
            )
        ) {
            return
        }

        shurikenLastThrow[player.uniqueId] = now

        // 수리검 하나 소모
        if (held.amount <= 1) {
            player.inventory.setItemInMainHand(
                ItemStack(Material.AIR)
            )
        } else {
            held.amount = held.amount - 1
        }

        player.playSound(
            player.location,
            Sound.ENTITY_ARROW_SHOOT,
            0.8f,
            1.5f
        )

        val direction = player.eyeLocation.direction.normalize()

        val current = player.eyeLocation.clone()
            .add(direction.clone().multiply(0.6))

        val velocity = direction.clone().multiply(1.5)

        object : BukkitRunnable() {

            private var ticks = 0

            override fun run() {

                val world = current.world

                if (world == null) {
                    cancel()
                    return
                }

                if (
                    !world.isChunkLoaded(
                        current.blockX shr 4,
                        current.blockZ shr 4
                    )
                ) {
                    if (player.isOnline) {
                        returnShuriken(player.location)
                    }
                    cancel()
                    return
                }

                current.add(velocity)

                // 회전하는 네 갈래 수리검 효과
                spawnShurikenShape(
                    current,
                    direction,
                    ticks * 0.8,
                    0.42,
                    false
                )

                // 적중 판정
                val target = world.getNearbyEntities(
                    current,
                    0.55,
                    0.55,
                    0.55
                )
                    .filterIsInstance<LivingEntity>()
                    .firstOrNull {
                        it.uniqueId != player.uniqueId &&
                                it !is ArmorStand &&
                                it.isValid &&
                                !it.isDead
                    }

                if (target != null) {

                    target.damage(5.0, player)

                    world.playSound(
                        current,
                        Sound.ITEM_TRIDENT_HIT,
                        0.8f,
                        1.4f
                    )

                    returnShuriken(
                        target.location.clone().add(0.0, 0.5, 0.0)
                    )

                    cancel()
                    return
                }

                // 블록에 닿으면 회수
                val block = current.block

                if (block.type.isSolid && !block.isPassable) {

                    world.playSound(
                        current,
                        Sound.BLOCK_CHAIN_HIT,
                        0.8f,
                        1.5f
                    )

                    returnShuriken(current)

                    cancel()
                    return
                }

                ticks++

                // 최대 비행 거리
                if (ticks >= 40) {
                    returnShuriken(current)
                    cancel()
                }
            }
        }.runTaskTimer(this, 1L, 1L)
    }

    // 회수 가능한 수리검 드롭
    private fun returnShuriken(location: Location) {

        val world = location.world ?: return

        val dropped = world.dropItemNaturally(
            location,
            createShurikenItem()
        )

        // 바로 던진 사람에게 들어가는 현상 방지
        dropped.setPickupDelay(10)
    }

    // ==================================================
    // 그림자 분신술 / 다중 그림자 분신술
    // ==================================================

    private fun summonShadowClones(player: Player, skill: ShadowSkill) {
        val controller = shadowController
        if (controller == null) {
            player.sendMessage(
                Component.text("Citizens 플러그인이 없어 그림자 분신술을 사용할 수 없습니다.", NamedTextColor.RED)
            )
            return
        }

        val ownerId = player.uniqueId
        val cooldownKey = "$ownerId:${skill.id}"
        val now = System.currentTimeMillis()
        val cooldownUntil = shadowCooldowns[cooldownKey] ?: 0L

        if (now < cooldownUntil) {
            val seconds = ((cooldownUntil - now + 999L) / 1000L).coerceAtLeast(1L)
            player.sendMessage(
                Component.text("분신술 쿨타임이 남았습니다: ${seconds}초", NamedTextColor.RED)
            )
            return
        }

        val current = getCurrentChakra(player)
        if (current < skill.chakraCost) {
            player.sendMessage(
                Component.text("차크라가 부족합니다! 필요 차크라: ${skill.chakraCost}", NamedTextColor.RED)
            )
            return
        }

        if (!controller.summon(player, skill.cloneCount)) {
            player.sendMessage(
                Component.text("분신을 소환하지 못했습니다. 차크라를 차감하지 않았습니다.", NamedTextColor.RED)
            )
            return
        }

        chakra[ownerId] = current - skill.chakraCost
        shadowCooldowns[cooldownKey] = now + skill.cooldownMillis

        player.world.spawnParticle(Particle.CLOUD, player.location.clone().add(0.0, 1.0, 0.0), 35, 0.7, 0.8, 0.7, 0.04)
        player.world.playSound(player.location, Sound.ENTITY_PLAYER_TELEPORT, 1.0f, 1.4f)
        player.sendMessage(
            Component.text("${skill.displayName}! 분신 ${skill.cloneCount}명이 30초 동안 함께합니다.", NamedTextColor.LIGHT_PURPLE)
        )
    }

    // ==================================================
    // 접속 / 퇴장 / 사망
    // ==================================================

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        loadPlayerData(event.player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {

        val player = event.player

        shadowController?.remove(player.uniqueId)

        cancelPendingSkill(
            player,
            refund = true,
            notify = false
        )

        savePlayerData(player)
        saveConfig()

        chakraActionbarPlayers.remove(player.uniqueId)
        shurikenLastThrow.remove(player.uniqueId)
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {

        val player = event.entity

        shadowController?.remove(player.uniqueId)

        cancelPendingSkill(
            player,
            refund = true,
            notify = false
        )

        savePlayerData(player)
        saveConfig()
    }
}
