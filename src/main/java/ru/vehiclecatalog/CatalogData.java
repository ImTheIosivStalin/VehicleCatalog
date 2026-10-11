package ru.vehiclecatalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

final class CatalogData {
    private static final Logger LOGGER = LoggerFactory.getLogger(CatalogData.class);
    private static volatile CompletableFuture<List<CatalogEntry>> cachedLoad;

    private CatalogData() {
    }

    static CompletableFuture<List<CatalogEntry>> preload() {
        CompletableFuture<List<CatalogEntry>> load = cachedLoad;
        if (load != null) {
            return load;
        }
        synchronized (CatalogData.class) {
            if (cachedLoad == null) {
                cachedLoad = CompletableFuture.supplyAsync(CatalogData::load);
            }
            return cachedLoad;
        }
    }

    static List<CatalogEntry> load() {
        Map<ResourceLocation, Resource> resources = Minecraft.getInstance().getResourceManager()
                .listResources("sbw/vehicles", id -> id.getPath().endsWith(".json"));
        Map<ResourceLocation, CatalogEntry> vehicles = new LinkedHashMap<>();

        for (Map.Entry<ResourceLocation, Resource> resourceEntry : resources.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            ResourceLocation fileId = resourceEntry.getKey();
            try (Reader reader = resourceEntry.getValue().openAsReader()) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) {
                    continue;
                }
                JsonObject config = parsed.getAsJsonObject();
                ResourceLocation id = readId(config, fileId);
                vehicles.putIfAbsent(id, vehicleEntry(id, config));
            } catch (Exception exception) {
                LOGGER.warn("Skipping unreadable vehicle config {}", fileId, exception);
            }
        }

        loadModFileConfigs(vehicles);
        loadExternalConfigFiles(vehicles);

        List<CatalogEntry> entries = new ArrayList<>(vehicles.values().stream()
                .filter(entry -> !isExcludedCatalogEntry(entry.id()))
                .toList());
        entries.sort(Comparator.comparing((CatalogEntry entry) -> entry.name().getString(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.id().toString()));
        LOGGER.info("Loaded {} vehicle catalog entries from active game resources and installed mods", entries.size());
        return List.copyOf(entries);
    }

    private static void loadModFileConfigs(Map<ResourceLocation, CatalogEntry> vehicles) {
        for (IModFileInfo modFileInfo : ModList.get().getModFiles()) {
            for (IModInfo modInfo : modFileInfo.getMods()) {
                String namespace = modInfo.getModId();
                loadModFileConfigs(vehicles, modFileInfo, namespace, "assets");
                loadModFileConfigs(vehicles, modFileInfo, namespace, "data");
            }
        }
    }

    private static void loadModFileConfigs(Map<ResourceLocation, CatalogEntry> vehicles,
                                          IModFileInfo modFileInfo, String namespace, String packType) {
        Path root = modFileInfo.getFile().findResource(packType, namespace);
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        int entriesBeforeScan = vehicles.size();
        int configsRead = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".json"))
                    .sorted().toList()) {
                String relativePath = root.relativize(path).toString().replace('\\', '/');
                if (!isVehicleConfigPath(relativePath)) {
                    continue;
                }
                ResourceLocation fileId = new ResourceLocation(namespace, relativePath);
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed.isJsonObject() && isVehicleConfig(parsed.getAsJsonObject())) {
                        JsonObject config = parsed.getAsJsonObject();
                        ResourceLocation id = readId(config, fileId);
                        CatalogEntry entry = vehicleEntry(id, config);
                        if (packType.equals("data")) {
                            vehicles.put(id, entry);
                        } else {
                            vehicles.putIfAbsent(id, entry);
                        }
                        configsRead++;
                    }
                } catch (Exception exception) {
                    LOGGER.warn("Skipping unreadable vehicle config in {}: {}", modFileInfo.getFile().getFileName(),
                            path, exception);
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("Could not scan vehicle configs for mod {} in {} resources", namespace, packType, exception);
        }
        if (configsRead > 0) {
            LOGGER.info("Read {} vehicle config(s) from {}:{} ({} new catalog entries)",
                    configsRead, namespace, packType, vehicles.size() - entriesBeforeScan);
        }
    }

    /**
     * SuperbWarfare and some addon builds store their active vehicle JSON outside
     * the mod jar, under the instance config directory. Scan those files too.
     */
    private static void loadExternalConfigFiles(Map<ResourceLocation, CatalogEntry> vehicles) {
        Path configRoot = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        if (!Files.isDirectory(configRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(configRoot, 8)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .filter(file -> {
                        String rel = configRoot.relativize(file).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                        return rel.contains("sbw") || rel.contains("vehicle") || rel.contains("vehicles");
                    }).sorted().toList()) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed.isJsonObject() && isVehicleConfig(parsed.getAsJsonObject())) {
                        JsonObject config = parsed.getAsJsonObject();
                        ResourceLocation fallback = new ResourceLocation("superbwarfare",
                                path.getFileName().toString().replaceFirst("(?i)\\.json$", "").toLowerCase(Locale.ROOT));
                        ResourceLocation id = readId(config, fallback);
                        // Prefer active config-directory values over bundled defaults.
                        vehicles.put(id, vehicleEntry(id, config));
                    }
                } catch (Exception exception) {
                    LOGGER.debug("Skipping non-vehicle JSON in config directory: {}", path, exception);
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("Could not scan external vehicle config files under {}", configRoot, exception);
        }
    }

    private static boolean isVehicleConfigPath(String relativePath) {
        String normalized = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.startsWith("sbw/vehicles/")
                || normalized.contains("/vehicles/")
                || normalized.contains("/vehicle/")
                || normalized.startsWith("vehicles/")
                || normalized.startsWith("vehicle/");
    }

    private static boolean isVehicleConfig(JsonObject config) {
        if (config.has("ID") && (!config.get("ID").isJsonPrimitive()
                || ResourceLocation.tryParse(config.get("ID").getAsString()) == null)) {
            return false;
        }
        int vehicleMarkers = 0;
        for (String key : List.of("Weapons", "EngineInfo", "EngineType", "MaxHealth",
                "MaxEnergy", "VehicleContainerType", "MaxForwardSpeedRate")) {
            if (containsField(config, key)) {
                vehicleMarkers++;
            }
        }
        return vehicleMarkers >= 2 || containsField(config, "Weapons");
    }

    private static ResourceLocation readId(JsonObject config, ResourceLocation fileId) {
        if (config.has("ID") && config.get("ID").isJsonPrimitive()) {
            ResourceLocation parsed = ResourceLocation.tryParse(config.get("ID").getAsString());
            if (parsed != null) {
                return parsed;
            }
        }
        String path = fileId.getPath();
        int vehicleSegment = path.lastIndexOf("vehicles/");
        String vehiclePath = vehicleSegment >= 0
                ? path.substring(vehicleSegment + "vehicles/".length(), path.length() - ".json".length())
                : path.substring(0, path.length() - ".json".length());
        return new ResourceLocation(fileId.getNamespace(), vehiclePath);
    }

    private static CatalogEntry vehicleEntry(ResourceLocation id, JsonObject config) {
        ResourceLocation entityId = config.has("ID") && config.get("ID").isJsonPrimitive()
                ? ResourceLocation.tryParse(config.get("ID").getAsString())
                : id;
        List<String> details = new ArrayList<>();
        String vehicleClassKey = vehicleClassKey(id);
        String nationName = resolveNation(id, config);
        details.add(Component.translatable("gui.vehiclecatalog.class").getString() + ": "
                + Component.translatable(vehicleClassKey).getString());
        addNation(details, nationName);
        addMainCharacteristics(details, config);

        Component name = resolveVehicleName(id);
        return new CatalogEntry(id, name, details, entityId, vehicleClassKey, nationKey(id, nationName), nationName);
    }

    private static void addMainCharacteristics(List<String> details, JsonObject config) {
        addMainValue(details, config, "MaxHealth", "gui.vehiclecatalog.health");
        addMainValue(details, config, "Mass", "gui.vehiclecatalog.mass");

        JsonObject engineInfo = objectAt(config, "EngineInfo");
        addMainValue(details, engineInfo.has("MaxForwardSpeedRate") ? engineInfo : config,
                "MaxForwardSpeedRate", "gui.vehiclecatalog.forward_rate");
        addMainValue(details, engineInfo.has("MaxBackwardSpeedRate") ? engineInfo : config,
                "MaxBackwardSpeedRate", "gui.vehiclecatalog.backward_rate");

        JsonObject weapons = objectAt(config, "Weapons");
        boolean hasApDamage = false;
        boolean hasHeDamage = false;
        for (Map.Entry<String, JsonElement> entry : weapons.entrySet()) {
            if (entry.getKey().startsWith("@") || !entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject weapon = resolveWeapon(weapons, entry.getKey(), new HashSet<>());
            String weaponLabel = weaponDisplayName(entry.getKey(), weapon);
            details.add(Component.translatable("gui.vehiclecatalog.weapon_header").getString()
                    + ": " + weaponLabel);
            String damageType = damageType(entry.getKey(), weapon);
            if ("ap".equals(damageType)) {
                hasApDamage |= addWeaponValue(details, weapon, "Damage",
                        "gui.vehiclecatalog.damage_ap", "");
            } else if ("he".equals(damageType)) {
                hasHeDamage |= addWeaponValue(details, weapon, "Damage",
                        "gui.vehiclecatalog.damage_he", "");
            }
            boolean[] ammoDamageTypes = addAmmoOverrideStats(details, weapon);
            hasApDamage |= ammoDamageTypes[0];
            hasHeDamage |= ammoDamageTypes[1];
            addWeaponValue(details, weapon, "Magazine", "gui.vehiclecatalog.magazine", "");
            addWeaponReloadTime(details, weapon);
            addWeaponValue(details, weapon, "ExplosionDamage",
                    "gui.vehiclecatalog.explosion_damage", "");
            addWeaponValue(details, weapon, "ExplosionRadius",
                    "gui.vehiclecatalog.explosion_radius", "");
            addWeaponValue(details, weapon, "Velocity",
                    "gui.vehiclecatalog.projectile_velocity", "");
        }
        if (!hasApDamage) {
            addUnavailable(details, "gui.vehiclecatalog.damage_ap");
        }
        if (!hasHeDamage) {
            addUnavailable(details, "gui.vehiclecatalog.damage_he");
        }
    }

    private static void addMainValue(List<String> details, JsonObject source, String field, String labelKey) {
        JsonElement value = source.get(field);
        String shownValue = value != null && value.isJsonPrimitive()
                ? value.getAsString()
                : Component.translatable("gui.vehiclecatalog.not_available").getString();
        details.add(Component.translatable(labelKey).getString() + ": " + shownValue);
    }

    private static boolean addWeaponValue(List<String> details, JsonObject weapon, String field,
                                          String labelKey, String weaponLabel) {
        JsonElement value = weapon.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            return false;
        }
        String label = Component.translatable(labelKey).getString();
        if (!weaponLabel.isBlank()) {
            label += " (" + weaponLabel + ")";
        }
        details.add("  " + label + ": " + value.getAsString());
        return true;
    }

    private static void addWeaponReloadTime(List<String> details, JsonObject weapon) {
        JsonElement reloadTicks = weapon.has("EmptyReloadTime")
                ? weapon.get("EmptyReloadTime")
                : weapon.get("ReloadTime");
        if (reloadTicks == null || !reloadTicks.isJsonPrimitive() || !reloadTicks.getAsJsonPrimitive().isNumber()) {
            return;
        }
        String seconds = new BigDecimal(reloadTicks.getAsString())
                .divide(BigDecimal.valueOf(20))
                .stripTrailingZeros()
                .toPlainString();
        details.add("  " + Component.translatable("gui.vehiclecatalog.reload_time")
                .getString() + ": " + seconds);
    }

    private static String weaponDisplayName(String weaponKey, JsonObject weapon) {
        String configuredName = primitiveString(weapon, "Name");
        if (!configuredName.isBlank()) {
            String translatedName = Component.translatable(configuredName).getString();
            translatedName = translatedName.replaceAll("%(?:\\d+\\$)?s", "")
                    .replaceAll("\\s*%\\s*$", "").trim();
            if (!translatedName.equals(configuredName)) {
                if (!translatedName.isBlank()) {
                    return translatedName;
                }
            }
        }
        String localizedKey = switch (weaponKey.toLowerCase(Locale.ROOT)) {
            case "cannon", "maincannon" -> "gui.vehiclecatalog.weapon.cannon";
            case "machinegun", "maingun" -> "gui.vehiclecatalog.weapon.machine_gun";
            case "mainmachinegun" -> "gui.vehiclecatalog.weapon.main_machine_gun";
            case "passengermachinegun" -> "gui.vehiclecatalog.weapon.passenger_machine_gun";
            case "missile", "rocket" -> "gui.vehiclecatalog.weapon.missile";
            case "grenadelauncher" -> "gui.vehiclecatalog.weapon.grenade_launcher";
            case "autocannon" -> "gui.vehiclecatalog.weapon.auto_cannon";
            default -> "";
        };
        return localizedKey.isEmpty()
                ? humanizeWeaponName(weaponKey)
                : Component.translatable(localizedKey).getString();
    }

    private static JsonObject resolveWeapon(JsonObject weapons, String weaponKey, Set<String> resolving) {
        JsonObject own = objectAt(weapons, weaponKey);
        if (!resolving.add(weaponKey)) {
            return own;
        }
        JsonObject resolved = new JsonObject();
        JsonElement template = own.get("Template");
        if (template != null && template.isJsonPrimitive()) {
            String templateName = template.getAsString();
            if (templateName.startsWith("@")) {
                JsonObject inherited = resolveWeapon(weapons, templateName.substring(1), resolving);
                for (Map.Entry<String, JsonElement> property : inherited.entrySet()) {
                    resolved.add(property.getKey(), property.getValue().deepCopy());
                }
            }
        }
        for (Map.Entry<String, JsonElement> property : own.entrySet()) {
            if (!property.getKey().equals("Template")) {
                resolved.add(property.getKey(), property.getValue().deepCopy());
            }
        }
        resolving.remove(weaponKey);
        return resolved;
    }

    private static boolean[] addAmmoOverrideStats(List<String> details, JsonObject weapon) {
        boolean hasApDamage = false;
        boolean hasHeDamage = false;
        JsonElement ammoTypes = weapon.get("AmmoType");
        if (ammoTypes == null || !ammoTypes.isJsonArray()) {
            return new boolean[]{false, false};
        }
        for (JsonElement ammoType : ammoTypes.getAsJsonArray()) {
            if (!ammoType.isJsonObject()) {
                continue;
            }
            JsonObject ammoConfig = ammoType.getAsJsonObject();
            JsonObject override = objectAt(ammoConfig, "Override");
            if (override.size() == 0) {
                continue;
            }
            JsonObject ammoStats = new JsonObject();
            for (Map.Entry<String, JsonElement> property : weapon.entrySet()) {
                if (!property.getKey().equals("AmmoType")) {
                    ammoStats.add(property.getKey(), property.getValue().deepCopy());
                }
            }
            for (Map.Entry<String, JsonElement> property : override.entrySet()) {
                ammoStats.add(property.getKey(), property.getValue().deepCopy());
            }
            String ammoId = primitiveString(ammoConfig, "Ammo");
            String variantLabel = localizedAmmoName(ammoId);
            String damageType = damageType(ammoId, ammoStats);
            if ("ap".equals(damageType)) {
                hasApDamage |= addWeaponValue(details, ammoStats, "Damage",
                        "gui.vehiclecatalog.damage_ap", variantLabel);
            } else if ("he".equals(damageType)) {
                hasHeDamage |= addWeaponValue(details, ammoStats, "Damage",
                        "gui.vehiclecatalog.damage_he", variantLabel);
            }
            addWeaponValue(details, ammoStats, "ExplosionDamage",
                    "gui.vehiclecatalog.explosion_damage", variantLabel);
            addWeaponValue(details, ammoStats, "ExplosionRadius",
                    "gui.vehiclecatalog.explosion_radius", variantLabel);
            addWeaponValue(details, ammoStats, "Velocity",
                    "gui.vehiclecatalog.projectile_velocity", variantLabel);
        }
        return new boolean[]{hasApDamage, hasHeDamage};
    }

    private static String localizedAmmoName(String ammoId) {
        String normalized = ammoId.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "ap_shell", "armor_piercing", "armour_piercing")) {
            return Component.translatable("gui.vehiclecatalog.ammo.ap").getString();
        }
        if (containsAny(normalized, "he_shell", "high_explosive")) {
            return Component.translatable("gui.vehiclecatalog.ammo.he").getString();
        }
        int separator = ammoId.lastIndexOf(':');
        return humanizeWeaponName(ammoId.substring(separator + 1));
    }

    private static String damageType(String weaponName, JsonObject weapon) {
        String shellType = primitiveString(weapon, "ShellType").toLowerCase(Locale.ROOT);
        String identity = (weaponName + " " + primitiveString(weapon, "Projectile") + " "
                + primitiveString(weapon, "AmmoType")).toLowerCase(Locale.ROOT);
        if (shellType.equals("ap") || shellType.equals("bb")
                || primitiveBoolean(weapon, "IsArmorPiercingProjectile")
                || containsAny(identity, "ap_shell", "shell_ap", "armor_piercing", "armour_piercing", "_bb")) {
            return "ap";
        }
        if (shellType.equals("he") || shellType.equals("of") || shellType.equals("off")
                || primitiveBoolean(weapon, "IsHighExplosiveProjectile")
                || containsAny(identity, "he_shell", "shell_he", "high_explosive", "_of", "_he")) {
            return "he";
        }
        return "";
    }

    private static String primitiveString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static boolean primitiveBoolean(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static String humanizeWeaponName(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ').trim();
    }

    private static void addUnavailable(List<String> details, String labelKey) {
        details.add(Component.translatable(labelKey).getString() + ": "
                + Component.translatable("gui.vehiclecatalog.not_available").getString());
    }

    private static boolean containsField(JsonElement value, String field) {
        if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                if (entry.getKey().equals(field) || containsField(entry.getValue(), field)) {
                    return true;
                }
            }
        } else if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                if (containsField(element, field)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String vehicleClassKey(ResourceLocation id) {
        String curatedClass = curatedClassKey(id);
        if (curatedClass != null) {
            return curatedClass;
        }
        String path = id.getPath().toLowerCase(Locale.ROOT).replace('-', '_');
        String identity = (id.getNamespace() + " " + path).toLowerCase(Locale.ROOT);

        // Classify by the vehicle's real role first; specific roles must precede generic tank/IFV matches.
        if (containsAny(identity, "bmpt", "terminator", "ramka"))
            return "gui.vehiclecatalog.class.bmpt";
        if (containsAny(identity, "pantsir", "tunguska", "shilka", "tor_m", "tor_ m", "buk_m", "s_300", "s_400",
                "patriot", "avenger", "nasams", "irist", "chaparral", "air_defense", "anti_air", "sam_system", "zsu_"))
            return "gui.vehiclecatalog.class.air_defense";
        if (containsAny(identity, "himars", "mlrs", "grad", "uragan", "smerch", "tornado_g", "tornado_s", "type_63", "msta",
                "d30", "d_30", "zis3", "2s1", "2s3", "2s19", "2s35", "pzh_2000", "krab", "archer", "fh_77", "caesar", "atm os", "atm_os"))
            return "gui.vehiclecatalog.class.artillery";
        if (containsAny(identity, "kornet", "chryzantema", "chrysanthemum", "shturm_s", "shturm", "ptrk", "anti_tank", "tank_destroyer",
                "jagdpanzer", "striker_m1134", "m901", "wiesel_tow"))
            return "gui.vehiclecatalog.class.pt_sau";
        if (containsAny(identity, "helicopter", "mi_8", "mi_17", "mi_24", "mi_28", "ka_52", "ka52", "ah_1", "ah_6", "ah_64", "uh_60",
                "uh60", "ch_47", "ch47", "nh90", "tiger", "apache", "cobra", "hind", "havoc", "blackhawk", "black_hawk", "chinook", "hokum"))
            return "gui.vehiclecatalog.class.helicopter";
        if (containsAny(identity, "aircraft", "airplane", "fighter", "bomber", "mig_", "su_", "tu_", "il_", "yak_", "f_14", "f_15", "f_16",
                "f_18", "f_22", "f_35", "a_10", "a10", "c_130", "c130", "ac_130", "j_20", "j20", "jas_39", "gripen", "eurofighter", "typhoon",
                "rafale", "mirage", "tornado", "harrier", "stuka", "ju_87", "b_2", "b_52", "su_25", "su_27", "su_30", "su_34", "su_35", "su_57"))
            return "gui.vehiclecatalog.class.airplane";
        if (containsAny(identity, "submarine", "destroyer", "frigate", "corvette", "battleship", "aircraft_carrier", "speedboat", "boat", "ship", "patrol_boat"))
            return "gui.vehiclecatalog.class.naval";
        if (containsAny(identity, "bmp", "bradley", "puma", "cv_90", "cv90", "ajax", "warrior", "marder", "lynx", "bmd", "kurganets", "kurganets_25",
                "namer", "dardo", "freccia", "as21", "redback", "kaplan"))
            return "gui.vehiclecatalog.class.bmp";
        if (containsAny(identity, "btr", "brdm", "lav_", "stryker", "m113", "varta", "mrap", "cougar", "maxxpro", "bushmaster", "ot_", "patria", "piranha",
                "fuchs", "foxhound", "mastiff", "wolfhound", "boxer", "vbci", "vab", "lav25", "lav_25", "bumerang", "boomerang"))
            return "gui.vehiclecatalog.class.apc";
        if (containsAny(identity, "uaz", "ural", "kamaz", "gaz_tigr", "tigr", "humvee", "hmmwv", "fmtv", "truck", "jeep", "pickup", "logistic", "fuel_truck",
                "cargo", "transport", "land_rover", "unimog", "zil_", "maz_", "tatra", "tractor", "ambulance"))
            return "gui.vehiclecatalog.class.transport";
        if (containsAny(identity, "tank", "t_55", "t55", "t_62", "t62", "t_64", "t64", "t_72", "t72", "t_80", "t80", "t_84", "t84", "t_90", "t90",
                "t_14", "t14", "m1a1", "m1a2", "abrams", "leopard", "challenger", "merkava", "type_10", "type_ Type", "type_99", "type_96", "ztz", "k2_black",
                "k2_", "altay", "clerc", "leclerc", "ari ete", "ariete", "centurion", "chieftain", "amx_30", "amx30", "oplot", "pt_91", "twardy"))
            return "gui.vehiclecatalog.class.tank";
        return "gui.vehiclecatalog.class.other";
    }

    private static boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) return true;
        }
        return false;
    }

    private static Component resolveVehicleName(ResourceLocation id) {
        if (id.getNamespace().equals("fcp")) {
            Component catalogName = Component.translatable("gui.vehiclecatalog.vehicle.fcp." + id.getPath());
            if (!catalogName.getString().startsWith("gui.vehiclecatalog.vehicle.fcp.")) {
                return catalogName;
            }
        }
        String key = "entity." + id.getNamespace() + "." + id.getPath();
        Component localized = Component.translatable(key);
        if (!localized.getString().equals(key)) {
            return localized;
        }
        ResourceLocation englishLanguage = new ResourceLocation(id.getNamespace(), "lang/en_us.json");
        Resource english = Minecraft.getInstance().getResourceManager().getResource(englishLanguage).orElse(null);
        if (english != null) {
            try (Reader reader = english.openAsReader()) {
                JsonElement language = JsonParser.parseReader(reader);
                if (language.isJsonObject() && language.getAsJsonObject().has(key)) {
                    return Component.literal(language.getAsJsonObject().get(key).getAsString());
                }
            } catch (Exception exception) {
                LOGGER.warn("Could not read vehicle names from {}", englishLanguage, exception);
            }
        }
        return Component.literal(prettyName(id.getPath()));
    }

    private static String prettyName(String path) {
        String[] acronyms = {"bmpt", "bmp", "btr", "brdm", "msta", "uaz", "t72", "t14", "m109",
                "m939", "mi17", "dpv", "ags", "dshk", "spg"};
        String[] words = path.replace('_', ' ').split(" ");
        List<String> displayWords = new ArrayList<>();
        for (String word : words) {
            String normalized = word.toLowerCase(Locale.ROOT);
            String display = word;
            for (String acronym : acronyms) {
                if (normalized.startsWith(acronym)) {
                    String suffix = normalized.substring(acronym.length());
                    if (!suffix.isEmpty() && Character.isDigit(suffix.charAt(0))) {
                        display = acronym.toUpperCase(Locale.ROOT) + "-" + suffix;
                    } else {
                        display = acronym.toUpperCase(Locale.ROOT) + suffix;
                    }
                    break;
                }
            }
            displayWords.add(display);
        }
        return String.join(" ", displayWords);
    }

    private static JsonObject objectAt(JsonObject source, String key) {
        return source.has(key) && source.get(key).isJsonObject() ? source.getAsJsonObject(key) : new JsonObject();
    }

    private static void addNation(List<String> details, String nation) {
        details.add(Component.translatable("gui.vehiclecatalog.nation").getString() + ": " + nation);
    }

    private static String resolveNation(ResourceLocation id, JsonObject config) {
        String configuredNation = configuredNation(config);
        String overrideNation = resolveNationOverride(id);
        if (!overrideNation.isBlank()) {
            return overrideNation;
        }
        String normalizedNation = configuredNation.toLowerCase(Locale.ROOT);
        String notAvailable = Component.translatable("gui.vehiclecatalog.not_available").getString();
        if (configuredNation.isBlank() || configuredNation.equalsIgnoreCase(notAvailable)
                || containsAny(normalizedNation, "unknown", "unspecified", "неизвест", "not specified", "n/a")) {
            return Component.translatable("gui.vehiclecatalog.nation.combined").getString();
        }
        return configuredNation;
    }

    private static String configuredNation(JsonObject config) {
        for (String key : List.of("Nation", "Country", "Faction")) {
            if (config.has(key) && config.get(key).isJsonPrimitive()) {
                String value = config.get(key).getAsString().trim();
                if (!value.isEmpty()) {
                    return nationName(value);
                }
            }
        }
        if (!config.has("ContainerIcon") || !config.get("ContainerIcon").isJsonPrimitive()) {
            return "";
        }
        String iconPath = config.get("ContainerIcon").getAsString();
        int separator = iconPath.lastIndexOf('_');
        int extension = iconPath.lastIndexOf('.');
        if (separator < 0 || extension <= separator + 1) {
            return "";
        }
        String code = iconPath.substring(separator + 1, extension);
        return nationName(code);
    }

    private static String requestedNationOverride(ResourceLocation id) {
        String path = id.getPath().toLowerCase(Locale.ROOT).replace('-', '_');
        String namespace = id.getNamespace().toLowerCase(Locale.ROOT);
        if (path.equals("truck")) {
            return Component.translatable("gui.vehiclecatalog.nation.rus").getString();
        }
        if (containsAny(path, "m1a2", "m_1a_2", "abrams", "john_deere", "tractor", "jas_39e", "jas39e")) {
            return Component.translatable("gui.vehiclecatalog.nation.us").getString();
        }
        if (containsAny(path, "jas_39", "jas39", "gripen")) {
            return Component.translatable("gui.vehiclecatalog.nation.us").getString();
        }
        if (containsAny(path, "speedboat", "tiny_speedboat")) {
            return Component.translatable("gui.vehiclecatalog.nation.us").getString();
        }
        if (containsAny(path, "drone", "kirov", "cart", "trolley", "laser_tower", "wheel_chair", "yx_100", "yx100", "happiest_ghast",
                "prisma", "waveforce_tower", "energy_cannon", "example_trailer")) {
            return Component.translatable("gui.vehiclecatalog.nation.combined").getString();
        }
        if (path.contains("sodayo_pick_up")) {
            return Component.translatable("gui.vehiclecatalog.nation.jp").getString();
        }
        if (path.contains("sapsan") || path.contains("zelensky") || path.equals("kv_16")) {
            return Component.translatable("gui.vehiclecatalog.nation.ua").getString();
        }
        if (namespace.equals("vvp") && path.equals("truck")) {
            return Component.translatable("gui.vehiclecatalog.nation.rus").getString();
        }
        if ((namespace.equals("ashvehicle") && path.equals("kv_2"))
                || (namespace.equals("superbwarfare") && (path.equals("kv_2") || path.equals("mortar") || path.equals("tow")))
                || (namespace.equals("fcp") && path.equals("empl_tow"))) {
            return Component.translatable("gui.vehiclecatalog.nation.rus").getString();
        }
        if (containsAny(path, "ah_6", "littlebird", "bl_132", "mle_1934", "mk_42", "hpj_11", "h_pj_11",
                "j_16", "lav_150", "lav_ad", "m1a2", "aav")) {
            return Component.translatable("gui.vehiclecatalog.nation.us").getString();
        }
        return "";
    }

    private static String nationKey(ResourceLocation id, String nationName) {
        String nation = nationName.toLowerCase(Locale.ROOT).trim();
        String path = id.getPath().toLowerCase(Locale.ROOT).replace('-', '_');
        if (nation.isEmpty() || nation.equals(Component.translatable("gui.vehiclecatalog.not_available")
                .getString().toLowerCase(Locale.ROOT))) {
            return "combined";
        }
        if (containsAny(nation, "сборная", "mixed nations")) return "combined";
        if (containsAny(nation, "рус", "росси", "рф", "russia", "russian") || nation.equals("rf") || nation.equals("rus")) return "rus";
        if (containsAny(nation, "сша", "америк", "america", "american", "united states") || nation.equals("us") || nation.equals("usa")) return "us";
        if (containsAny(nation, "украин", "ukrain") || nation.equals("ua") || nation.equals("ukr")) return "ua";
        if (containsAny(nation, "герман", "german", "deutsch") || nation.equals("de") || nation.equals("deu") || nation.equals("germany")) return "de";
        if (containsAny(nation, "британ", "великобрит", "england", "britain", "british") || nation.equals("uk") || nation.equals("gb")) return "uk";
        if (containsAny(nation, "итал", "ital")) return "it";
        if (containsAny(nation, "швед", "swed")) return "se";
        if (containsAny(nation, "австрал", "austral")) return "au";
        if (containsAny(nation, "кита", "china", "chinese") || nation.equals("cn") || nation.equals("chn")) return "cn";
        if (containsAny(nation, "япон", "japan", "japanese") || nation.equals("jp") || nation.equals("jpn")) return "jp";
        if (containsAny(nation, "европ", "europe", "euro") || nation.equals("eu")) return "eu";
        if (nation.equals("ter") || nation.contains("территор")) return "ter";
        if (containsAny(nation, "франц", "france", "french") || nation.equals("fr")) return "fr";
        if (containsAny(nation, "израил", "israel", "israeli") || nation.equals("il")) return "il";
        if (containsAny(nation, "коре", "korea", "korean")) return nation.contains("север") || nation.contains("north") ? "kp" : "kr";
        if (containsAny(nation, "польш", "poland", "polish") || nation.equals("pl")) return "pl";
        if (containsAny(nation, "турц", "turkey", "turkish") || nation.equals("tr")) return "tr";
        if (containsAny(nation, "финлянд", "finland", "finnish") || nation.equals("fi")) return "fi";
        if (containsAny(nation, "ссср", "soviet", "ussr")) return "ussr";
        return "custom:" + nation.replaceAll("[^\\p{L}\\p{N}]+", "_");
    }

    private static String resolveNationOverride(ResourceLocation id) {
        String requestedNation = requestedNationOverride(id);
        if (!requestedNation.isBlank()) {
            return requestedNation;
        }
        String curatedNation = curatedNation(id);
        if (!curatedNation.isBlank()) {
            return curatedNation;
        }
        String path = id.getPath().toLowerCase(Locale.ROOT);
        if (isRequestedRussianVehicle(id)) {
            return Component.translatable("gui.vehiclecatalog.nation.rus").getString();
        }
        if (isRequestedAmericanVehicle(id)) {
            return Component.translatable("gui.vehiclecatalog.nation.us").getString();
        }
        if (isRequestedUkrainianVehicle(id)) {
            return Component.translatable("gui.vehiclecatalog.nation.ua").getString();
        }
        if (isRequestedGermanVehicle(id)) {
            return Component.translatable("gui.vehiclecatalog.nation.de").getString();
        }
        if (isRequestedBritishVehicle(id)) {
            return Component.translatable("gui.vehiclecatalog.nation.uk").getString();
        }
        if (isRequestedItalianVehicle(id)) {
            return Component.translatable("gui.vehiclecatalog.nation.it").getString();
        }
        if (isRequestedSwedishVehicle(id)) {
            return Component.translatable("gui.vehiclecatalog.nation.se").getString();
        }
        if (isRequestedAustralianVehicle(id)) {
            return Component.translatable("gui.vehiclecatalog.nation.au").getString();
        }
        if (isRequestedChineseVehicle(id)) {
            return Component.translatable("gui.vehiclecatalog.nation.cn").getString();
        }
        if (isRequestedJapaneseVehicle(id)) {
            return Component.translatable("gui.vehiclecatalog.nation.jp").getString();
        }
        if (isRequestedEuropeanVehicle(id)) {
            return Component.translatable("gui.vehiclecatalog.nation.eu").getString();
        }
        // Fallback identification from well-known real-world model names. Explicit Nation/Country/Faction
        // fields in a vehicle config are still preferred unless this curated identity match applies.
        if (containsAny(path, "t72", "t_72", "t80", "t_80", "t90", "t_90", "t14", "t_14", "bmp1", "bmp_1", "bmp2", "bmp_2", "bmp3", "bmp_3",
                "btr80", "btr_80", "btr82", "btr_82", "brdm2", "brdm_2", "mi_24", "mi24", "mi_28", "mi28", "ka52", "ka_52", "pantsir", "tunguska", "msta", "ural_grad", "chryzantema", "t72_b3", "t90_m"))
            return Component.translatable("gui.vehiclecatalog.nation.rus").getString();
        if (containsAny(path, "abrams", "m1a1", "m1a2", "bradley", "stryker", "humvee", "hmmwv", "himars", "m142", "ah_64", "apache", "a_10", "a10", "f_16", "f16", "f_15", "f15", "f_22", "f22", "f_35", "f35", "m113", "lav_25", "ac_130", "c130"))
            return Component.translatable("gui.vehiclecatalog.nation.us").getString();
        if (containsAny(path, "oplot", "btr_3", "btr3", "btr_4", "btr4", "varta", "koz ak", "koz ak", "koz ak", "bogdana", "dozor", "ukraine"))
            return Component.translatable("gui.vehiclecatalog.nation.ua").getString();
        if (containsAny(path, "leopard", "puma", "marder", "fuchs", "pzh_2000", "boxer", "wiesel", "gepard", "lynx", "german", "jagdpanzer"))
            return Component.translatable("gui.vehiclecatalog.nation.de").getString();
        if (containsAny(path, "challenger", "warrior", "ajax", "scimitar", "foxhound", "mastiff", "british", "harrier", "chieftain"))
            return Component.translatable("gui.vehiclecatalog.nation.uk").getString();
        if (containsAny(path, "centauro", "ariete", "dardo", "freccia", "italy", "italian"))
            return Component.translatable("gui.vehiclecatalog.nation.it").getString();
        if (containsAny(path, "cv90", "cv_90", "strv", "archer", "jas_39", "gripen", "sweden", "swedish"))
            return Component.translatable("gui.vehiclecatalog.nation.se").getString();
        if (containsAny(path, "bushmaster", "australia", "australian"))
            return Component.translatable("gui.vehiclecatalog.nation.au").getString();
        if (containsAny(path, "ztz", "type_99", "type_96", "type_15", "plz", "zbd", "zbl", "j_20", "j20", "china", "chinese"))
            return Component.translatable("gui.vehiclecatalog.nation.cn").getString();
        if (containsAny(path, "type_10", "type_90", "type_89", "type_16", "f_2", "f2", "viper_zero", "japan", "japanese"))
            return Component.translatable("gui.vehiclecatalog.nation.jp").getString();
        if (containsAny(path, "rafale", "leclerc", "clerc", "amx_10", "amx10", "amx_30", "amx30", "vbci", "vab", "caesar", "mirage", "france", "french"))
            return "Франция";
        if (containsAny(path, "merkava", "namer", "israel", "israeli", "spike_nlos"))
            return "Израиль";
        if (containsAny(path, "k2_black", "k2_", "k21", "k9_thunder", "k9_", "korea", "korean", "k239"))
            return "Южная Корея";
        if (containsAny(path, "pt_91", "pt91", "rosomak", "krab", "bwp", "poland", "polish"))
            return "Польша";
        if (containsAny(path, "altay", "kirpi", "ejder", "turkey", "turkish"))
            return "Турция";
        if (containsAny(path, "cv90", "strv", "sisu", "patria", "finland", "finnish"))
            return "Финляндия";
        return "";
    }


    /** Curated real-world identity overrides for vehicle definitions shipped by FCP, AshVehicle and VVP. */
    private static String curatedNation(ResourceLocation id) {
        String p = id.getPath().toLowerCase(Locale.ROOT).replace('-', '_');
        String ns = id.getNamespace().toLowerCase(Locale.ROOT);
        // Toyota Hilux: Japanese-designed pickup, including armed variants.
        if (containsAny(p, "toyota_hilux", "toyota_hulix", "hilux", "hulix") || (ns.contains("sodayo") && containsAny(p, "pickup", "pick_up")))
            return Component.translatable("gui.vehiclecatalog.nation.jp").getString();
        // US-designed vehicles and aircraft
        if (containsAny(p, "aavp", "stryker", "lav25", "lav_25", "hmmwv", "humvee", "matv", "m939", "fmtv", "dpv_", "m109", "m1a1abrams", "m1a2", "abrams", "bradley", "m3a3", "ah_64", "apache", "cobra", "ah1z", "littlebird", "venom", "viper", "huey", "uh_60", "uh60", "mh_60", "ac130", "c130", "b_2", "b52", "f_117", "f_18", "f_22", "f_35", "f_4", "f14", "f_15", "f_16", "rah_66", "reaper", "mq_9", "v_22", "x_47b", "yf_23", "m_777", "m142_himars", "himars", "avenger", "asrad"))
            return Component.translatable("gui.vehiclecatalog.nation.us").getString();
        // Ukrainian design/manufacture
        if (containsAny(p, "btr3", "btr_3", "btr4", "btr_4", "btr4mv1", "kozak", "novator", "varta", "oplot", "btr_4mv", "dozor", "bogdana"))
            return Component.translatable("gui.vehiclecatalog.nation.ua").getString();
        // German design/manufacture
        if (containsAny(p, "leopard", "puma", "gepard", "ajax")) {
            if (p.contains("ajax")) return Component.translatable("gui.vehiclecatalog.nation.uk").getString();
            return Component.translatable("gui.vehiclecatalog.nation.de").getString();
        }
        // British design/manufacture
        if (containsAny(p, "challenger", "warrior", "foxhound", "mastiff", "wolfhound"))
            return p.contains("eurofighter") ? "Европейский союз (совместная разработка)" : Component.translatable("gui.vehiclecatalog.nation.uk").getString();
        // Russian / Soviet-origin systems. Soviet-era models are grouped with their principal successor/operator.
        if (containsAny(p, "bmp", "bmd", "brdm", "btr80", "btr_80", "btr82", "btr_82", "t72", "t_72", "t80", "t_80", "t90", "t_90", "t14", "t_14", "armata", "terminator", "bmpt", "gaz_tigr", "tigr", "kamaz", "ural", "uaz", "msta", "pantsir", "pautina", "mi17", "mi_17", "mi_8", "mi8", "mi_24", "mi24", "mi_28", "mi28", "ka52", "ka_52", "su_25", "su_27", "su_33", "su_34", "su_57", "mig_29", "tunguska", "grad", "tos", "chryzantema", "kornet", "shturm", "ags_30", "d30", "d_30", "zis3", "empl_dshk", "empl_ags17", "empl_zis3"))
            return Component.translatable("gui.vehiclecatalog.nation.rus").getString();
        // French, Swedish, Italian, Chinese, Japanese and Korean systems
        if (containsAny(p, "rafale", "leclerc", "amx_", "amx10", "amx30", "caesar", "mirage", "vbci", "vab")) return "Франция";
        if (containsAny(p, "cv_90", "cv90", "strv103", "jas_39", "gripen", "archer")) return Component.translatable("gui.vehiclecatalog.nation.se").getString();
        if (containsAny(p, "centauro", "ariete", "dardo", "freccia")) return Component.translatable("gui.vehiclecatalog.nation.it").getString();
        if (containsAny(p, "j_20", "j20", "type_99", "type_96", "zbd", "zbl", "plz")) return Component.translatable("gui.vehiclecatalog.nation.cn").getString();
        if (containsAny(p, "f_2", "type_10", "type_90", "type_89")) return Component.translatable("gui.vehiclecatalog.nation.jp").getString();
        if (containsAny(p, "k2_", "k21", "k9_", "k239")) return "Южная Корея";
        if (containsAny(p, "merkava", "namer", "spike_nlos")) return "Израиль";
        if (containsAny(p, "pt_91", "pt91", "rosomak", "krab", "bwp")) return "Польша";
        if (containsAny(p, "altay", "kirpi", "ejder")) return "Турция";
        if (containsAny(p, "bushmaster")) return Component.translatable("gui.vehiclecatalog.nation.au").getString();
        if (containsAny(p, "nh_90", "nh90", "eurofighter")) return "Европейский союз (совместная разработка)";
        if (containsAny(p, "kv_2", "kv2")) return "СССР";
        // FCP-specific vehicles where the short ID alone can be ambiguous
        if (ns.equals("fcp") && containsAny(p, "toyota_hilux")) return "Япония";
        if (ns.equals("fcp") && containsAny(p, "john_deere", "combine", "cultivator", "seeder")) return "США";
        if (ns.equals("ashvehicle") && containsAny(p, "kv_2", "kv2")) return "СССР";
        if (containsAny(p, "rubber_boat", "zumwalt")) return Component.translatable("gui.vehiclecatalog.nation.us").getString();
        return "";
    }

    private static String curatedClassKey(ResourceLocation id) {
        String p = id.getPath().toLowerCase(Locale.ROOT).replace('-', '_');
        String identity = (id.getNamespace() + " " + p).toLowerCase(Locale.ROOT);

        // Explicit catalogue classifications take precedence over general name heuristics.
        if ((identity.contains("sodayo") && containsAny(identity, "pickup", "pick_up", "teneven9", "ten_even9"))
                || containsAny(identity, "toyota_hilux_mortar", "toyota_hulix_mortar"))
            return "gui.vehiclecatalog.class.transport";
        if (containsAny(identity, "brm_3k", "brm3k", "brm")) return "gui.vehiclecatalog.class.bmp";
        if (containsAny(identity, "stryker_tow", "stryker_mortar", "varta_ptrk")) return "gui.vehiclecatalog.class.apc";
        if (containsAny(identity, "hmmwv_unarmored_tow", "matv_tow"))
            return "gui.vehiclecatalog.class.transport";
        if (containsAny(identity, "pautina")) return "gui.vehiclecatalog.class.transport";
        if (containsAny(identity, "v_22", "v22", "osprey")) return "gui.vehiclecatalog.class.helicopter";
        if (containsAny(identity, "plz_05", "plz05", "sapsan", "grim2", "grim_2", "waveforce_tower"))
            return "gui.vehiclecatalog.class.artillery";
        if (containsAny(identity, "ags_30", "ags30", "empl_kornet", "kornet", "empl_tow", "laser_tower", "waveforce_tower",
                "bl_132", "mle_1934", "mk_42", "hpj_11", "h_pj_11", "ciws", "energy_cannon",
                "annihilator", "ground_installation", "ground_emplacement", "empl_"))
            return "gui.vehiclecatalog.class.ground_installations";
        if (containsAny(identity, "centauro", "yx_100", "yx100", "m1a2", "m1_a2"))
            return "gui.vehiclecatalog.class.tank";
        if (containsAny(identity, "kirov", "airship", "dirigible", "zeppelin", "drone", "happiest_ghast",
                "happy_ghast", "kv_16", "kv16", "wheel_cart", "cart", "trolley", "wheelbarrow",
                "wheel_chair", "wheelchair", "invalid_chair", "invalidnoe_kreslo", "mortar",
                "zelensky", "vehicle_assembling_table", "example_trailer"))
            return "gui.vehiclecatalog.class.other";
        if (containsAny(identity, "speedboat", "tiny_speedboat", "rubber_boat", "boat"))
            return "gui.vehiclecatalog.class.naval";
        if (containsAny(identity, "tow", "5_mk42", "mk42", "mk_42", "mle1934", "mle_1934", "r1938",
                "58bl_132", "h_pj_11", "hpj_11", "ciws", "laser_defense", "defensive_laser",
                "uberwave", "energy_cannon", "annihilator", "ground_installation", "ground_emplacement", "empl_"))
            return "gui.vehiclecatalog.class.ground_installations";
        if (containsAny(identity, "lav_ad", "lavad", "lav_aav", "aav_lav_ad")) return "gui.vehiclecatalog.class.air_defense";
        // Toyota Hilux armed variants are still Japanese pickups/light vehicles, never IFVs.
        if (containsAny(identity, "toyota_hilux", "toyota_hulix", "hilux", "hulix") || (identity.contains("sodayo") && containsAny(identity, "pickup", "pick_up", "hilux", "hulix"))) return "gui.vehiclecatalog.class.transport";
        if (containsAny(p, "bmpt", "terminator", "ramka")) return "gui.vehiclecatalog.class.bmpt";
        if (containsAny(p, "pantsir", "gepard", "tunguska", "shilka", "tor_m", "buk_m", "s_300", "s_400", "patriot", "avenger", "nasams", "irist", "chaparral", "air_defense", "anti_air", "asrad", "zsu_")) return "gui.vehiclecatalog.class.air_defense";
        if (containsAny(p, "himars", "mlrs", "grad", "uragan", "smerch", "tornado_g", "tornado_s", "msta", "m109", "d30", "d_30", "zis3", "2s1", "2s3", "2s19", "2s35", "pzh_2000", "krab", "archer", "caesar", "m_777", "tos", "empl_zis3")) return "gui.vehiclecatalog.class.artillery";
        if (containsAny(p, "kornet", "chryzantema", "chrysanthemum", "shturm", "ptrk", "anti_tank", "tank_destroyer", "m901", "tow", "malyutka")) return "gui.vehiclecatalog.class.pt_sau";
        if (containsAny(p, "helicopter", "mi_8", "mi8", "mi_17", "mi17", "mi_24", "mi24", "mi_28", "mi28", "ka_52", "ka52", "ah_1", "ah1z", "ah_6", "ah6", "ah_6_little_bird", "ah6_little_bird", "little_bird", "littlebird", "ah_64", "ah64", "uh_60", "uh60", "mh_60", "ch_47", "nh90", "nh_90", "tiger", "apache", "cobra", "hind", "havoc", "blackhawk", "black_hawk", "chinook", "hokum", "huey", "littlebird", "venom", "viper", "bigbird", "rah_66")) return "gui.vehiclecatalog.class.helicopter";
        if (containsAny(p, "aircraft", "airplane", "fighter", "bomber", "mig_", "su_", "tu_", "il_", "yak_", "f_117", "f117", "f_4", "f4", "f_14", "f14", "f_15", "f_16", "f16", "f_18", "f_22", "f_35", "f_2", "j_16", "j16", "ju_87", "ju87", "junkers_ju_87", "a_10", "a10", "c_130", "c130", "ac_130", "j_20", "j20", "jas_39", "jas39", "gripen", "eurofighter", "typhoon", "rafale", "mirage", "tornado", "harrier", "b_2", "b52", "su_25", "su_27", "su_30", "su_33", "su_34", "su_35", "su_57", "reaper", "x_47b", "yf_23", "mq_9")) return "gui.vehiclecatalog.class.airplane";
        if (containsAny(p, "submarine", "destroyer", "frigate", "corvette", "battleship", "aircraft_carrier", "speedboat", "boat", "ship", "patrol_boat", "zumwalt")) return "gui.vehiclecatalog.class.naval";
        if (containsAny(p, "bmp_2", "bmp2", "bmp_3", "bmp3", "brm_3k", "brm3k", "brm", "m2_bradley", "m2_breadly", "bradley", "puma", "cv_90", "cv90", "ajax", "warrior", "marder", "lynx", "bmd", "kurganets", "namer", "dardo", "freccia")) return "gui.vehiclecatalog.class.bmp";
        if (containsAny(p, "lav_150", "lav150", "lav_25", "lav25", "lav_ad")) return "gui.vehiclecatalog.class.apc";
        if (containsAny(p, "btr", "brdm", "stryker", "m113", "varta", "mrap", "cougar", "maxxpro", "bushmaster", "ot_", "patria", "piranha", "fuchs", "foxhound", "mastiff", "wolfhound", "boxer", "vbci", "vab", "bumerang", "boomerang", "kozak", "novator", "aavp")) return "gui.vehiclecatalog.class.apc";
        if (containsAny(p, "uaz", "ural", "kamaz", "gaz_tigr", "tigr", "humvee", "hmmwv", "fmtv", "truck", "jeep", "pickup", "pick_up", "logistic", "fuel_truck", "cargo", "transport", "land_rover", "unimog", "zil_", "maz_", "tatra", "tractor", "ambulance", "toyota_hilux", "toyota_hulix", "hilux", "hulix", "sodayo", "dpv_", "matv", "m939", "cultivator", "combine", "seeder", "example_trailer")) return "gui.vehiclecatalog.class.transport";
        if (containsAny(p, "prisma", "uh100", "uh_100", "ztz_99a", "ztz99a", "t_90a", "t90a", "obm_m1a2", "m1a2_abrams", "ob_m1a2", "kv_2", "kv2", "t_55", "t55", "t_62", "t62", "t_64", "t64", "t_72", "t72", "t_80", "t80", "t_84", "t84", "t_90", "t90", "t_14", "t14", "m1a1", "m1a2", "abrams", "leopard", "challenger", "merkava", "type_10", "type_99", "type_96", "ztz", "k2_black", "k2_", "altay", "clerc", "leclerc", "ariete", "centurion", "chieftain", "amx_30", "amx30", "oplot", "pt_91", "twardy")) return "gui.vehiclecatalog.class.tank";
        if (containsAny(p, "empl_", "ags_30", "dshk", "mk19", "spg9", "spg_9", "tow", "m2_emplacement", "mg3")) return "gui.vehiclecatalog.class.other";
        return null;
    }

    private static boolean isExcludedCatalogEntry(ResourceLocation id) {
        String path = id.getPath().toLowerCase(Locale.ROOT).replace('-', '_');
        return containsAny(path, "vehicle_assembling_table", "vehicle_assembly_table",
                "vehicle_assembly_station", "vehicle_assembling_station");
    }

    private static boolean isRequestedRussianVehicle(ResourceLocation id) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        if (id.getNamespace().equals("vvp")) {
            return List.of("bmp_2", "bmp_2m", "bmp_2_bakhcha", "bmp_3", "t72_b3m", "t90_m",
                    "terminator", "bmpt_3k", "ural", "pantsir_s1", "pautina", "gaz_tigr",
                    "mi_28", "mi_24", "mi_8", "chryzantema", "d30", "brm", "btr_80a", "ags_30",
                    "mi_17").contains(path);
        }
        if (id.getNamespace().equals("superbwarfare")) {
            return List.of("t_90a", "bmp_2", "type_63", "mi_28").contains(path);
        }
        if (!id.getNamespace().equals("fcp")) {
            return false;
        }
        return List.of("t72av", "bmp1", "bmp1p", "bmp1u", "bmp1am", "bmp2", "bmp2d", "bmp2m",
                "bmp2md", "btr80", "btr82", "btr82at", "brdm2", "empl_kornet", "pantsir",
                "msta", "empl_zis3", "uaz", "uaz_dshka", "uaz_spg9", "ural", "ural_fuel",
                "ural_kung", "ural_grad", "kamaz", "kamaz_kung", "kamaz_long", "gaz_tigr",
                "gaz_tigr_rws", "gaz_tigr_mg", "gaz_tigr_gl", "gaz_tigr_dual", "mi17").contains(path);
    }

    private static boolean isRequestedAmericanVehicle(ResourceLocation id) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return id.getNamespace().equals("vvp")
                && List.of("m1a2_hv", "m1a2_sep_ii", "bradley", "m1a2", "m2_bradley", "m3a3_bradley",
                "humvee_m2", "humvee_m997", "humvee_mk19", "stryker", "stryker_m1296", "ah_64",
                "fmtv", "m142_himars", "lav_25", "lav_150", "ah_6", "a_10", "ac_130h", "speedboat",
                "tiny_speedboat", "truck").contains(path);
    }

    private static boolean isRequestedUkrainianVehicle(ResourceLocation id) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("oplot") || path.contains("btr_3") || path.contains("btr_4") || path.contains("varta")
                || path.contains("ukraine");
    }

    private static boolean isRequestedGermanVehicle(ResourceLocation id) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("leopard") || path.contains("puma") || path.contains("ju_87") || path.contains("stuka");
    }

    private static boolean isRequestedBritishVehicle(ResourceLocation id) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("challenger") || path.contains("ajax") || path.contains("british");
    }

    private static boolean isRequestedItalianVehicle(ResourceLocation id) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("centauro") || path.contains("italy");
    }

    private static boolean isRequestedSwedishVehicle(ResourceLocation id) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("cv90") || path.contains("nh90") || path.contains("fh_77") || path.contains("archer")
                || path.contains("jas_39") || path.contains("sweden");
    }

    private static boolean isRequestedAustralianVehicle(ResourceLocation id) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("bushmaster") || path.contains("australia") || path.contains("australian");
    }

    private static boolean isRequestedChineseVehicle(ResourceLocation id) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("ztz") || path.contains("plz") || path.contains("j20") || path.contains("china");
    }

    private static boolean isRequestedJapaneseVehicle(ResourceLocation id) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("f_2") || path.contains("viper_zero") || path.contains("japan");
    }

    private static boolean isRequestedEuropeanVehicle(ResourceLocation id) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("eurofighter") || path.contains("typhoon") || path.contains("europe") || path.contains("eu");
    }

    private static String nationName(String code) {
        return switch (code.toLowerCase(Locale.ROOT)) {
            case "ru", "rus", "rf", "russia", "russian" -> Component.translatable("gui.vehiclecatalog.nation.rus").getString();
            case "us", "usa", "america", "american" -> Component.translatable("gui.vehiclecatalog.nation.us").getString();
            case "ua", "ukr", "ukraine", "ukrainian" -> Component.translatable("gui.vehiclecatalog.nation.ua").getString();
            case "de", "deu", "germany", "german" -> Component.translatable("gui.vehiclecatalog.nation.de").getString();
            case "uk", "gb", "britain", "british", "england" -> Component.translatable("gui.vehiclecatalog.nation.uk").getString();
            case "it", "italy", "italian" -> Component.translatable("gui.vehiclecatalog.nation.it").getString();
            case "se", "swe", "sweden", "swedish" -> Component.translatable("gui.vehiclecatalog.nation.se").getString();
            case "au", "aus", "australia", "australian" -> Component.translatable("gui.vehiclecatalog.nation.au").getString();
            case "cn", "chn", "china", "chinese" -> Component.translatable("gui.vehiclecatalog.nation.cn").getString();
            case "jp", "jpn", "japan", "japanese" -> Component.translatable("gui.vehiclecatalog.nation.jp").getString();
            case "eu", "europe", "euro" -> Component.translatable("gui.vehiclecatalog.nation.eu").getString();
            case "ter" -> Component.translatable("gui.vehiclecatalog.nation.ter").getString();
            default -> code;
        };
    }

}
