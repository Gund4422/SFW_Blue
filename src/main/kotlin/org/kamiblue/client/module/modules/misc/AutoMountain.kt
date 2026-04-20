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
import org.kamiblue.client.util.text.MessageSendHelper
import org.kamiblue.commons.event.EventHandler

internal object AutoMountain : Module(
    name = "AutoMountain",
    category = Category.MISC,
    description = "Automatically builds stair scaffolding for M.O.L.I. renovations."
) {

    // ── Settings ──────────────────────────────────────────────────────────────
    private val mouseT         by setting("MouseTurn", true)
    private val startPaused     by setting("StartPaused", true)
    private val swapStack        by setting("SwapStack", true)
    private val swapPauseTicks   by setting("SwapPauseTicks", 3, 1..60, 1)
    private val upLimit         by setting("UpLimit", 255, 0..255, 1) // Set to 255 for 1.12.2
    private val downLimit       by setting("DownLimit", 0, 0..255, 1)
    private val placementDelay  by setting("TickDelay", 1, 1..10, 1)

    // ── State ─────────────────────────────────────────────────────────────────
    private var paused             = false
    private var playerPos          = BlockPos.ORIGIN
    private var wasFacing          = EnumFacing.NORTH
    private var prevPitch          = 0
    private var speed              = 0
    private var lastSlot           = -1
    private var wasPressedLastTick = false

    init {
        onEnable {
            val player = mc.player ?: return@onEnable
            lastSlot = player.inventory.currentItem
            
            if (startPaused.value) {
                paused = false
                MessageSendHelper.sendChatMessage("$chatName §ePress Right-Click to start building!")
            } else {
                snapToGround()
                wasFacing = player.horizontalFacing
                prevPitch = Math.round(player.rotationPitch)
                if (swapStack.value) swapToValidBlock()
                centerPlayer()
                paused = true
                MessageSendHelper.sendChatMessage("$chatName §bBuilding mode active!")
            }
        }

        onDisable {
            mc.player?.noClip = false
            speed = 0
        }
    }

    @EventHandler
    fun onTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.START || isDisabled) return

        val player = mc.player ?: return
        val world = mc.world ?: return

        playerPos = player.position

        // Toggle logic via Right Click
        val useDown = mc.gameSettings.keyBindUseItem.isKeyDown
        if (useDown && !wasPressedLastTick) {
            paused = !paused
            if (paused) snapToGround()
            MessageSendHelper.sendChatMessage("$chatName " + if (paused) "§aResumed" else "§cPaused")
        }
        wasPressedLastTick = useDown

        if (!paused) return

        // Freeze movement for precise placement
        player.motionX = 0.0
        player.motionY = 0.0
        player.motionZ = 0.0
        centerPlayer()
        player.setPosition(player.posX, Math.round(player.posY).toDouble() + 0.25, player.posZ)

        // Swap logic
        if (swapStack.value) swapToValidBlock()

        // Tick delay handling
        if (speed < placementDelay.value) {
            speed++
            return
        }
        speed = 0

        if (isInvalidBlock(player.heldItemMainhand)) return

        // Pitch-based direction check
        val pitchUp = if (mouseT.value) player.rotationPitch <= 40f else prevPitch <= 40

        if (pitchUp) {
            if (player.posY < upLimit.value) buildUp()
        } else {
            if (player.posY > downLimit.value) buildDown()
        }
    }

    private fun buildUp() {
        val player = mc.player ?: return
        val facing = if (mouseT.value) player.horizontalFacing else wasFacing
        val dx = facing.directionVec.x
        val dz = facing.directionVec.z

        val target = playerPos.add(dx, 0, dz)
        if (isReplaceable(target)) {
            placeBlockAt(target)
            player.setPosition(player.posX + dx, player.posY + 1, player.posZ + dz)
        }
    }

    private fun buildDown() {
        val player = mc.player ?: return
        val facing = if (mouseT.value) player.horizontalFacing else wasFacing
        val dx = facing.directionVec.x
        val dz = facing.directionVec.z

        val pos = playerPos.add(dx, -2, dz)
        if (isReplaceable(pos)) {
            placeBlockAt(pos)
            player.setPosition(player.posX + dx, player.posY - 1, player.posZ + dz)
        }
    }

    private fun placeBlockAt(pos: BlockPos) {
        val hitVec = Vec3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        mc.playerController.processRightClickBlock(
            mc.player, mc.world, pos, EnumFacing.UP, hitVec, EnumHand.MAIN_HAND
        )
        mc.player.swingArm(EnumHand.MAIN_HAND)
    }

    private fun isReplaceable(pos: BlockPos) = 
        mc.world.getBlockState(pos).block.isReplaceable(mc.world, pos)

    private fun centerPlayer() {
        val cx = Math.floor(mc.player.posX) + 0.5
        val cz = Math.floor(mc.player.posZ) + 0.5
        mc.player.setPosition(cx, mc.player.posY, cz)
    }

    private fun snapToGround() {
        mc.player.setPosition(mc.player.posX, Math.ceil(mc.player.posY), mc.player.posZ)
    }

    private fun swapToValidBlock() {
        for (i in 0..8) {
            val stack = mc.player.inventory.getStackInSlot(i)
            if (!isInvalidBlock(stack)) {
                mc.player.inventory.currentItem = i
                return
            }
        }
    }

    private fun isInvalidBlock(stack: ItemStack): Boolean {
        if (stack.isEmpty || stack.item !is ItemBlock) return true
        val b = (stack.item as ItemBlock).block
        return b is BlockLiquid || b is BlockFalling || b is BlockTallGrass || b is BlockBush
    }
}
