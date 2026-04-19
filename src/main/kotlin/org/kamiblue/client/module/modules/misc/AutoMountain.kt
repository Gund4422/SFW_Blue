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
import org.kamiblue.client.event.listener.safeListener // Path 1: Check this first
// import org.kamiblue.event.listener.safeListener // Path 2: Uncomment if Path 1 fails
import org.kamiblue.client.util.Wrapper

internal object AutoMountain : Module(
    name = "AutoMountain",
    description = "Automatically builds stair scaffolding up or down.",
    category = Category.MISC
) {

    // ── Settings ──────────────────────────────────────────────────────────────
    private val mouseT         by setting("MouseTurn",            true)
    private val startPaused     by setting("StartPaused",          true)
    private val swapStack       by setting("SwapStackOnRunOut",    true)
    private val swapPauseTicks  by setting("SwapPauseTicks",       3, 1..60, 1)
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

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    // In some Kami forks, these are just 'fun' and not 'override fun' 
    // but usually they are open in the base class.
    override fun onEnable() {
        val player = mc.player ?: return
        lastSlot = player.inventory.currentItem
        if (startPaused) {
            paused = false
            MessageSendHelper.sendChatMessage("[AutoMountain] Press UseKey (RightClick) to build stairs!")
        } else {
            snapToGround()
            wasFacing = player.horizontalFacing
            prevPitch = Math.round(player.rotationPitch)
            if (swapStack) swapToValidBlock()
            player.motionX = 0.0
            player.motionY = 0.0
            player.motionZ = 0.0
            centerPlayer()
            paused = true
            MessageSendHelper.sendChatMessage("[AutoMountain] Building stairs!")
        }
        playerPos = player.position
        delayLeft = antiKickDelay
        offLeft   = antiKickOff
    }

    override fun onDisable() {
        mc.player?.noClip = false
        speed = 0
    }

    // ── Main tick ─────────────────────────────────────────────────────────────
    init {
        safeListener<TickEvent.ClientTickEvent> { event ->
            if (event.phase != TickEvent.Phase.START) return@safeListener

            val player = mc.player ?: return@safeListener
            val world = mc.world ?: return@safeListener

            playerPos = player.position

            val useDown = mc.gameSettings.keyBindUseItem.isKeyDown
            if (useDown && !wasPressedLastTick) togglePause()
            wasPressedLastTick = useDown

            if (!paused) {
                wasFacing = player.horizontalFacing
                prevPitch = Math.round(player.rotationPitch)
                return@safeListener
            }

            if (!antiKick) {
                offLeft   = Int.MAX_VALUE / 2
                delayLeft = 0
            } else if (offLeft > antiKickOff) {
                offLeft = antiKickOff
            }

            player.motionX = 0.0
            player.motionY = 0.0
            player.motionZ = 0.0
            centerPlayer()
            player.setPosition(player.posX, Math.round(player.posY).toDouble() + 0.25, player.posZ)

            if (swapStack) {
                val prev = player.inventory.currentItem
                swapToValidBlock()
                val newSlot = player.inventory.currentItem
                if (newSlot != lastSlot && newSlot != prev) {
                    justSwapped = true
                    graceTicks  = swapPauseTicks
                    lastSlot    = newSlot
                }
            }

            if (speed < placementDelay) {
                go = false
                speed++
            } else {
                speed = 0
                go = true
            }

            if (justSwapped) {
                graceTicks--
                if (graceTicks > 0) {
                    go    = false
                    speed = 0
                    player.motionX = 0.0
                    player.motionY = 0.0
                    player.motionZ = 0.0
                    centerPlayer()
                    player.setPosition(player.posX, Math.round(player.posY).toDouble() + 0.25, player.posZ)
                    return@safeListener
                } else {
                    justSwapped = false
                }
            }

            if (!go) return@safeListener
            if (isInvalidBlock(player.heldItemMainhand)) return@safeListener

            val pitchUp = if (mouseT) player.rotationPitch <= 40f else prevPitch <= 40

            if (pitchUp) {
                if (!consumeLimitUp()) return@safeListener
                buildUp()
                if (player.posY >= upLimit - 1 && invertUp) setPitch(75)
            } else {
                if (!consumeLimitDown()) return@safeListener
                buildDown()
                if (player.posY <= downLimit + 1 && invertDown) setPitch(35)
            }

            centerPlayer()
        }
    }

    // ── Build logic ───────────────────────────────────────────────────────────
    private fun buildUp() {
        val player = mc.player ?: return
        val facing = if (mouseT) player.horizontalFacing else wasFacing
        val dx = facing.directionVec.x
        val dz = facing.directionVec.z

        val target = playerPos.add(dx, 0, dz)
        val un1    = playerPos.add(0, 2, 0)
        val un2    = target.add(0, 1, 0)
        val un3    = target.add(0, 2, 0)
        val un4    = target.add(0, 3, 0)

        if (listOf(un1, un2, un3, un4).all { isReplaceable(it) }
            && mc.world?.worldBorder?.contains(un2) == true) {
            if (isReplaceable(target)) placeBlockAt(target)
            player.setPosition(
                player.posX + dx,
                player.posY + 1,
                player.posZ + dz
            )
        } else {
            if (invertUp) setPitch(75)
        }
    }

    private fun buildDown() {
        val player = mc.player ?: return
        val facing = if (mouseT) player.horizontalFacing else wasFacing
        val dx = facing.directionVec.x
        val dz = facing.directionVec.z

        val dn1 = playerPos.add(dx, -1, dz)
        val dn2 = playerPos.add(dx,  0, dz)
        val dn3 = playerPos.add(dx,  1, dz)
        val pos = playerPos.add(dx, -2, dz)

        if (listOf(dn1, dn2, dn3).all { isReplaceable(it) }
            && mc.world?.worldBorder?.contains(dn2) == true) {
            if (isReplaceable(pos)) placeBlockAt(pos)
            player.setPosition(
                player.posX + dx,
                player.posY - 1,
                player.posZ + dz
            )
        } else {
            if (invertDown) setPitch(35)
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────
    private fun placeBlockAt(pos: BlockPos) {
        val player = mc.player ?: return
        val world = mc.world ?: return
        val hitVec = Vec3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        mc.playerController.processRightClickBlock(
            player, world, pos, EnumFacing.DOWN, hitVec, EnumHand.MAIN_HAND
        )
        player.swingArm(EnumHand.MAIN_HAND)
    }

    private fun isReplaceable(pos: BlockPos) =
        mc.world?.getBlockState(pos)?.block?.isReplaceable(mc.world, pos) == true

    private fun centerPlayer() {
        val player = mc.player ?: return
        val cx = Math.floor(player.posX) + 0.5
        val cz = Math.floor(player.posZ) + 0.5
        player.setPosition(cx, player.posY, cz)
    }

    private fun snapToGround() {
        val player = mc.player ?: return
        player.setPosition(player.posX, Math.ceil(player.posY), player.posZ)
    }

    private fun setPitch(p: Int) {
        val player = mc.player ?: return
        if (mouseT) player.rotationPitch = p.toFloat() else prevPitch = p
    }

    private fun consumeLimitUp(): Boolean {
        val player = mc.player ?: return false
        if (player.posY > upLimit && !invertUp) return false
        if (delayLeft > 0) { delayLeft--; return false }
        if (offLeft <= 0) { delayLeft = antiKickDelay; offLeft = antiKickOff; return false }
        offLeft--
        return true
    }

    private fun consumeLimitDown(): Boolean {
        val player = mc.player ?: return false
        if (player.posY < downLimit && !invertDown) return false
        if (delayLeft > 0) { delayLeft--; return false }
        if (offLeft <= 0) { delayLeft = antiKickDelay; offLeft = antiKickOff; return false }
        offLeft--
        return true
    }

    private fun togglePause() {
        val player = mc.player ?: return
        if (paused) {
            snapToGround()
        } else {
            val pos = playerPos.add(0, -1, 0)
            if (isReplaceable(pos)) placeBlockAt(pos)
        }
        paused = !paused
        player.motionX = 0.0
        player.motionY = 0.0
        player.motionZ = 0.0
        speed = 0
    }

    private fun swapToValidBlock() {
        val player = mc.player ?: return
        for (i in 0..8) {
            if (!isInvalidBlock(player.inventory.getStackInSlot(i))) {
                player.inventory.currentItem = i
                return
            }
        }
    }

    private fun isInvalidBlock(stack: ItemStack): Boolean {
        if (stack.isEmpty) return true
        val item = stack.item
        if (item !is ItemBlock) return true
        if (item is ItemBed || item is ItemSkull) return true
        val block = item.block
        return block is BlockBush || block is BlockTorch || block is BlockRedstoneWire ||
                block is BlockFence || block is BlockFenceGate || block is BlockWall ||
                block is BlockFalling || block is BlockRail || block is BlockSign ||
                block is BlockCarpet || block is BlockSnow || block is BlockPressurePlate ||
                block is BlockShulkerBox || block is BlockCactus || block is BlockReed ||
                block is BlockLiquid || block is BlockLadder || block is BlockTNT ||
                block is BlockCake || block is BlockWeb || block is BlockFlowerPot
    }
}
