package org.rasengan

import org.bukkit.entity.Player
import java.util.UUID

/** Citizens API를 직접 노출하지 않는 선택적 연동 인터페이스 */
interface ShadowCloneController {
    fun summon(owner: Player, cloneCount: Int): Boolean
    fun remove(ownerId: UUID, showEffect: Boolean = false)
    fun shutdown()
}
