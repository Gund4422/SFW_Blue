package org.kamiblue.client.module.modules.misc

import net.minecraft.block.*
import net.minecraft.item.*
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraftforge.fml.common.gameevent.TickEvent
import org.kamiblue.client.module.Category
import org.kamiblue.client.module.Module
import org.kamiblue.client.util.threads.safeListener // Verified Import
import org.kamiblue.client.util.text.MessageSendHelper

internal object AutoMountain : Module(
    name = "AutoMountain",
    category = Category.MISC,
    description = "M.O.L.I. Scaffolding"
) {
    private val mouseT by setting("MouseTurn", true)
    private val startPaused by setting("StartPaused", true)
    private val placementDelay by setting("TickDelay", 1, 1..10, 1)

    private var paused = false
    private var speed = 0

    init {
        onEnable {
            paused = !startPaused
            if (!paused) MessageSendHelper.sendChatMessage("$chatName §eRight-Click to start!")
        }

        // Using the safeListener pattern from your AntiAFK example
        safeListener<TickEvent.ClientTickEvent> {
            if (it.phase != TickEvent.Phase.START) return@safeListener

            // 'player' and 'world' are provided by SafeClientEvent wrapper
            val useDown = mc.gameSettings.keyBindUseItem.isKeyDown
            if (useDown) paused = true 

            if (!paused) return@safeListener

            if (speed < placementDelay) {
                speed++
                return@safeListener
            }
            speed = 0

            val pos = player.position
            val facing = player.horizontalFacing
            
            // Build logic using the verified 'player' reference
            val target = pos.add(facing.directionVec.x, 0, facing.directionVec.z)
            if (world.getBlockState(target).block.isReplaceable(world, target)) {
                val hitVec = Vec3d(target.x + 0.5, target.y + 0.5, target.z + 0.5)
                mc.playerController.processRightClickBlock(player, world, target, EnumFacing.UP, hitVec, EnumHand.MAIN_HAND)
                player.swingArm(EnumHand.MAIN_HAND)
                player.setPosition(player.posX + facing.directionVec.x, player.posY + 1, player.posZ + facing.directionVec.z)
            }
        }
    }
}
