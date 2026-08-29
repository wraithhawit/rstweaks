package com.wraithhawit.rstweaks.iface.client;

import java.util.List;
import java.util.function.BooleanSupplier;

import com.refinedmods.refinedstorage.common.support.widget.AbstractSideButtonWidget;

import com.wraithhawit.rstweaks.RSTweaks;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

/**
 * Turns the player inventory into something you click to choose which slots auto-insert may take
 * from.
 *
 * <p>Purely a screen mode, which is why it is not a {@code ClientProperty} like the others: nothing
 * about it belongs on the item or needs to reach the server. What the clicks it enables produce
 * does, and that travels as its own packet.
 */
final class ChooseSlotsSideButtonWidget extends AbstractSideButtonWidget {
    private static final MutableComponent TITLE =
        Component.translatable("gui.rstweaks.inventory_interface.choose_slots");
    private static final List<MutableComponent> ON = List.of(
        Component.translatable("gui.rstweaks.inventory_interface.choose_slots.on")
            .withStyle(ChatFormatting.GRAY));
    private static final List<MutableComponent> OFF = List.of(
        Component.translatable("gui.rstweaks.inventory_interface.choose_slots.off")
            .withStyle(ChatFormatting.GRAY));
    private static final Component HELP =
        Component.translatable("gui.rstweaks.inventory_interface.choose_slots.help");

    private static final ResourceLocation YES = sprite("yes");
    private static final ResourceLocation NO = sprite("no");

    private final BooleanSupplier active;

    ChooseSlotsSideButtonWidget(final BooleanSupplier active, final Runnable toggle) {
        super(button -> toggle.run());
        this.active = active;
    }

    @Override
    protected ResourceLocation getSprite() {
        return active.getAsBoolean() ? YES : NO;
    }

    @Override
    protected MutableComponent getTitle() {
        return TITLE;
    }

    @Override
    protected List<MutableComponent> getSubText() {
        return active.getAsBoolean() ? ON : OFF;
    }

    @Override
    protected Component getHelpText() {
        return HELP;
    }

    private static ResourceLocation sprite(final String path) {
        return ResourceLocation.fromNamespaceAndPath(
            RSTweaks.MODID, "widget/side_button/choose_slots/" + path);
    }
}
