package ru.vehiclecatalog;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class AuthorScreen extends Screen {
    private static final int PANEL = 0xE4141C27;
    private static final int PANEL_DARK = 0xF00D1219;
    private static final int TEXT = 0xFFE8EDF5;
    private static final int AUTHOR_RED = 0xFFFF3030;

    private final Screen parent;

    public AuthorScreen(Screen parent) {
        super(Component.translatable("gui.vehiclecatalog.author.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(CatalogButton.create(Component.translatable("gui.vehiclecatalog.author.close"),
                width / 2 - 42, height / 2 + 19, 84, 20, button -> onClose()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = (width - 240) / 2;
        int top = height / 2 - 48;
        int right = left + 240;
        int bottom = top + 102;

        graphics.fill(left, top, right, bottom, PANEL);
        graphics.fill(left, top, right, top + 2, 0xFFFFB52E);
        graphics.fill(left, top, left + 2, bottom, 0xFFFFB52E);
        graphics.fill(right - 2, top, right, bottom, 0xFF080B10);
        graphics.fill(left, bottom - 2, right, bottom, 0xFF080B10);
        graphics.drawCenteredString(font, getTitle(), width / 2, top + 12, TEXT);
        graphics.fill(left + 12, top + 32, right - 12, top + 34, PANEL_DARK);
        graphics.drawCenteredString(font,
                Component.translatable("gui.vehiclecatalog.author.credit")
                        .withStyle(style -> style.withColor(AUTHOR_RED).withBold(true)),
                width / 2, top + 49, AUTHOR_RED);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
