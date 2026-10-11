package ru.vehiclecatalog;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod.EventBusSubscriber(modid = VehicleCatalogMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CreativeCatalogButton {
    private static final Logger LOGGER = LoggerFactory.getLogger(CreativeCatalogButton.class);

    private CreativeCatalogButton() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof CreativeModeInventoryScreen screen)) {
            return;
        }
        EditBox searchBox = screen.children().stream()
                .filter(EditBox.class::isInstance)
                .map(EditBox.class::cast)
                .findFirst()
                .orElse(null);
        int buttonWidth = 61;
        int preferredX = searchBox == null ? screen.width - buttonWidth - 2
                : searchBox.getX() + searchBox.getWidth() + 3;
        int x = Math.max(2, Math.min(preferredX + 35, screen.width - buttonWidth - 2));
        int y = searchBox == null ? 8 : searchBox.getY() - 4;
        int authorButtonWidth = buttonWidth;
        int authorX = x;
        CompletableFuture<java.util.List<CatalogEntry>> entryLoad = CatalogData.preload();
        AtomicBoolean opening = new AtomicBoolean();
        event.addListener(CatalogButton.create(
                net.minecraft.network.chat.Component.translatable("gui.vehiclecatalog.open"),
                x, y, buttonWidth, 18,
                button -> {
                    if (!opening.compareAndSet(false, true)) {
                        return;
                    }
                    button.active = false;
                    CompletableFuture<Void> cooldown = CompletableFuture.runAsync(
                            () -> { }, CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS));
                    CompletableFuture.allOf(entryLoad, cooldown).whenComplete((ignored, exception) ->
                            screen.getMinecraft().execute(() -> {
                                if (exception != null) {
                                    LOGGER.error("Could not prepare the vehicle catalog", exception);
                                    opening.set(false);
                                    button.active = true;
                                    return;
                                }
                                if (screen.getMinecraft().screen == screen) {
                                    screen.getMinecraft().setScreen(new CatalogScreen(screen, entryLoad));
                                } else {
                                    opening.set(false);
                                }
                            }));
                }));
        event.addListener(CatalogButton.create(
                net.minecraft.network.chat.Component.translatable("gui.vehiclecatalog.author.button"),
                authorX, y + 22, authorButtonWidth, 18,
                button -> screen.getMinecraft().setScreen(new AuthorScreen(screen))));
    }
}
