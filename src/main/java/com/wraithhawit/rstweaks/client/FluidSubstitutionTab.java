package com.wraithhawit.rstweaks.client;

import com.refinedmods.refinedstorage.common.support.widget.CustomButton;
import com.refinedmods.refinedstorage.common.util.IdentifierUtil;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The fifth tab down the right-hand side of the Pattern Grid.
 *
 * <p>Drawn to match Refined Storage's own four exactly — same sprite set, same 16x16 at
 * the same 19-pixel pitch — because a tab that looks bolted on is worse than no tab. The
 * icon is a lava bucket, since a bucket of something becoming an empty bucket and a
 * bucket's worth of fluid is the whole feature.
 *
 * <p>Not a fifth {@code PatternType} constant. That enum is held in {@code EnumMap}s and
 * its {@code values()} is captured at static-init by {@code PatternState}'s codecs, so
 * adding a constant risks patterns already saved in a world failing to load. This is our
 * own widget instead; what it selects is still a processing pattern, which is what the
 * fluid substitution resolver already recognises.
 */
public class FluidSubstitutionTab extends CustomButton {
    private static final WidgetSprites SPRITES = new WidgetSprites(
        IdentifierUtil.createIdentifier("widget/generic_small_button"),
        IdentifierUtil.createIdentifier("widget/generic_small_button_disabled"),
        IdentifierUtil.createIdentifier("widget/generic_small_button_focused"),
        IdentifierUtil.createIdentifier("widget/generic_small_button_disabled")
    );
    private static final Component NAME =
        Component.translatable("misc.rstweaks.pattern.fluid_substitution");
    private static final ItemStack ICON = Items.LAVA_BUCKET.getDefaultInstance();

    private boolean selected;

    public FluidSubstitutionTab(final int x, final int y, final Consumer<CustomButton> onPress) {
        super(x, y, 16, 16, SPRITES, onPress, NAME);
        this.setTooltip(Tooltip.create(NAME));
    }

    public void setSelected(final boolean selected) {
        this.selected = selected;
    }

    @Override
    public void renderWidget(final GuiGraphics graphics, final int x, final int y,
                             final float partialTicks) {
        final ResourceLocation sprite = this.sprites.get(this.isActive(),
            this.isHovered() || this.selected);
        graphics.blitSprite(sprite, this.getX(), this.getY(), this.width, this.height);
        graphics.renderItem(ICON, this.getX(), this.getY());
    }
}
