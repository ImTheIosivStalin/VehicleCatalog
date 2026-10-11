package ru.vehiclecatalog;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public final class CatalogButton extends Button {
    private CatalogButton(Builder builder) {
        super(builder);
    }

    public static Button create(Component message, int x, int y, int width, int height, OnPress onPress) {
        return Button.builder(message, onPress)
                .bounds(x, y, width, height)
                .build(CatalogButton::new);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int background = !active ? 0xFF171918
                : isHoveredOrFocused() ? 0xFF303430
                : 0xFF202320;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, background);
        if (isFocused()) {
            graphics.fill(getX(), getY(), getX() + width, getY() + 1, 0xFF73796F);
            graphics.fill(getX(), getY() + height - 1, getX() + width, getY() + height, 0xFF73796F);
            graphics.fill(getX(), getY(), getX() + 1, getY() + height, 0xFF73796F);
            graphics.fill(getX() + width - 1, getY(), getX() + width, getY() + height, 0xFF73796F);
        }
        int textColor = active ? 0xFFE8EDF5 : 0xFF777B77;
        graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(),
                getX() + width / 2, getY() + (height - 8) / 2, textColor);
    }
}
