package com.wraithhawit.rstweaks.mixin;

import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.iface.BlockPickPacket;
import com.wraithhawit.rstweaks.iface.SupportedGrids;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import net.neoforged.neoforge.network.PacketDistributor;

import org.jetbrains.annotations.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes pick block reach the network when your own inventory cannot answer it.
 *
 * <p>A mixin because there is no event here. NeoForge 21.1 fires nothing around
 * {@code Minecraft.pickBlock} — the class list has {@code InputEvent.Key},
 * {@code InputEvent.MouseButton} and {@code InputEvent.InteractionKeyMappingTriggered}, and none of
 * them is this. Watching the key ourselves in a client tick would race vanilla for the same press
 * and get the modifier and screen-open rules wrong on the way.
 *
 * <p><b>It does not cancel.</b> The case it acts on is the one where vanilla does nothing at all:
 * survival, and {@code findSlotMatchingItem} came back -1. Letting the original method run to its
 * own no-op costs a few comparisons and means this injection cannot be the reason pick block stops
 * working — including for whatever else is patched into it.
 *
 * <p>The conditions are re-derived here rather than captured out of the method's locals, which is
 * the trade this makes on purpose: a local capture is pinned to where vanilla happens to put its
 * branches, and the whole method is nine lines of getters that a HEAD injection can read for
 * itself. Nothing is written and nothing is decided — the derivation only settles whether a packet
 * is worth sending, and the server derives the item again from its own copy of the block.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftPickBlockMixin {
    @Shadow
    @Nullable
    public HitResult hitResult;

    @Shadow
    @Nullable
    public LocalPlayer player;

    @Shadow
    @Nullable
    public ClientLevel level;

    @Inject(method = "pickBlock", at = @At("HEAD"))
    private void rstweaks$pickFromNetwork(final CallbackInfo ci) {
        if (!Config.blockPick || player == null || level == null) {
            return;
        }
        // Creative already hands over anything, including things no network has. Spectator is a
        // separate question and not covered by it — instabuild is false in spectator — and the
        // server refuses it too; this is only to save the round trip.
        if (player.getAbilities().instabuild || player.isSpectator()) {
            return;
        }
        if (!(hitResult instanceof BlockHitResult blockHit) || hitResult.getType() != HitResult.Type.BLOCK) {
            return;
        }
        final BlockState state = level.getBlockState(blockHit.getBlockPos());
        if (state.isAir()) {
            return;
        }
        final ItemStack wanted = state.getCloneItemStack(hitResult, level, blockHit.getBlockPos(), player);
        if (wanted.isEmpty()) {
            return;
        }
        // Already carrying it: vanilla is about to switch to that slot, which is the right answer
        // and a cheaper one than a round trip.
        if (player.getInventory().findSlotMatchingItem(wanted) != -1) {
            return;
        }
        // Checked here so that a player with no grid on them presses pick block at a block they do
        // not have and sends nothing at all. Every press on a missing block would otherwise be a
        // packet. Refined Storage's composite provider rather than an inventory scan, so a grid
        // worn in a Curios slot counts -- and it is safe on the client, because Refined Storage
        // runs the same composite here for its own open-grid keybinds.
        if (SupportedGrids.carriedBy(player).isEmpty()) {
            return;
        }
        PacketDistributor.sendToServer(new BlockPickPacket(blockHit.getBlockPos(), blockHit.getDirection()));
    }
}
