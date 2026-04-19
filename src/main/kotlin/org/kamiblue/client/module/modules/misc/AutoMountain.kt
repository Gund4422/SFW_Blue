package org.kamiblue.client.module.modules.misc

import net.minecraft.block.*
import net.minecraft.client.Minecraft
import net.minecraft.item.*
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraftforge.fml.common.gameevent.TickEvent
import org.kamiblue.client.module.Category
import org.kamiblue.client.module.Module
import org.kamiblue.client.util.text.MessageSendHelper
import org.kamiblue.event.listener.safeListener

internal object AutoMountain : Module(
    name = "AutoMountain",
    description = "Automatically builds stair scaffolding up or down.",
    category = Category.MISC
) {

    // ── Settings ──────────────────────────────────────────────────────────────
    private val mouseT         by setting("MouseTurn",            true)
    private val startPaused    by setting("StartPaused",          true)
    private val swapStack      by setting("SwapStackOnRunOut",    true)
    private val swapPauseTicks by setting("SwapPauseTicks",       3, 1..60, 1)
    private val upLimit        by setting("UpwardBuildLimit",     255, -64..255, 1)
    private val downLimit      by setting("DownwardBuildLimit",   0, -64..255, 1)
    private val invertUp       by setting("InvertDirAtUpLimit",   false)
    private val invertDown     by setting("InvertDirAtDownLimit", false)
    private val placementDelay by setting("PlacementTickDelay",   1, 1..10, 1)
    private val antiKick       by setting("PauseBasedAntiKick",   false)
    private val antiKickDelay  by setting("PauseTicks",           5, 1..100, 1)
    private val antiKickOff    by setting("TicksBetweenPause",    20, 1..200, 1)

    // ── State ─────────────────────────────────────────────────────────────────
    private var paused             = false
    private var playerPos          = BlockPos.ORIGIN
    private var wasFacing          = EnumFacing.NORTH
    private var prevPitch          = 0
    private var speed              = 0
    private var go                 = true
    private var delayLeft          = 0
    private var offLeft            = 0
    private var justSwapped        = false
    private var graceTicks         = 0
    private var lastSlot           = -1
    private var wasPressedLastTick = false

    // ── Convenience accessor ──────────────────────────────────────────────────
    private val mc get() = Minecraft.getMinecraft()

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onEnable() {
        val mc = Minecraft.getMinecraft()
        lastSlot = mc.player.inventory.currentItem
        if (startPaused) {
            paused = false
            MessageSendHelper.sendChatMessage("[AutoMountain] Press UseKey (RightClick) to build stairs!")
        } else {
            snapToGround(mc)
            wasFacing = mc.player.horizontalFacing
            prevPitch = Math.round(mc.player.rotationPitch)
            if (swapStack) swapToValidBlock(mc)
            mc.player.motionX = 0.0
            mc.player.motionY = 0.0
            mc.player.motionZ = 0.0
            centerPlayer(mc)
            paused = true
            MessageSendHelper.sendChatMessage("[AutoMountain] Building stairs!")
        }
        playerPos = mc.player.position
        delayLeft = antiKickDelay
        offLeft   = antiKickOff
    }

    override fun onDisable() {
        val mc = Minecraft.getMinecraft()
        mc.player.noClip = false
        speed = 0
    }

    // ── Main tick ─────────────────────────────────────────────────────────────
    init {
        safeListener<TickEvent.ClientTickEvent> { event ->
            if (event.phase != TickEvent.Phase.START) return@safeListener

            val mc = Minecraft.getMinecraft()
            if (mc.player == null || mc.world == null) return@safeListener

            playerPos = mc.player.position

            // Toggle pause on right-click (use key)
            val useDown = mc.gameSettings.keyBindUseItem.isKeyDown
            if (useDown && !wasPressedLastTick) togglePause(mc)
            wasPressedLastTick = useDown

            if (!paused) {
                wasFacing = mc.player.horizontalFacing
                prevPitch = Math.round(mc.player.rotationPitch)
                return@safeListener
            }

            // Anti-kick counters
            if (!antiKick) {
                offLeft   = Int.MAX_VALUE / 2
                delayLeft = 0
            } else if (offLeft > antiKickOff) {
                offLeft = antiKickOff
            }

            // Keep player snapped and still
            mc.player.motionX = 0.0
            mc.player.motionY = 0.0
            mc.player.motionZ = 0.0
            centerPlayer(mc)
            mc.player.setPosition(mc.player.posX, Math.round(mc.player.posY).toDouble() + 0.25, mc.player.posZ)

            // Auto-swap empty block stacks
            if (swapStack) {
                val prev = mc.player.inventory.currentItem
                swapToValidBlock(mc)
                val newSlot = mc.player.inventory.currentItem
                if (newSlot != lastSlot && newSlot != prev) {
                    justSwapped = true
                    graceTicks  = swapPauseTicks
                    lastSlot    = newSlot
                }
            }

            // Placement speed throttle
            if (speed < placementDelay) {
                go = false
                speed++
            } else {
                speed = 0
                go = true
            }

            // Grace ticks after a hotbar swap
            if (justSwapped) {
                graceTicks--
                if (graceTicks > 0) {
                    go    = false
                    speed = 0
                    mc.player.motionX = 0.0
                    mc.player.motionY = 0.0
                    mc.player.motionZ = 0.0
                    centerPlayer(mc)
                    mc.player.setPosition(mc.player.posX, Math.round(mc.player.posY).toDouble() + 0.25, mc.player.posZ)
                    return@safeListener
                } else {
                    justSwapped = false
                }
            }

            if (!go) return@safeListener
            if (isInvalidBlock(mc.player.heldItemMainhand)) return@safeListener

            val pitchUp = if (mouseT) mc.player.rotationPitch <= 40f else prevPitch <= 40

            if (pitchUp) {
                if (!consumeLimitUp(mc)) return@safeListener
                buildUp(mc)
                if (mc.player.posY >= upLimit - 1 && invertUp) setPitch(75)
            } else {
                if (!consumeLimitDown(mc)) return@safeListener
                buildDown(mc)
                if (mc.player.posY <= downLimit + 1 && invertDown) setPitch(35)
            }

            centerPlayer(mc)
        }
    }

    // ── Build logic ───────────────────────────────────────────────────────────
    private fun buildUp(mc: Minecraft) {
        val facing = if (mouseT) mc.player.horizontalFacing else wasFacing
        val dx = facing.directionVec.x
        val dz = facing.directionVec.z

        val target = playerPos.add(dx, 0, dz)
        val un1    = playerPos.add(0, 2, 0)
        val un2    = target.add(0, 1, 0)
        val un3    = target.add(0, 2, 0)
        val un4    = target.add(0, 3, 0)

        if (listOf(un1, un2, un3, un4).all { isReplaceable(mc, it) }
            && mc.world.worldBorder.contains(un2)) {
            if (isReplaceable(mc, target)) placeBlockAt(mc, target)
            mc.player.setPosition(
                mc.player.posX + dx,
                mc.player.posY + 1,
                mc.player.posZ + dz
            )
        } else {
            if (invertUp) setPitch(75)
        }
    }

    private fun buildDown(mc: Minecraft) {
        val facing = if (mouseT) mc.player.horizontalFacing else wasFacing
        val dx = facing.directionVec.x
        val dz = facing.directionVec.z

        val dn1 = playerPos.add(dx, -1, dz)
        val dn2 = playerPos.add(dx,  0, dz)
        val dn3 = playerPos.add(dx,  1, dz)
        val pos = playerPos.add(dx, -2, dz)

        if (listOf(dn1, dn2, dn3).all { isReplaceable(mc, it) }
            && mc.world.worldBorder.contains(dn2)) {
            if (isReplaceable(mc, pos)) placeBlockAt(mc, pos)
            mc.player.setPosition(
                mc.player.posX + dx,
                mc.player.posY - 1,
                mc.player.posZ + dz
            )
        } else {
            if (invertDown) setPitch(35)
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────
    private fun placeBlockAt(mc: Minecraft, pos: BlockPos) {
        val hitVec = Vec3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        mc.playerController.processRightClickBlock(
            mc.player, mc.world, pos, EnumFacing.DOWN, hitVec, EnumHand.MAIN_HAND
        )
        mc.player.swingArm(EnumHand.MAIN_HAND)
    }

    private fun isReplaceable(mc: Minecraft, pos: BlockPos) =
        mc.world.getBlockState(pos).block.isReplaceable(mc.world, pos)

    private fun centerPlayer(mc: Minecraft) {
        val cx = Math.floor(mc.player.posX) + 0.5
        val cz = Math.floor(mc.player.posZ) + 0.5
        mc.player.setPosition(cx, mc.player.posY, cz)
    }

    private fun snapToGround(mc: Minecraft) {
        mc.player.setPosition(mc.player.posX, Math.ceil(mc.player.posY), mc.player.posZ)
    }

    private fun setPitch(p: Int) {
        val mc = Minecraft.getMinecraft()
        if (mouseT) mc.player.rotationPitch = p.toFloat() else prevPitch = p
    }

    private fun consumeLimitUp(mc: Minecraft): Boolean {
        if (mc.player.posY > upLimit && !invertUp) return false
        if (delayLeft > 0) { delayLeft--; return false }
        if (offLeft <= 0) { delayLeft = antiKickDelay; offLeft = antiKickOff; return false }
        offLeft--
        return true
    }

    private fun consumeLimitDown(mc: Minecraft): Boolean {
        if (mc.player.posY < downLimit && !invertDown) return false
        if (delayLeft > 0) { delayLeft--; return false }
        if (offLeft <= 0) { delayLeft = antiKickDelay; offLeft = antiKickOff; return false }
        offLeft--
        return true
    }

    private fun togglePause(mc: Minecraft) {
        if (paused) {
            snapToGround(mc)
        } else {
            val pos = playerPos.add(0, -1, 0)
            if (isReplaceable(mc, pos)) placeBlockAt(mc, pos)
        }
        paused = !paused
        mc.player.motionX = 0.0
        mc.player.motionY = 0.0
        mc.player.motionZ = 0.0
        speed = 0
    }

    private fun swapToValidBlock(mc: Minecraft) {
        for (i in 0..8) {
            if (!isInvalidBlock(mc.player.inventory.getStackInSlot(i))) {
                mc.player.inventory.currentItem = i
                return
            }
        }
    }

    private fun isInvalidBlock(stack: ItemStack): Boolean {
        if (stack.isEmpty) return true
        val item = stack.item
        if (item !is ItemBlock) return true
        if (item is ItemBed) return true
        if (item is ItemSkull) return true
        val block = item.block
        return block is BlockBush
            || block is BlockTorch
            || block is BlockRedstoneWire
            || block is BlockFence
            || block is BlockFenceGate
            || block is BlockWall
            || block is BlockFalling
            || block is BlockRail
            || block is BlockSign
            || block is BlockCarpet
            || block is BlockSnow
            || block is BlockPressurePlate
            || block is BlockShulkerBox
            || block is BlockCactus
            || block is BlockReed
            || block is BlockLiquid
            || block is BlockLadder
            || block is BlockTNT
            || block is BlockCake
            || block is BlockWeb
            || block is BlockFlowerPot
    }
}
