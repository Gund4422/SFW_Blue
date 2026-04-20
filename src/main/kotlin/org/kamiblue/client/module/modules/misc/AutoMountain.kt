package org.kamiblue.client.module.modules.misc

import net.minecraft.block.*
import net.minecraft.item.*
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import org.kamiblue.client.module.Category
import org.kamiblue.client.module.Module
import org.kamiblue.client.util.text.MessageSendHelper
import org.kamiblue.client.setting.settings.impl.primitive.BooleanSetting
import org.kamiblue.client.setting.settings.impl.number.IntegerSetting

internal object AutoMountain : Module(
    name = "AutoMountain",
    category = Category.MISC,
    description = "M.O.L.I. custom renovation tool."
) {
    // Manual Setting Definitions - No 'value' errors here
    private val mouseT = BooleanSetting("MouseTurn", true).also { addSetting(it) }
    private val startPaused = BooleanSetting("StartPaused", true).also { addSetting(it) }
    private val swapStack = BooleanSetting("SwapStack", true).also { addSetting(it) }
    private val upLimit = IntegerSetting("UpLimit", 255, 0..255, 1).also { addSetting(it) }
    private val placementDelay = IntegerSetting("TickDelay", 1, 1..10, 1).also { addSetting(it) }

    private var paused = false
    private var speed = 0
    private var wasPressedLastTick = false

    init {
        // We manually register this object to the Forge Event Bus
        MinecraftForge.EVENT_BUS.register(this)

        onEnable {
            if (startPaused.value) {
                paused = false
                MessageSendHelper.sendChatMessage("$chatName §eRight-Click to start building!")
            } else {
                paused = true
            }
        }
    }

    @SubscribeEvent
    fun onTick(event: TickEvent.ClientTickEvent) {
        // isDisabled is inherited from AbstractModule
        if (event.phase != TickEvent.Phase.START || isDisabled) return

        val player = mc.player ?: return
        
        // Toggle Logic
        val useDown = mc.gameSettings.keyBindUseItem.isKeyDown
        if (useDown && !wasPressedLastTick) paused = !paused
        wasPressedLastTick = useDown

        if (!paused) return

        // Anti-Rubberband: Freeze & Center
        player.motionX = 0.0
        player.motionY = 0.0
        player.motionZ = 0.0
        player.setPosition(Math.floor(player.posX) + 0.5, player.posY, Math.floor(player.posZ) + 0.5)

        if (swapStack.value) swapToValidBlock()

        if (speed < placementDelay.value) {
            speed++
            return
        }
        speed = 0

        // Build Logic
        val pos = player.position
        val facing = player.horizontalFacing
        val pitchUp = player.rotationPitch <= 40f

        if (pitchUp && player.posY < upLimit.value) {
            val target = pos.add(facing.directionVec.x, 0, facing.directionVec.z)
            if (mc.world.getBlockState(target).block.isReplaceable(mc.world, target)) {
                placeBlock(target)
                player.setPosition(player.posX + facing.directionVec.x, player.posY + 1, player.posZ + facing.directionVec.z)
            }
        }
    }

    private fun placeBlock(pos: BlockPos) {
        val hitVec = Vec3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        mc.playerController.processRightClickBlock(mc.player, mc.world, pos, EnumFacing.UP, hitVec, EnumHand.MAIN_HAND)
        mc.player.swingArm(EnumHand.MAIN_HAND)
    }

    private fun swapToValidBlock() {
        for (i in 0..8) {
            val stack = mc.player.inventory.getStackInSlot(i)
            if (!stack.isEmpty && stack.item is ItemBlock) {
                mc.player.inventory.currentItem = i
                return
            }
        }
    }
}
