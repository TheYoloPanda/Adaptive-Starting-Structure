package com.typ.adaptivestartingstructure.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import com.typ.adaptivestartingstructure.network.ContinueFallbackPayload;

public final class SafePlacementFailureScreen extends Screen {
    private static final int CONTENT_WIDTH = 420;
    private MultiLineLabel explanation =
            MultiLineLabel.EMPTY;
    private MultiLineLabel warning =
            MultiLineLabel.EMPTY;
    private final Component error;

    public SafePlacementFailureScreen() {
        this(null);
    }

    private SafePlacementFailureScreen(Component error) {
        super(Component.translatable(
                "screen.adaptive_starting_structure.fallback.title"));
        this.error = error;
    }

    public SafePlacementFailureScreen withError(
            Component nextError) {
        return new SafePlacementFailureScreen(nextError);
    }

    @Override
    protected void init() {
        int textWidth = Math.min(
                CONTENT_WIDTH,
                this.width - 40);
        this.explanation = MultiLineLabel.create(
                this.font,
                Component.translatable(
                        "screen.adaptive_starting_structure.fallback.explanation"),
                textWidth);
        this.warning = MultiLineLabel.create(
                this.font,
                Component.translatable(
                                "screen.adaptive_starting_structure.fallback.warning")
                        .withStyle(ChatFormatting.RED),
                textWidth);

        int buttonY = Math.min(
                this.height - 42,
                this.height / 2 + 72);
        this.addRenderableWidget(
                Button.builder(
                                Component.translatable(
                                        "screen.adaptive_starting_structure.fallback.delete_and_create"),
                                button -> showDeleteConfirmation())
                        .bounds(
                                this.width / 2 - 155,
                                buttonY,
                                150,
                                20)
                        .build());
        this.addRenderableWidget(
                Button.builder(
                                Component.translatable(
                                        "screen.adaptive_starting_structure.fallback.continue"),
                                button ->
                                        showContinueConfirmation())
                        .bounds(
                                this.width / 2 + 5,
                                buttonY,
                                150,
                                20)
                        .build());
    }

    private void showDeleteConfirmation() {
        this.minecraft.setScreen(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        ClientFallbackDecisionHandler
                                .deleteWorldAndCreateNew(this);
                    } else {
                        this.minecraft.setScreen(this);
                    }
                },
                Component.translatable(
                        "screen.adaptive_starting_structure.fallback.delete_confirm.title"),
                Component.translatable(
                        "screen.adaptive_starting_structure.fallback.delete_confirm.message"),
                Component.translatable(
                        "screen.adaptive_starting_structure.fallback.delete_and_create"),
                CommonComponents.GUI_CANCEL));
    }

    private void showContinueConfirmation() {
        this.minecraft.setScreen(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        this.minecraft.setScreen(
                                new ApplyingFallbackScreen());
                        PacketDistributor.sendToServer(
                                ContinueFallbackPayload.INSTANCE);
                    } else {
                        this.minecraft.setScreen(this);
                    }
                },
                Component.translatable(
                        "screen.adaptive_starting_structure.fallback.continue_confirm.title"),
                Component.translatable(
                        "screen.adaptive_starting_structure.fallback.continue_confirm.message"),
                Component.translatable(
                        "screen.adaptive_starting_structure.fallback.continue"),
                CommonComponents.GUI_CANCEL));
    }

    @Override
    public void render(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick) {
        super.render(
                graphics,
                mouseX,
                mouseY,
                partialTick);
        int centerX = this.width / 2;
        int top = Math.max(24, this.height / 2 - 92);
        graphics.drawCenteredString(
                this.font,
                this.title,
                centerX,
                top,
                0xFFFFFF);
        int explanationY = top + 24;
        this.explanation.renderCentered(
                graphics,
                centerX,
                explanationY);
        int warningY = explanationY
                + this.explanation.getLineCount() * 9
                + 16;
        this.warning.renderCentered(
                graphics,
                centerX,
                warningY,
                9,
                0xFF5555);
        if (this.error != null) {
            graphics.drawCenteredString(
                    this.font,
                    this.error,
                    centerX,
                    warningY
                            + this.warning.getLineCount() * 9
                            + 12,
                    0xFF5555);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
