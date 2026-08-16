package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternGridScreen;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.client.FluidSubstitutionTab;
import com.wraithhawit.rstweaks.client.PatternTabHighlight;
import com.wraithhawit.rstweaks.storage.FluidSwapFillable;
import com.wraithhawit.rstweaks.storage.FluidSwapLayout;

import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds the fluid substitution tab beneath Refined Storage's four.
 *
 * <p>Two earlier attempts crashed, both for the same reason: {@code @Shadow} resolves against the
 * target class <em>only</em>, never a superclass, for fields or methods alike. So
 * {@code getBottomHeight()} on {@code AbstractGridScreen} and {@code addRenderableWidget} on
 * {@code Screen} were both out of reach, and the second attempt took the game down at launch —
 * {@code MenuScreens.init} loads every screen class during registration, so a screen mixin fails
 * before the main menu rather than when the screen opens.
 *
 * <p>This version reaches them two different ways, neither of which is a superclass shadow:
 * <ul>
 *   <li>declaring {@code extends Screen}, which is in the target's real hierarchy, so
 *       {@code addRenderableWidget} is simply inherited;</li>
 *   <li>shadowing {@code patternTypeButtons}, which <em>is</em> declared on
 *       {@code PatternGridScreen}, and taking the position from the buttons already in it.</li>
 * </ul>
 *
 * <p>Positioning from the existing buttons rather than recomputing Refined Storage's arithmetic
 * also means the tab cannot drift out of alignment if that layout ever changes.
 */
@Mixin(PatternGridScreen.class)
public abstract class PatternGridScreenMixin extends Screen {
    /** Never invoked — Mixin discards the mixin class's constructors. Present to satisfy javac. */
    private PatternGridScreenMixin(final Component title) {
        super(title);
    }

    /**
     * Declared on {@code PatternGridScreen} itself, so this shadow is legal. The value type is
     * package-private, hence the wildcards; every value is an {@link AbstractWidget} at runtime.
     */
    @Shadow
    @Final
    private Map<?, ?> patternTypeButtons;

    /** Assigned in {@code init}, so not final and null until then. */
    @Shadow
    @Nullable
    private Button clearButton;

    @Unique
    private FluidSubstitutionTab rstweaks$tab;

    /**
     * Set while we are the ones asking for the pattern type to change, so the callback below can
     * tell our own request apart from the player picking a different tab.
     */
    @Unique
    private boolean rstweaks$requesting;

    /**
     * Whether the layout has been seen live at least once since it was switched on.
     *
     * <p>Without this the first click on the tab undid itself. Selecting the tab presses Refined
     * Storage's Processing button, but the pattern type travels through a synced property, so for
     * the first frame or two {@code getPatternType()} still reports the old type — which makes the
     * laid-out slot report {@code isActive() == false}, which is exactly the signal we use to
     * notice the player leaving. The layout was therefore torn down before it ever appeared, and
     * only a second click stuck, because by then the type had caught up.
     */
    @Unique
    private boolean rstweaks$confirmed;

    /** Widgets we hid, so they can be put back if the player returns to plain processing. */
    @Unique
    private final java.util.List<AbstractWidget> rstweaks$hidden = new java.util.ArrayList<>();

    @Inject(method = "addPatternTypeButtons", at = @At("RETURN"))
    private void rstweaks$addFluidSubstitutionTab(final CallbackInfo ci) {
        if (!Config.fluidSubstitutionPatterns) {
            // A tab that makes patterns nothing will execute is worse than no tab.
            return;
        }
        int x = -1;
        int lowest = Integer.MIN_VALUE;
        for (final Object value : this.patternTypeButtons.values()) {
            if (value instanceof AbstractWidget button && button.getY() > lowest) {
                lowest = button.getY();
                x = button.getX();
            }
        }
        if (x < 0) {
            // No buttons to follow. Better to show nothing than to guess a position.
            return;
        }
        // 19 is the pitch Refined Storage steps its own tabs by.
        this.rstweaks$tab = new FluidSubstitutionTab(x, lowest + 19, button -> rstweaks$selected());
        addRenderableWidget(this.rstweaks$tab);
    }

    /**
     * A fluid substitution pattern <em>is</em> a processing pattern — one whose inputs and outputs
     * are the same container and fluid on opposite sides — so selecting our tab puts the grid into
     * processing mode and leaves the rest to the auto-fill.
     *
     * <p>It does that by pressing Refined Storage's own Processing button rather than calling
     * {@code setPatternType}, which takes the package-private {@code PatternType} and so cannot be
     * named from here. Pressing the button runs exactly the code a player clicking it would, which
     * is also the version least likely to drift if that path changes.
     */
    @Unique
    private void rstweaks$selected() {
        this.rstweaks$requesting = true;
        try {
            for (final Map.Entry<?, ?> entry : this.patternTypeButtons.entrySet()) {
                if ("PROCESSING".equals(String.valueOf(entry.getKey()))
                    && entry.getValue() instanceof Button processing) {
                    processing.onPress();
                    break;
                }
            }
        } finally {
            this.rstweaks$requesting = false;
        }
        // Applied after the press, not before: switching type is what would otherwise reposition
        // the slots out from under us.
        if (rstweaks$layout(true)) {
            FluidSwapLayout.active = true;
            this.rstweaks$tab.setSelected(true);
            rstweaks$tellServerFluidTab(true);
        }
    }

    /**
     * Tells the server the tab has opened or closed, which is the only thing that lets auto-fill
     * stay out of Refined Storage's own Processing tab.
     *
     * <p>The fill has to run server-side — the matrix containers are synced server-to-client, so
     * anything written here is overwritten on the next broadcast — and which tab is showing never
     * otherwise leaves the client. A menu button click is the cheapest honest way to say it: a
     * vanilla packet, no registration, and harmless on a server without this mod.
     */
    @Unique
    private void rstweaks$tellServerFluidTab(final boolean open) {
        final LocalPlayer player = Minecraft.getInstance().player;
        final MultiPlayerGameMode gameMode = Minecraft.getInstance().gameMode;
        if (player == null || gameMode == null
            || !(player.containerMenu instanceof FluidSwapFillable)) {
            return;
        }
        final int container = player.containerMenu.containerId;
        // Said once per change, and once per grid regardless. Every message now moves a pattern
        // between the live matrix and the stash, so repeating one swaps the two tabs' contents for
        // no reason -- and opening a grid used to announce a close and an open in the same tick.
        if (container == this.rstweaks$announcedFor && open == this.rstweaks$announced) {
            return;
        }
        this.rstweaks$announcedFor = container;
        this.rstweaks$announced = open;
        gameMode.handleInventoryButtonClick(container,
            open ? FluidSwapFillable.RSTWEAKS_FLUID_TAB_ON
                : FluidSwapFillable.RSTWEAKS_FLUID_TAB_OFF);
    }

    /** The container id the tab state below was last announced for; -1 before anything was said. */
    @Unique
    private int rstweaks$announcedFor = -1;

    @Unique
    private boolean rstweaks$announced;

    /**
     * Puts Refined Storage's Processing tab out while ours is lit, and lights it again on the way
     * back.
     *
     * <p>Both used to show as selected, on the reasoning that a fluid substitution pattern really
     * is a processing pattern — and that a dark Processing button would look clickable but do
     * nothing, since the pattern type is already PROCESSING and setting it again fires no change.
     * The second half of that stopped being true when {@code mouseClicked} below started
     * intercepting clicks on Refined Storage's own tabs, which is what makes the button work now.
     * Two tabs lit at once only ever read as a bug.
     *
     * <p>Reasserted every frame rather than set once. The pattern type travels through a synced
     * property, so Refined Storage's own {@code patternTypeChanged} can arrive a frame or two after
     * our tab is selected and light the button straight back up.
     *
     * <p>Leaving is noticed rather than intercepted. {@code patternTypeChanged} would be the
     * obvious hook, but its only parameter is the package-private {@code PatternType}, so no
     * handler we can write in Java matches its descriptor. Instead we watch a public signal that
     * says the same thing: a processing matrix slot reports {@code isActive()} only while the grid
     * is in processing mode, so once our laid-out slot goes quiet, the player has moved on.
     */
    /**
     * Resets on every {@code init}, which covers opening a different Pattern Grid and resizing the
     * window. The flag is static — one screen is open at a time — so leaving it set would show the
     * next grid a layout its slots are not actually in.
     */
    @Inject(method = "init", at = @At("RETURN"))
    private void rstweaks$resetFluidSubstitution(final CallbackInfo ci) {
        rstweaks$clearFluidSubstitution();
        // The server is the authority on which tab this grid was left on, and says so through a
        // menu property. Asking it replaces the old guess -- inspecting the matrix for something
        // that looks like a swap -- which was wrong twice over: the matrix the client is sent at
        // open is always Refined Storage's Processing one whatever tab is live, and an empty fluid
        // tab has nothing to recognise even when it is sent the right one.
        final FluidSwapFillable menu = rstweaks$menu();
        if (this.rstweaks$tab != null && menu != null
            && (menu.rstweaks$serverSaysFluidTab() || menu.rstweaks$holdsFluidSwap())) {
            rstweaks$selected();
            return;
        }
        // Deliberately NOT announcing Processing here any more. Saying it unprompted is what
        // destroyed the memory: the server had bound the fluid matrix, the client decided from the
        // contents that this was Processing, and told it so -- overwriting the flag the block
        // entity had just loaded. The server already knows; it only needs telling when the player
        // actually clicks a tab.
    }

    /**
     * Lights or darkens Refined Storage's Processing tab. Ours is not in that map, so it cannot be
     * hit by accident.
     */
    @Unique
    private void rstweaks$highlightProcessing(final boolean highlighted) {
        for (final Map.Entry<?, ?> entry : this.patternTypeButtons.entrySet()) {
            if ("PROCESSING".equals(String.valueOf(entry.getKey()))
                && entry.getValue() instanceof PatternTabHighlight button) {
                button.rstweaks$setHighlighted(highlighted);
                return;
            }
        }
    }

    @Unique
    private void rstweaks$clearFluidSubstitution() {
        // Only put the gutter buttons back if the grid is still on PROCESSING -- which is exactly
        // the case this misses otherwise, the player clicking Processing to get out. When they
        // leave to another pattern type instead, those buttons have already set their own
        // visibility from patternTypeChanged, and restoring them would show a scrollbar on a
        // crafting grid.
        final boolean stillProcessing = rstweaks$layoutStillShowing();
        if (stillProcessing) {
            for (final AbstractWidget widget : this.rstweaks$hidden) {
                widget.visible = true;
            }
        }
        // Only when the grid stays on PROCESSING -- which is where clicking Processing to leave our
        // tab lands, and where nothing else would ever light that button again, since setting the
        // pattern type to what it already is fires no change. On the way to another type, Refined
        // Storage's own patternTypeChanged sets every tab, so leave it to do that.
        rstweaks$highlightProcessing(stillProcessing);
        this.rstweaks$hidden.clear();
        FluidSwapLayout.active = false;
        this.rstweaks$confirmed = false;
        rstweaks$layout(false);
        if (this.rstweaks$tab != null) {
            this.rstweaks$tab.setSelected(false);
        }
        // Deliberately does not tell the server. This also runs on the way in, from init, where
        // announcing a close before the tab has been chosen would swap the grid's two patterns and
        // swap them straight back. Every caller that is really a departure says so itself.
    }

    /**
     * The slots belong to the menu, so the menu does the moving. It is reached through the player
     * rather than {@code getMenu()}, which is declared on {@code AbstractContainerScreen} — a
     * superclass, and therefore not something this mixin can shadow.
     */
    /**
     * Hides the extra buttons that belong to the 9x9 matrix, without naming any of them.
     *
     * <p>Refined Storage puts the Clear button in a gutter column beside the processing inset, and
     * other mods add to the same column — Cable Tiers' sided input button sits directly under it.
     * Anything sharing that column is there to operate on a 9x9 matrix that the fluid layout does
     * not have, so all of it goes except Clear, which still does something useful.
     *
     * <p>Matching on the column rather than the class is what keeps this working for a mod we do
     * not depend on and cannot reference. Visibility is not restored on the way out: every one of
     * these buttons sets its own {@code visible} from {@code patternTypeChanged}, which fires
     * exactly when the player leaves, so putting them back would fight the owner.
     */
    @Unique
    private void rstweaks$hideGutterButtons() {
        if (this.clearButton == null) {
            return;
        }
        final int gutter = this.clearButton.getX();
        for (final Renderable renderable : this.renderables) {
            if (renderable instanceof AbstractWidget widget
                && widget != this.clearButton
                && widget.getX() == gutter
                && widget.visible) {
                widget.visible = false;
                this.rstweaks$hidden.add(widget);
            }
        }
    }

    /**
     * Leaves fluid substitution when any of Refined Storage's own pattern type buttons is clicked.
     *
     * <p>Needed because selecting our tab puts the grid into PROCESSING, so clicking the Processing
     * button afterwards sets the pattern type to what it already is. No change means no
     * {@code patternTypeChanged}, which means the state signal we watch never moves — the button
     * looked live and did nothing, and the grid stayed on our layout.
     *
     * <p>Runs at HEAD, before the button is pressed, and ignores our own tab because that is not in
     * Refined Storage's map. Clearing early is harmless for the other types, which would have
     * cleared a frame later anyway.
     */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void rstweaks$leaveOnPatternTypeClick(final double mouseX,
                                                final double mouseY,
                                                final int button,
                                                final CallbackInfoReturnable<Boolean> cir) {
        if (!FluidSwapLayout.active) {
            return;
        }
        for (final Object value : this.patternTypeButtons.values()) {
            if (value instanceof AbstractWidget widget && widget.isMouseOver(mouseX, mouseY)) {
                rstweaks$clearFluidSubstitution();
                rstweaks$tellServerFluidTab(false);
                return;
            }
        }
    }

    @Unique
    private boolean rstweaks$layout(final boolean on) {
        final FluidSwapFillable menu = rstweaks$menu();
        return menu != null && menu.rstweaks$applyFluidLayout(on);
    }

    @Unique
    private boolean rstweaks$layoutStillShowing() {
        final FluidSwapFillable menu = rstweaks$menu();
        return menu != null && menu.rstweaks$fluidLayoutVisible();
    }

    @Unique
    @Nullable
    private FluidSwapFillable rstweaks$menu() {
        final LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !(player.containerMenu instanceof FluidSwapFillable fillable)) {
            return null;
        }
        return fillable;
    }

    /**
     * Extends the tab column so the fifth tab sits inside the frame rather than hanging off the
     * bottom of it.
     *
     * <p>Refined Storage's side panel is painted into {@code pattern_grid.png} and stops after the
     * four tabs it knows about, so ours was drawn on bare background. Rather than invent artwork,
     * a clean 19-pixel slice of the panel is copied down to sit behind our tab, and the panel's own
     * bottom edge is copied below it. Measured off the texture: the strip runs x 168-192, the body
     * is uniform through v 73-152, and the closing edge is the three rows at v 153.
     *
     * <p>All of it is positioned from the tab widget itself — the tab sits at {@code leftPos + 172},
     * so everything else follows from {@code getX()} and {@code getY()}. That avoids reaching for
     * {@code leftPos} and {@code topPos}, which live on a superclass and are exactly what broke the
     * two previous attempts at this file.
     */
    @Inject(method = "renderBg", at = @At("RETURN"))
    private void rstweaks$extendTabColumn(final GuiGraphics graphics,
                                         final float partialTicks,
                                         final int mouseX,
                                         final int mouseY,
                                         final CallbackInfo ci) {
        if (FluidSwapLayout.active) {
            if (rstweaks$layoutStillShowing()) {
                // Cheap unless the direction changed: the pattern flips to two inputs and one
                // output the moment a fluid goes in, so the arrangement tracks what is encoded.
                this.rstweaks$confirmed = true;
                rstweaks$layout(true);
                rstweaks$hideGutterButtons();
                rstweaks$highlightProcessing(false);
            } else if (this.rstweaks$confirmed) {
                rstweaks$clearFluidSubstitution();
                rstweaks$tellServerFluidTab(false);
            }
        }
        if (this.rstweaks$tab == null) {
            return;
        }
        final int x = this.rstweaks$tab.getX() - RSTWEAKS_TAB_INSET;
        // The panel's last body row is one pixel above our tab.
        graphics.blit(RSTWEAKS_TEXTURE, x, this.rstweaks$tab.getY() - 1,
            RSTWEAKS_STRIP_U, RSTWEAKS_BODY_V, RSTWEAKS_STRIP_W, RSTWEAKS_ROW_H, 256, 256);
        graphics.blit(RSTWEAKS_TEXTURE, x, this.rstweaks$tab.getY() + RSTWEAKS_ROW_H - 1,
            RSTWEAKS_STRIP_U, RSTWEAKS_EDGE_V, RSTWEAKS_STRIP_W, RSTWEAKS_EDGE_H, 256, 256);
    }

    @Unique
    private static final ResourceLocation RSTWEAKS_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "textures/gui/pattern_grid.png");
    /**
     * Tabs sit at leftPos + 172, and the slice starts at 169 rather than 168 on purpose.
     * Column 168 carries a white highlight, but only in the bands beside the recessed insets
     * higher up the panel (v 87-103 and v 123-139) -- there is no such highlight at the height
     * we are extending, so copying it painted a stray white line down the left of the new
     * section. Column 169 is uniform C6C6C6 from v 60 to 156, and leaving 168 unpainted lets
     * the panel already underneath show through, which is the right colour anyway.
     */
    @Unique
    private static final int RSTWEAKS_TAB_INSET = 3;
    @Unique
    private static final int RSTWEAKS_STRIP_U = 169;
    @Unique
    private static final int RSTWEAKS_STRIP_W = 24;
    /** Any row in the uniform stretch of panel body. */
    @Unique
    private static final int RSTWEAKS_BODY_V = 120;
    @Unique
    private static final int RSTWEAKS_ROW_H = 19;
    @Unique
    private static final int RSTWEAKS_EDGE_V = 153;
    @Unique
    private static final int RSTWEAKS_EDGE_H = 3;
}
