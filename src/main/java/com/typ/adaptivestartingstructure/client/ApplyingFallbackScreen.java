package com.typ.adaptivestartingstructure.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ApplyingFallbackScreen extends Screen {
    ApplyingFallbackScreen() {
        super(Component.translatable(
                "screen.adaptive_starting_structure.fallback.applying"));
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
        graphics.drawCenteredString(
                this.font,
                this.title,
                this.width / 2,
                this.height / 2 - 5,
                0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
