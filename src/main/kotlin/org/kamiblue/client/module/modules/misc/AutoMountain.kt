package me.zeroeightsix.kami.module.modules.misc

import me.zeroeightsix.kami.event.events.PacketEvent
import me.zeroeightsix.kami.event.events.RenderWorldEvent
import me.zeroeightsix.kami.module.Module
import me.zeroeightsix.kami.module.modules.movement.Flight
import me.zeroeightsix.kami.setting.Settings
import me.zeroeightsix.kami.util.color.ColorHolder
import me.zeroeightsix.kami.util.graphics.ESPRenderer
import me.zeroeightsix.kami.util.math.VectorUtils
import me.zeroeightsix.kami.util.text.MessageSendHelper
import me.zeroeightsix.kami.util.world.BlockUtils
import net.minecraft.block.*
import net.minecraft.item.*
import net.minecraft.network.play.client.CPacketPlayer
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.RayTraceResult
import net.minecraft.util.math.Vec3d
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import java.awt.Color

/**
 * AutoMountain - Ported from TrouserStreak
 * Ported to KamiBlue by Intiha
 *
 * Automatically builds stair-step scaffolding upward or downward.
 */
@Module.Info(
    name = "AutoMountain",
    description = "Automatically builds stair scaffolding up or down.",
    category = Module.Category.MISC
)
class AutoMountain : Module() {

    // ── General ──────────────────────────────────────────────────────────────
    private val mouseT       = register(Settings.b("MouseTurn", true))
    private val startPaused  = register(Settings.b("StartPaused", true))
    private val swapStack    = register(Settings.b("SwapStackOnRunOut", true))
    private val swapPauseTicks = register(Settings.integerBuilder("SwapPauseTicks").withValue(3).withRange(1, 60).build())
    private val disableOnDC  = register(Settings.b("DisableOnDisconnect", false))

    // ── Build Options ─────────────────────────────────────────────────────────
    private val upLimit      = register(Settings.integerBuilder("UpwardBuildLimit").withValue(255).withRange(-64, 255).build())
    private val downLimit    = register(Settings.integerBuilder("DownwardBuildLimit").withValue(0).withRange(-64, 255).build())
    private val invertUp     = register(Settings.b("InvertDirAtUpLimit", false))
    private val invertDown   = register(Settings.b("InvertDirAtDownLimit", false))

    // ── Timings ───────────────────────────────────────────────────────────────
    private val placementDelay = register(Settings.integerBuilder("PlacementTickDelay").withValue(1).withRange(1, 10).build())
    private val diagDelay    = register(Settings.integerBuilder("DiagonalSwitchDelay").withValue(1).withRange(1, 10).build())
    private val lagPause     = register(Settings.b("PauseIfServerLagging", true))
    private val lagSeconds   = register(Settings.floatBuilder("LagThresholdSeconds").withValue(1.0f).withRange(0.1f, 10.0f).build())
    private val antiKick     = register(Settings.b("PauseBasedAntiKick", false))
    private val antiKickDelay = register(Settings.integerBuilder("PauseTicks").withValue(5).withRange(1, 100).build())
    private val antiKickOff  = register(Settings.integerBuilder("TicksBetweenPause").withValue(20).withRange(1, 200).build())

    // ── Render ────────────────────────────────────────────────────────────────
    private val renderNext   = register(Settings.b("RenderNextBlock", true))
    private val renderColor  = register(Settings.c("RenderColor", Color(255, 0, 255, 80)))

    // ── Runtime state ─────────────────────────────────────────────────────────
    private var paused       = false
    private var playerPos    = BlockPos.ORIGIN
    private var renderPos    = BlockPos.ORIGIN
    private var wasFacing    = EnumFacing.NORTH
    private var prevPitch    = 0
    private var speed        = 0
    private var go           = true
    private var cookie       = 0
    private var cookieYaw    = 0f
    private var delayLeft    = 0
    private var offLeft      = 0
    private var justSwapped  = false
    private var graceTicks   = 0
    private var lastSlot     = -1

    // ─────────────────────────────────────────────────────────────────────────
    override fun onEnable() {
        if (mc.player == null) return
        lastSlot = mc.player.inventory.currentItem

        if (startPaused.value) {
            paused = false
            MessageSendHelper.sendChatMessage("[AutoMountain] Press UseKey (RightClick) to build stairs!")
        } else {
            snapPlayerToGround()
            wasFacing = mc.player.horizontalFacing
            prevPitch = Math.round(mc.player.rotationPitch)
            if (swapStack.value) swapToValidBlock()
            mc.player.motionX = 0.0; mc.player.motionY = 0.0; mc.player.motionZ = 0.0
            centerPlayer()
            paused = true
            MessageSendHelper.sendChatMessage("[AutoMountain] Building stairs!")
        }

        playerPos = mc.player.position
        renderPos = mc.player.position
        delayLeft = antiKickDelay.value
        offLeft   = antiKickOff.value
    }

    override fun onDisable() {
        if (mc.player == null) return
        mc.player.noClip = false
        mc.player.capabilities.isFlying = false
        speed = 0
    }

    // ── Packet spoofing: always tell server we are on ground ─────────────────
    @SubscribeEvent
    fun onPacketSend(event: PacketEvent.Send) {
        val pkt = event.packet
        if (pkt is CPacketPlayer) {
            try {
                val f = CPacketPlayer::class.java.getDeclaredField("onGround")
                f.isAccessible = true
                f.set(pkt, true)
            } catch (_: Exception) {}
        }
    }

    // ── Main tick ─────────────────────────────────────────────────────────────
    @SubscribeEvent
    fun onTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.START) return
        if (mc.player == null || mc.world == null) return

        playerPos = mc.player.position
        renderPos = if (mc.player.posY % 1.0 != 0.0)
            BlockPos(mc.player.posX.toInt(), mc.player.posY.toInt() + 1, mc.player.posZ.toInt())
        else
            mc.player.position

        // Right-click toggle (use key)
        if (mc.gameSettings.keyBindUseItem.isKeyDown && !wasPressedLastTick) {
            togglePause()
        }
        wasPressedLastTick = mc.gameSettings.keyBindUseItem.isKeyDown

        if (!paused) {
            wasFacing = mc.player.horizontalFacing
            prevPitch = Math.round(mc.player.rotationPitch)
            return
        }

        // Lock facing for MountainMakerBot equivalent (always north-locked removed — keep facing)
        if (!antiKick.value) {
            offLeft = Int.MAX_VALUE / 2
            delayLeft = 0
        } else if (offLeft > antiKickOff.value) {
            offLeft = antiKickOff.value
        }

        // Keep player snapped and still
        mc.player.motionX = 0.0; mc.player.motionY = 0.0; mc.player.motionZ = 0.0
        centerPlayer()
        mc.player.setPosition(mc.player.posX, Math.round(mc.player.posY) + 0.25, mc.player.posZ)

        // Disable flight modules if active
        mc.moduleManager?.getModuleT(Flight::class.java)?.let { if (it.isEnabled) it.toggle() }

        // Swap block stacks
        if (swapStack.value) {
            val prevSelected = mc.player.inventory.currentItem
            swapToValidBlock()
            val newSelected = mc.player.inventory.currentItem
            if (newSelected != lastSlot && newSelected != prevSelected) {
                justSwapped = true
                graceTicks = swapPauseTicks.value
                lastSlot = newSelected
            }
        }

        // Placement delay
        if (speed < placementDelay.value) { go = false; speed++ } else { speed = 0; go = true }

        // Swap grace period
        if (justSwapped) {
            graceTicks--
            if (graceTicks > 0) {
                go = false; speed = 0
                mc.player.motionX = 0.0; mc.player.motionY = 0.0; mc.player.motionZ = 0.0
                centerPlayer()
                mc.player.setPosition(mc.player.posX, Math.round(mc.player.posY) + 0.25, mc.player.posZ)
                return
            } else justSwapped = false
        }

        if (!go) return
        if (isInvalidBlock(mc.player.heldItemMainhand)) return

        val pitchUp = if (mouseT.value) mc.player.rotationPitch <= 40f else prevPitch <= 40

        if (pitchUp) {
            if (!checkLimitsUp()) return
            buildUp()
            if (mc.player.posY >= upLimit.value - 1 && invertUp.value) setPitch(75)
        } else {
            if (!checkLimitsDown()) return
            buildDown()
            if (mc.player.posY <= downLimit.value + 1 && invertDown.value) setPitch(35)
        }

        centerPlayer()
    }

    // ── Render ────────────────────────────────────────────────────────────────
    @SubscribeEvent
    fun onRenderWorld(event: RenderWorldEvent) {
        if (!renderNext.value || mc.player == null) return
        val c = renderColor.value
        val color = ColorHolder(c.red, c.green, c.blue, c.alpha)
        val renderer = ESPRenderer()
        renderer.aFilled = color.a
        renderer.aOutline = 200

        val pos = getNextBlockPos() ?: return
        renderer.add(pos, color)
        renderer.render(true)
    }

    // ── Build helpers ─────────────────────────────────────────────────────────
    private fun buildUp() {
        val facing = if (mouseT.value) mc.player.horizontalFacing else wasFacing
        val delta  = facingDelta(facing)

        val target = playerPos.add(delta.x, 0, delta.z)
        val un1 = playerPos.add(0, 2, 0)
        val un2 = target.add(0, 1, 0)
        val un3 = target.add(0, 2, 0)
        val un4 = target.add(0, 3, 0)

        if (listOf(un1, un2, un3, un4).all { isReplaceable(it) } && mc.world.worldBorder.contains(un2)) {
            val placePos = playerPos.add(delta.x, 0, delta.z)
            if (isReplaceable(placePos)) placeBlockAt(placePos)
            mc.player.setPosition(
                mc.player.posX + delta.x,
                mc.player.posY + 1,
                mc.player.posZ + delta.z
            )
        } else {
            if (invertUp.value) setPitch(75)
        }
    }

    private fun buildDown() {
        val facing = if (mouseT.value) mc.player.horizontalFacing else wasFacing
        val delta  = facingDelta(facing)

        val dn1 = playerPos.add(delta.x, -1, delta.z)
        val dn2 = playerPos.add(delta.x,  0, delta.z)
        val dn3 = playerPos.add(delta.x,  1, delta.z)
        val pos  = playerPos.add(delta.x, -2, delta.z)

        if (listOf(dn1, dn2, dn3).all { isReplaceable(it) } && mc.world.worldBorder.contains(dn2)) {
            if (isReplaceable(pos)) placeBlockAt(pos)
            mc.player.setPosition(
                mc.player.posX + delta.x,
                mc.player.posY - 1,
                mc.player.posZ + delta.z
            )
        } else {
            if (invertDown.value) setPitch(35)
        }
    }

    private fun getNextBlockPos(): BlockPos? {
        if (!paused) return null
        val facing = if (mouseT.value) mc.player.horizontalFacing else wasFacing
        val delta  = facingDelta(facing)
        val pitchUp = if (mouseT.value) mc.player.rotationPitch <= 40f else prevPitch <= 40
        return if (pitchUp)
            renderPos.add(delta.x, 0, delta.z)
        else
            renderPos.add(delta.x, -2, delta.z)
    }

    // ── Utility ───────────────────────────────────────────────────────────────
    private fun facingDelta(facing: EnumFacing): BlockPos = when (facing) {
        EnumFacing.NORTH -> BlockPos(0, 0, -1)
        EnumFacing.SOUTH -> BlockPos(0, 0,  1)
        EnumFacing.EAST  -> BlockPos(1, 0,  0)
        EnumFacing.WEST  -> BlockPos(-1, 0, 0)
        else             -> BlockPos(0, 0, -1)
    }

    private fun placeBlockAt(pos: BlockPos) {
        val hitVec = Vec3d(pos).addVector(0.5, 0.5, 0.5)
        val rtr = RayTraceResult(hitVec, EnumFacing.DOWN, pos)
        mc.playerController.processRightClickBlock(mc.player, mc.world, pos, EnumFacing.DOWN, hitVec, EnumHand.MAIN_HAND)
        mc.player.swingArm(EnumHand.MAIN_HAND)
    }

    private fun isReplaceable(pos: BlockPos): Boolean {
        val state = mc.world.getBlockState(pos)
        return state.block.isReplaceable(mc.world, pos) && mc.world.getBlockState(pos).block !is BlockLiquid
    }

    private fun centerPlayer() {
        val cx = Math.floor(mc.player.posX) + 0.5
        val cz = Math.floor(mc.player.posZ) + 0.5
        mc.player.setPosition(cx, mc.player.posY, cz)
    }

    private fun snapPlayerToGround() {
        mc.player.setPosition(mc.player.posX, Math.ceil(mc.player.posY), mc.player.posZ)
    }

    private fun setPitch(p: Int) {
        if (mouseT.value) mc.player.rotationPitch = p.toFloat()
        else prevPitch = p
    }

    private fun checkLimitsUp(): Boolean {
        if (mc.player.posY > upLimit.value && !invertUp.value) return false
        if (delayLeft > 0) { delayLeft--; return false }
        if (offLeft <= 0) { delayLeft = antiKickDelay.value; offLeft = antiKickOff.value; return false }
        offLeft--
        return true
    }

    private fun checkLimitsDown(): Boolean {
        if (mc.player.posY < downLimit.value && !invertDown.value) return false
        if (delayLeft > 0) { delayLeft--; return false }
        if (offLeft <= 0) { delayLeft = antiKickDelay.value; offLeft = antiKickOff.value; return false }
        offLeft--
        return true
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

    private fun togglePause() {
        if (paused) {
            snapPlayerToGround()
        } else {
            val pos = playerPos.add(0, -1, 0)
            if (isReplaceable(pos)) placeBlockAt(pos)
        }
        paused = !paused
        mc.player.motionX = 0.0; mc.player.motionY = 0.0; mc.player.motionZ = 0.0
        speed = 0
    }

    private fun isInvalidBlock(stack: net.minecraft.item.ItemStack): Boolean {
        if (stack.isEmpty) return true
        val item = stack.item
        if (item !is ItemBlock) return true
        if (item is ItemBed)         return true
        if (item is ItemSkull)       return true
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
            || block is BlockSugarCane
            || block is BlockKelp
            || block is BlockLadder
            || block is BlockTNT
            || block is BlockCake
            || block is BlockWeb
            || block is BlockFlowerPot
    }

    private var wasPressedLastTick = false
}
