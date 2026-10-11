package ru.vehiclecatalog;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record CatalogEntry(
        ResourceLocation id,
        Component name,
        List<String> details,
        ResourceLocation entityTypeId,
        String vehicleClassKey,
        String nationKey,
        String nationName
) {
    public CatalogEntry {
        details = List.copyOf(details);
    }
}
