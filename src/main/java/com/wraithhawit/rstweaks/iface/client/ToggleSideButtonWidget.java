package com.wraithhawit.rstweaks.iface.client;

import com.refinedmods.refinedstorage.common.support.containermenu.ClientProperty;
import com.refinedmods.refinedstorage.common.support.widget.AbstractYesNoSideButtonWidget;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

/**
 * The auto-insert and auto-export toggles.
 *
 * <p>Refined Storage's {@link AbstractYesNoSideButtonWidget} already draws a boolean property as a
 * side button with a yes/no subtitle and sends the change on click; all that is left is the sprite
 * pair and the help line. These are the only two sprites this feature needs that Refined Storage
 * does not already ship.
 */
final class ToggleSideButtonWidget extends AbstractYesNoSideButtonWidget {
    private final Component help;

    ToggleSideButtonWidget(final ClientProperty<Boolean> property,
                           final MutableComponent title,
                           final ResourceLocation yesSprite,
                           final ResourceLocation noSprite,
                           final Component help) {
        super(property, title, yesSprite, noSprite);
        this.help = help;
    }

    @Override
    protected Component getHelpText() {
        return help;
    }
}
