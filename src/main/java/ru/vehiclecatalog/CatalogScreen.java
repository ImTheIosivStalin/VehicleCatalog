package ru.vehiclecatalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class CatalogScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger(CatalogScreen.class);
    private static final int ROW_HEIGHT = 24;
    private static final int ACCENT = 0xFFFFB52E;
    private static final int TEXT = 0xFFE8EDF5;
    private static final int MUTED = 0xFFAEB8C7;
    private static final int PANEL = 0xE4141C27;
    private static final int PANEL_LIGHT = 0xE61E2937;
    private static final int PANEL_DARK = 0xF00D1219;
    private static final float DEFAULT_PREVIEW_SCALE = 0.15F;
    private static final int SODAYO_ROCKET_SHELL_COUNT = 12;

    private final Screen parent;
    private final CompletableFuture<List<CatalogEntry>> entryLoad;
    private List<CatalogEntry> entries = List.of();
    private final List<CatalogEntry> visibleEntries = new ArrayList<>();
    private List<String> vehicleClasses = List.of();
    private boolean entryLoadHandled;
    private boolean entryLoadFailed;

    private EditBox searchBox;
    private int selectedIndex;
    private int scrollOffset;
    private int detailsOffset;
    private float previewScale = DEFAULT_PREVIEW_SCALE;
    private float previewYaw = 32.0F;
    private float previewPitch = 18.0F;
    private boolean dragging;
    private boolean panning;
    private boolean filterDialogOpen;
    private boolean nationDialog;
    private boolean scrollbarDragging;
    private int scrollbarGrabOffset;
    private int dialogScrollOffset;
    private float previewPanX;
    private float previewPanY;
    private Entity previewEntity;
    private ResourceLocation failedPreviewId;
    private final Map<ResourceLocation, PreviewBounds> previewBoundsCache = new HashMap<>();
    private final Set<ResourceLocation> missingPreviewBounds = new HashSet<>();
    private String nationFilter = "all";
    private String classFilter = "";

    public CatalogScreen(Screen parent) {
        this(parent, CatalogData.preload());
    }

    CatalogScreen(Screen parent, CompletableFuture<List<CatalogEntry>> entryLoad) {
        super(Component.translatable("gui.vehiclecatalog.title"));
        this.parent = parent;
        this.entryLoad = entryLoad;
    }

    @Override
    protected void init() {
        searchBox = new EditBox(font, listLeft() + 11, panelTop() + 49,
                Math.max(1, listRight() - listLeft() - 16), 20,
                Component.translatable("gui.vehiclecatalog.search"));
        searchBox.setHint(Component.translatable("gui.vehiclecatalog.search"));
        searchBox.setMaxLength(80);
        searchBox.setBordered(false);
        searchBox.setTextColor(TEXT);
        searchBox.setResponder(value -> {
            scrollOffset = 0;
            selectedIndex = 0;
            detailsOffset = 0;
            updateEntries();
            previewEntity = null;
            previewPanX = 0;
            previewPanY = 0;
        });
        addRenderableWidget(searchBox);
        addRenderableWidget(CatalogButton.create(Component.literal("×"),
                width - 46, 16, 30, 24, button -> onClose()));
    }

    private void completeEntryLoadIfReady() {
        if (entryLoadHandled || !entryLoad.isDone()) {
            return;
        }
        entryLoadHandled = true;
        try {
            entries = entryLoad.join();
            vehicleClasses = buildClassOptions(entries);
            updateEntries();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            LOGGER.error("Could not load vehicle catalog entries", cause);
            entryLoadFailed = true;
        }
    }

    private static List<String> buildClassOptions(List<CatalogEntry> entries) {
        List<String> values = new ArrayList<>();
        for (CatalogEntry entry : entries) {
            if (!values.contains(entry.vehicleClassKey())) {
                values.add(entry.vehicleClassKey());
            }
        }
        values.sort(Comparator.comparing(key -> Component.translatable(key).getString(),
                String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(values);
    }

    private void updateEntries() {
        visibleEntries.clear();
        String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        for (CatalogEntry entry : entries) {
            String name = entry.name().getString().toLowerCase(Locale.ROOT);
            String id = entry.id().toString().toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !name.contains(query) && !id.contains(query)) {
                continue;
            }
            if (!nationFilter.equals("all") && !nationFilter.equals(nationKey(entry))) {
                continue;
            }
            if (!classFilter.isEmpty() && !entry.vehicleClassKey().equals(classFilter)) {
                continue;
            }
            visibleEntries.add(entry);
        }
        visibleEntries.sort((first, second) -> {
            int nationComparison = Integer.compare(nationOrder(first), nationOrder(second));
            if (nationComparison != 0) {
                return nationComparison;
            }
            int modComparison = Integer.compare(sourceOrder(first), sourceOrder(second));
            if (modComparison != 0) {
                return modComparison;
            }
            modComparison = String.CASE_INSENSITIVE_ORDER.compare(
                    first.id().getNamespace(), second.id().getNamespace());
            if (modComparison != 0) {
                return modComparison;
            }
            int nameComparison = String.CASE_INSENSITIVE_ORDER.compare(
                    first.name().getString(), second.name().getString());
            return nameComparison != 0 ? nameComparison : first.id().compareTo(second.id());
        });
        if (selectedIndex >= visibleEntries.size()) {
            selectedIndex = Math.max(0, visibleEntries.size() - 1);
        }
        scrollOffset = Math.min(scrollOffset, Math.max(0, visibleEntries.size() - listRows()));
    }

    private int margin() {
        return 10;
    }

    private int gap() {
        return 8;
    }

    private int usableWidth() {
        return Math.max(1, width - margin() * 2 - gap() * 2);
    }

    private int sidePanelWidth() {
        return usableWidth() * 22 / 100;
    }

    private int statsPanelWidth() {
        return usableWidth() / 5;
    }

    private int listLeft() {
        return margin();
    }

    private int listRight() {
        return listLeft() + sidePanelWidth();
    }

    private int previewLeft() {
        return listRight() + gap();
    }

    private int statsRight() {
        return width - margin();
    }

    private int statsLeft() {
        return statsRight() - statsPanelWidth();
    }

    private int previewRight() {
        return statsLeft() - gap();
    }

    private int panelTop() {
        return Math.max(38, height / 10);
    }

    private int panelBottom() {
        return Math.max(panelTop() + 1, height - 12);
    }

    private int previewPanelTop() {
        return panelTop();
    }

    private int previewPanelBottom() {
        return panelBottom();
    }

    private int statsPanelTop() {
        return panelTop() + 8;
    }

    private int statsPanelBottom() {
        return Math.max(statsPanelTop() + 1, panelBottom() - 8);
    }

    private int listTop() {
        return panelTop() + 140;
    }

    private int listBottom() {
        return panelBottom() - 8;
    }

    private int listRows() {
        return Math.max(1, (listBottom() - listTop() - 8) / ROW_HEIGHT);
    }

    private int previewTop() {
        return previewPanelTop() + 8;
    }

    private int previewBottom() {
        return previewPanelBottom() - 5;
    }

    private int listContentRight() {
        return listRight() - 18;
    }

    private int scrollbarX() {
        return listRight() - 14;
    }

    private int scrollbarThumbHeight() {
        int trackHeight = Math.max(1, listBottom() - listTop());
        return Math.min(trackHeight, Math.max(16,
                trackHeight * Math.min(listRows(), visibleEntries.size()) / Math.max(1, visibleEntries.size())));
    }

    private int scrollbarThumbTop() {
        int trackHeight = listBottom() - listTop();
        int thumbHeight = scrollbarThumbHeight();
        int maxScroll = Math.max(0, visibleEntries.size() - listRows());
        if (maxScroll == 0 || trackHeight <= thumbHeight) {
            return listTop();
        }
        return listTop() + (trackHeight - thumbHeight) * scrollOffset / maxScroll;
    }

    private void updateScrollbar(double thumbTop) {
        int trackHeight = listBottom() - listTop();
        int thumbHeight = scrollbarThumbHeight();
        int travel = trackHeight - thumbHeight;
        int maxScroll = Math.max(0, visibleEntries.size() - listRows());
        if (travel <= 0 || maxScroll == 0) {
            scrollOffset = 0;
            return;
        }
        double clampedThumbTop = Math.max(listTop(), Math.min(listBottom() - thumbHeight, thumbTop));
        scrollOffset = (int) Math.round((clampedThumbTop - listTop()) * maxScroll / travel);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        completeEntryLoadIfReady();
        renderBackground(g);
        g.fill(0, 0, width, height, 0x8A080C12);
        drawCornerFrame(g);
        g.drawCenteredString(font, title, width / 2, 12, TEXT);

        drawPanel(g, listLeft(), panelTop(), listRight(), panelBottom(), PANEL);
        drawPanel(g, previewLeft(), previewPanelTop(), previewRight(), previewPanelBottom(), PANEL);
        drawPanel(g, statsLeft(), statsPanelTop(), statsRight(), statsPanelBottom(), PANEL);
        renderList(g);
        if (!filterDialogOpen) {
            renderPreview(g, partialTick);
        }
        renderDetails(g);
        super.render(g, mouseX, mouseY, partialTick);
        renderFilterDialog(g);
    }

    private void renderList(GuiGraphics g) {
        int left = listLeft() + 8;
        int right = listContentRight();
        drawLabel(g, Component.translatable("gui.vehiclecatalog.list"), left, panelTop() + 11);
        g.fill(left, panelTop() + 46, right, panelTop() + 68, PANEL_DARK);
        drawFilter(g, left, panelTop() + 69, right,
                Component.translatable("gui.vehiclecatalog.filter.nations"), nationLabel(nationFilter));
        drawFilter(g, left, panelTop() + 95, right,
                Component.translatable("gui.vehiclecatalog.filter.categories"),
                classFilter.isEmpty() ? Component.translatable("gui.vehiclecatalog.all").getString()
                        : Component.translatable(classFilter).getString());
        drawLabel(g, Component.translatable("gui.vehiclecatalog.list_count", visibleEntries.size()), left, panelTop() + 123);
        g.fill(left, listTop(), right, listBottom(), PANEL_DARK);

        g.enableScissor(left, listTop(), right, listBottom());
        for (int row = 0; row < listRows(); row++) {
            int index = scrollOffset + row;
            if (index >= visibleEntries.size()) {
                break;
            }
            CatalogEntry entry = visibleEntries.get(index);
            int y = listTop() + 4 + row * ROW_HEIGHT;
            if (index == selectedIndex) {
                g.fill(left + 2, y - 1, right - 2, y + ROW_HEIGHT - 2, 0xFF29435F);
                g.fill(left + 2, y - 1, left + 5, y + ROW_HEIGHT - 2, ACCENT);
            }
            String name = font.plainSubstrByWidth(entry.name().getString(), Math.max(1, right - left - 14));
            g.drawString(font, Component.literal(name), left + 9, y + 2, TEXT, false);
            drawNationColor(g, left + 9, y + 12, nationKey(entry));
                String meta = font.plainSubstrByWidth(sourceName(entry) + " | " + nationFor(entry),
                    Math.max(1, right - left - 34));
            g.drawString(font, Component.literal(meta), left + 24, y + 12, MUTED, false);
        }
        g.disableScissor();
        renderListScrollbar(g);
        if (visibleEntries.isEmpty()) {
            Component emptyMessage = entryLoadFailed
                    ? Component.translatable("gui.vehiclecatalog.load_failed")
                    : entryLoadHandled ? Component.translatable("gui.vehiclecatalog.empty")
                    : Component.translatable("gui.vehiclecatalog.loading");
            g.drawCenteredString(font, emptyMessage,
                    (left + right) / 2, listTop() + 12, MUTED);
        }
    }

    private void renderListScrollbar(GuiGraphics g) {
        int trackTop = listTop();
        int trackBottom = listBottom();
        int trackLeft = scrollbarX();
        int trackRight = listRight() - 8;
        g.fill(trackLeft, trackTop, trackRight, trackBottom, 0xFF090D13);
        if (visibleEntries.size() > listRows()) {
            int thumbTop = scrollbarThumbTop();
            int thumbBottom = thumbTop + scrollbarThumbHeight();
            g.fill(trackLeft + 1, thumbTop, trackRight - 1, thumbBottom, 0xFF71849B);
            g.fill(trackLeft + 2, thumbTop + 1, trackRight - 2, thumbBottom - 1, 0xFFB9C5D5);
        } else {
            g.fill(trackLeft + 1, trackTop + 1, trackRight - 1, trackBottom - 1, 0xFF364353);
        }
    }

    private void renderPreview(GuiGraphics g, float partialTick) {
        int left = previewLeft() + 8;
        int right = previewRight() - 8;
        if (visibleEntries.isEmpty()) {
            drawLabel(g, Component.translatable("gui.vehiclecatalog.preview"), left, panelTop() + 11);
            g.drawCenteredString(font, Component.translatable("gui.vehiclecatalog.select"),
                    (left + right) / 2, previewTop() + 18, MUTED);
            return;
        }

        CatalogEntry entry = visibleEntries.get(selectedIndex);
        int modelLeft = left;
        int modelRight = right;
        int modelTop = previewTop();
        int modelBottom = previewBottom();
        g.fill(modelLeft, modelTop, modelRight, modelBottom, PANEL_DARK);

        boolean previewFailed = entry.id().equals(failedPreviewId);
        Entity entity = previewFailed ? null : getPreviewEntity(entry.entityTypeId());
        if (previewFailed) {
            g.drawCenteredString(font, Component.translatable("gui.vehiclecatalog.preview_render_failed"),
                    (modelLeft + modelRight) / 2, modelTop + 16, MUTED);
            g.drawCenteredString(font, Component.literal(entry.id().toString()),
                    (modelLeft + modelRight) / 2, modelTop + 30, MUTED);
        } else if (entity == null) {
            g.drawCenteredString(font, Component.translatable("gui.vehiclecatalog.preview_unavailable"),
                    (modelLeft + modelRight) / 2, modelTop + 16, MUTED);
            g.drawCenteredString(font, Component.literal(entry.id().toString()),
                    (modelLeft + modelRight) / 2, modelTop + 30, MUTED);
        } else {
            float areaHeight = Math.max(1, modelBottom - modelTop);
            float scaleAtDefaultZoom = Math.max(12.0F, Math.min(220.0F, areaHeight * 0.72F))
                    * DEFAULT_PREVIEW_SCALE;
            PreviewBounds bounds = getPreviewBounds(entry.entityTypeId());
            if (bounds != null) {
                float extent = Math.max(bounds.width(), bounds.height());
                float fitWidth = Math.max(1, modelRight - modelLeft) * 0.86F;
                float fitHeight = Math.max(1, modelBottom - modelTop - 72) * 0.72F;
                float fitScale = Math.min(fitWidth, fitHeight) * 16.0F / extent;
                scaleAtDefaultZoom = Math.min(scaleAtDefaultZoom, fitScale);
            }
            float scale = scaleAtDefaultZoom * previewScale / DEFAULT_PREVIEW_SCALE;
            int centerX = (int) ((modelLeft + modelRight) / 2.0F + previewPanX);
            g.enableScissor(modelLeft, modelTop, modelRight, modelBottom);
            g.pose().pushPose();
            float centerY = (modelTop + modelBottom) / 2.0F + 50.0F + previewPanY;
            if (bounds != null) {
                centerY += bounds.offsetY() * scale / 16.0F;
            }
            g.pose().translate(centerX, centerY, 180.0);
            g.pose().scale(scale, -scale, scale);
            g.pose().mulPose(Axis.XP.rotationDegrees(previewPitch));
            try {
                Minecraft.getInstance().getEntityRenderDispatcher().render(entity, 0.0, 0.0, 0.0,
                        previewYaw, partialTick, g.pose(), g.bufferSource(), 15728880);
                g.flush();
            } catch (RuntimeException exception) {
                failedPreviewId = entry.id();
                LOGGER.error("Failed to render vehicle catalog preview for {}", entry.id(), exception);
            } finally {
                g.pose().popPose();
                g.disableScissor();
            }
        }

        drawLabel(g, Component.translatable("gui.vehiclecatalog.preview"), left, panelTop() + 11);
        String name = font.plainSubstrByWidth(entry.name().getString(), Math.max(1, right - left));
        g.drawString(font, Component.literal(name), left, panelTop() + 27, ACCENT, true);
        g.drawCenteredString(font, Component.translatable("gui.vehiclecatalog.zoom"),
                (left + right) / 2, panelBottom() - 19, MUTED);
    }

    private void renderDetails(GuiGraphics g) {
        int left = statsLeft() + 8;
        int right = statsRight() - 8;
        drawLabel(g, Component.translatable("gui.vehiclecatalog.parameters"), left, statsPanelTop() + 11);
        g.fill(left, statsPanelTop() + 31, right, statsPanelTop() + 33, 0xFF71849B);
        if (visibleEntries.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("gui.vehiclecatalog.empty"),
                    (left + right) / 2, statsPanelTop() + 48, MUTED);
            return;
        }

        CatalogEntry entry = visibleEntries.get(selectedIndex);
        String name = font.plainSubstrByWidth(entry.name().getString(), Math.max(1, right - left));
        g.drawString(font, Component.literal(name), left, statsPanelTop() + 42, ACCENT, false);
        List<FormattedCharSequence> lines = new ArrayList<>(font.split(
                Component.literal(Component.translatable("gui.vehiclecatalog.nation").getString() + ": "
                        + nationFor(entry)), Math.max(1, right - left)));
        lines.addAll(font.split(Component.literal(Component.translatable("gui.vehiclecatalog.class").getString()
                + ": " + Component.translatable(entry.vehicleClassKey()).getString()), Math.max(1, right - left)));
        lines.add(FormattedCharSequence.EMPTY);
        String nationPrefix = Component.translatable("gui.vehiclecatalog.nation").getString() + ": ";
        String classPrefix = Component.translatable("gui.vehiclecatalog.class").getString() + ": ";
        String weaponHeaderPrefix = Component.translatable("gui.vehiclecatalog.weapon_header").getString() + ": ";
        for (String detail : entry.details()) {
            if (detail.startsWith(nationPrefix) || detail.startsWith(classPrefix)) {
                continue;
            }
            Component detailComponent = detail.startsWith(weaponHeaderPrefix)
                    ? Component.literal(detail).withStyle(style -> style.withColor(ACCENT).withBold(true))
                    : Component.literal(detail);
            lines.addAll(font.split(detailComponent, Math.max(1, right - left - 8)));
            lines.add(FormattedCharSequence.EMPTY);
        }

        int textTop = statsPanelTop() + 63;
        int textBottom = statsPanelBottom() - 9;
        int visibleLines = Math.max(1, (textBottom - textTop) / 12);
        detailsOffset = Math.max(0, Math.min(detailsOffset, Math.max(0, lines.size() - visibleLines)));
        int y = textTop - detailsOffset * 12;
        g.enableScissor(left, textTop, right, textBottom);
        for (FormattedCharSequence line : lines) {
            if (y >= textTop && y <= textBottom - 9) {
                g.drawString(font, line, left, y, TEXT, false);
            }
            y += 12;
        }
        g.disableScissor();
    }

    private void drawFilter(GuiGraphics g, int left, int top, int right, Component label, String selected) {
        g.fill(left, top, right, top + 23, PANEL_LIGHT);
        g.fill(left, top, left + 2, top + 23, ACCENT);
        String shownLabel = font.plainSubstrByWidth(label.getString(), Math.max(1, right - left - 8));
        String shownValue = font.plainSubstrByWidth(selected, Math.max(1, right - left - 8));
        g.drawString(font, Component.literal(shownLabel), left + 6, top + 2, MUTED, false);
        g.drawString(font, Component.literal(shownValue), left + 6, top + 12, TEXT, false);
    }

    private void renderFilterDialog(GuiGraphics g) {
        if (!filterDialogOpen) {
            return;
        }
        List<String> options = dialogOptions();
        int rowHeight = 22;
        int visibleRows = dialogVisibleRows(options.size());
        int left = dialogLeft();
        int top = dialogTop(options.size());
        int dialogWidth = dialogWidth();
        int dialogHeight = 52 + visibleRows * rowHeight;
        int right = left + dialogWidth;
        int rowsTop = top + 42;
        g.fill(0, 0, width, height, 0xA6000000);
        g.fill(previewLeft() + 8, previewTop(), previewRight() - 8, previewBottom(), 0xFF0D1219);
        drawPanel(g, left, top, right, top + dialogHeight, PANEL);
        Component heading = Component.translatable(nationDialog
                ? "gui.vehiclecatalog.dialog.nations" : "gui.vehiclecatalog.dialog.categories");
        g.drawString(font, heading, left + 12, top + 10, TEXT, false);
        g.fill(right - 31, top + 13, right - 5, top + 37, PANEL_DARK);
        g.drawCenteredString(font, "×", right - 18, top + 20, TEXT);
        g.fill(left + 8, top + 35, right - 8, top + 37, ACCENT);
        g.enableScissor(left + 8, rowsTop, right - 8, rowsTop + visibleRows * rowHeight);
        for (int row = 0; row < visibleRows; row++) {
            int optionIndex = dialogScrollOffset + row;
            if (optionIndex >= options.size()) {
                break;
            }
            String option = options.get(optionIndex);
            int rowTop = rowsTop + row * rowHeight;
            if (isDialogOptionSelected(option)) {
                g.fill(left + 9, rowTop, right - 9, rowTop + rowHeight - 1, 0xFF29435F);
                g.fill(left + 9, rowTop, left + 12, rowTop + rowHeight - 1, ACCENT);
            }
            if (nationDialog) {
                drawNationColor(g, left + 17, rowTop + 6, option);
            } else {
                g.fill(left + 15, rowTop + 3, left + 45, rowTop + rowHeight - 4, categoryColor(option));
                g.drawCenteredString(font, Component.literal(categoryMark(option)), left + 30, rowTop + 6, TEXT);
            }
            String label = nationDialog ? nationLabel(option)
                    : option.isEmpty() ? Component.translatable("gui.vehiclecatalog.all").getString()
                    : Component.translatable(option).getString();
            g.drawString(font, Component.literal(font.plainSubstrByWidth(label,
                    dialogWidth - (nationDialog ? 82 : 102))), left + (nationDialog ? 36 : 53),
                    rowTop + 7, TEXT, false);
            String count = Integer.toString(dialogOptionCount(option));
            g.drawString(font, count, right - 24 - font.width(count), rowTop + 7, MUTED, false);
        }
        g.disableScissor();
        if (options.size() > visibleRows) {
            int trackLeft = right - 13;
            int trackTop = rowsTop;
            int trackHeight = visibleRows * rowHeight;
            int thumbHeight = Math.max(18, trackHeight * visibleRows / options.size());
            int maxScroll = options.size() - visibleRows;
            int thumbTop = trackTop + (trackHeight - thumbHeight) * dialogScrollOffset / maxScroll;
            g.fill(trackLeft, trackTop, trackLeft + 3, trackTop + trackHeight, PANEL_DARK);
            g.fill(trackLeft, thumbTop, trackLeft + 3, thumbTop + thumbHeight, ACCENT);
        }
    }

    private List<String> dialogOptions() {
        if (nationDialog) {
            List<String> options = new ArrayList<>();
            options.add("all");
            entries.stream().map(CatalogEntry::nationKey).distinct()
                    .sorted(Comparator.comparing(this::nationLabel, String.CASE_INSENSITIVE_ORDER))
                    .forEach(options::add);
            return options;
        }
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(vehicleClasses);
        return options;
    }

    private int dialogOptionCount(String option) {
        if (nationDialog) {
            return option.equals("all") ? entries.size()
                    : (int) entries.stream().filter(entry -> entry.nationKey().equals(option)).count();
        }
        return option.isEmpty() ? entries.size()
                : (int) entries.stream().filter(entry -> entry.vehicleClassKey().equals(option)).count();
    }

    private int dialogVisibleRows(int optionCount) {
        return Math.max(1, Math.min(optionCount, Math.min(7, Math.max(3, (height - 100) / 22))));
    }

    private int dialogWidth() {
        return Math.min(240, width - 32);
    }

    private int dialogLeft() {
        return (width - dialogWidth()) / 2;
    }

    private int dialogTop(int optionCount) {
        int dialogHeight = 52 + dialogVisibleRows(optionCount) * 22;
        return (height - dialogHeight) / 2;
    }

    private boolean isDialogOptionSelected(String option) {
        return nationDialog ? nationFilter.equals(option) : classFilter.equals(option);
    }

    private static int categoryColor(String category) {
        if (category.isEmpty()) return 0xFF627487;
        return switch (category.substring(category.lastIndexOf('.') + 1)) {
            case "tank" -> 0xFFB85D43;
            case "pt_sau" -> 0xFFB2779D;
            case "bmp" -> 0xFF5F9E62;
            case "bmpt" -> 0xFF8B7DB5;
            case "helicopter" -> 0xFF4C9B9B;
            case "airplane" -> 0xFF587EC1;
            case "apc" -> 0xFF6D9B9A;
            case "air_defense" -> 0xFF6686B6;
            case "artillery" -> 0xFFC06C55;
            case "transport" -> 0xFF898D69;
            case "naval" -> 0xFF397D9D;
            case "ground_installations" -> 0xFFB28A46;
            case "other" -> 0xFF777F8C;
            default -> 0xFF777777;
        };
    }

    private static String categoryMark(String category) {
        if (category.isEmpty()) return "ALL";
        return switch (category.substring(category.lastIndexOf('.') + 1)) {
            case "tank" -> "T";
            case "pt_sau" -> "PT";
            case "bmp" -> "BMP";
            case "bmpt" -> "BT";
            case "helicopter" -> "AIR";
            case "airplane" -> "JET";
            case "apc" -> "APC";
            case "air_defense" -> "AA";
            case "artillery" -> "ART";
            case "transport" -> "TR";
            case "naval" -> "NAV";
            case "ground_installations" -> "GND";
            case "other" -> "OTH";
            default -> "?";
        };
    }

    private void drawNationColor(GuiGraphics g, int left, int top, String nation) {
        int color = switch (nation) {
            case "rus" -> 0xFFE53945;
            case "us" -> 0xFF3478D4;
            case "ua" -> 0xFF2E9E5C;
            case "de" -> 0xFF8B929C;
            case "uk" -> 0xFFB5C6D7;
            case "it" -> 0xFF2BAE66;
            case "se" -> 0xFF0D5C9A;
            case "au" -> 0xFFE0A52A;
            case "cn" -> 0xFFFF7777;
            case "jp" -> 0xFFBE1E2D;
            case "eu" -> 0xFF6CB7FF;
            default -> 0xFF71849B;
        };
        g.fill(left, top, left + 11, top + 10, color);
        g.fill(left, top, left + 11, top + 1, 0xFFCBD5E1);
        g.fill(left, top + 9, left + 11, top + 10, 0xFF0A0E14);
        g.fill(left, top, left + 1, top + 10, 0xFFCBD5E1);
        g.fill(left + 10, top, left + 11, top + 10, 0xFF0A0E14);
    }

    private void drawLabel(GuiGraphics g, Component text, int x, int y) {
        int panelRight = x < listRight() ? listRight() : x < statsLeft() ? previewRight() : statsRight();
        String shown = font.plainSubstrByWidth(text.getString(), Math.max(1, panelRight - x - 8));
        g.drawString(font, Component.literal(shown), x, y, 0xFF91A0B4, false);
    }

    private void drawPanel(GuiGraphics g, int left, int top, int right, int bottom, int color) {
        g.fill(left, top, right, bottom, color);
        g.fill(left, top, right, top + 2, 0xFF71849B);
        g.fill(left, bottom - 2, right, bottom, 0xFF0A0E14);
        g.fill(left, top, left + 2, bottom, 0xFF71849B);
        g.fill(right - 2, top, right, bottom, 0xFF05080D);
        g.fill(right - 34, bottom - 12, right - 10, bottom - 10, ACCENT);
    }

    private void drawCornerFrame(GuiGraphics g) {
        int color = 0xB7D3DBE8;
        int length = 34;
        int thickness = 3;
        g.fill(10, 10, 10 + length, 10 + thickness, color);
        g.fill(10, 10, 10 + thickness, 10 + length, color);
        g.fill(width - 10 - length, 10, width - 10, 10 + thickness, color);
        g.fill(width - 13, 10, width - 10, 10 + length, color);
        g.fill(10, height - 10 - thickness, 10 + length, height - 10, color);
        g.fill(10, height - 10 - length, 10 + thickness, height - 10, color);
        g.fill(width - 10 - length, height - 10 - thickness, width - 10, height - 10, color);
        g.fill(width - 13, height - 10 - length, width - 10, height - 10, color);
    }

    private static String nationFor(CatalogEntry entry) {
        return entry.nationName();
    }

    private String nationLabel(String nation) {
        String key = switch (nation) {
            case "all" -> "gui.vehiclecatalog.all";
            case "rus" -> "gui.vehiclecatalog.nation.rus";
            case "us" -> "gui.vehiclecatalog.nation.us";
            case "ua" -> "gui.vehiclecatalog.nation.ua";
            case "de" -> "gui.vehiclecatalog.nation.de";
            case "uk" -> "gui.vehiclecatalog.nation.uk";
            case "it" -> "gui.vehiclecatalog.nation.it";
            case "se" -> "gui.vehiclecatalog.nation.se";
            case "au" -> "gui.vehiclecatalog.nation.au";
            case "cn" -> "gui.vehiclecatalog.nation.cn";
            case "jp" -> "gui.vehiclecatalog.nation.jp";
            case "eu" -> "gui.vehiclecatalog.nation.eu";
            case "combined" -> "gui.vehiclecatalog.nation.combined";
            case "unknown" -> "gui.vehiclecatalog.not_available";
            default -> "";
        };
        if (!key.isEmpty()) {
            return Component.translatable(key).getString();
        }
        return entries.stream().filter(entry -> entry.nationKey().equals(nation))
                .map(CatalogEntry::nationName).findFirst().orElse(nation);
    }

    private static String nationKey(CatalogEntry entry) {
        return entry.nationKey();
    }

    private static int nationOrder(CatalogEntry entry) {
        return switch (nationKey(entry)) {
            case "rus" -> 0;
            case "us" -> 1;
            case "ua" -> 2;
            case "de" -> 3;
            case "uk" -> 4;
            case "it" -> 5;
            case "se" -> 6;
            case "au" -> 7;
            case "cn" -> 8;
            case "jp" -> 9;
            case "eu" -> 10;
            default -> 11;
        };
    }

    private static int sourceOrder(CatalogEntry entry) {
        return switch (entry.id().getNamespace()) {
            case "vvp" -> 0;
            case "superbwarfare", "sbw" -> 1;
            case "ashvehicle", "ashv" -> 2;
            case "fcp" -> 3;
            default -> 3;
        };
    }

    private static String sourceName(CatalogEntry entry) {
        String key = switch (entry.id().getNamespace()) {
            case "vvp" -> "gui.vehiclecatalog.source.vvp";
            case "superbwarfare", "sbw" -> "gui.vehiclecatalog.source.superbwarfare";
            case "ashvehicle", "ashv" -> "gui.vehiclecatalog.source.ashvehicle";
            case "fcp" -> "gui.vehiclecatalog.source.fcp";
            default -> "";
        };
        return key.isEmpty() ? entry.id().getNamespace() : Component.translatable(key).getString();
    }

    private Entity getPreviewEntity(ResourceLocation entityId) {
        if (previewEntity != null || entityId == null || Minecraft.getInstance().level == null) {
            return previewEntity;
        }
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(entityId);
        if (type == null) {
            return null;
        }
        previewEntity = type.create(Minecraft.getInstance().level);
        if (previewEntity != null) {
            previewEntity.noCulling = true;
            initializeSodayoRocketPreview(previewEntity, entityId);
        }
        return previewEntity;
    }

    private static void initializeSodayoRocketPreview(Entity entity, ResourceLocation entityId) {
        if (!entityId.equals(new ResourceLocation("superbwarfare", "sodayo_pick_up_rocket"))) {
            return;
        }
        try {
            Field ammoAccessorField = entity.getClass().getField("LOADED_AMMO");
            setLoadedAmmoPreview(entity, (EntityDataAccessor<?>) ammoAccessorField.get(null));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.warn("Could not initialize Sodayo rocket preview ammo slots", exception);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setLoadedAmmoPreview(Entity entity, EntityDataAccessor<?> accessor) {
        entity.getEntityData().set((EntityDataAccessor) accessor,
                java.util.Collections.nCopies(SODAYO_ROCKET_SHELL_COUNT, 0));
    }

    private PreviewBounds getPreviewBounds(ResourceLocation entityId) {
        if (entityId == null || missingPreviewBounds.contains(entityId)) {
            return null;
        }
        PreviewBounds cached = previewBoundsCache.get(entityId);
        if (cached != null) {
            return cached;
        }
        ResourceLocation geometryId = new ResourceLocation(entityId.getNamespace(),
                "models/bedrock/vehicle/" + entityId.getPath() + ".geo.json");
        var resource = Minecraft.getInstance().getResourceManager().getResource(geometryId).orElse(null);
        if (resource == null) {
            missingPreviewBounds.add(entityId);
            return null;
        }
        try (Reader reader = resource.openAsReader()) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed.isJsonObject()) {
                JsonObject root = parsed.getAsJsonObject();
                if (root.has("minecraft:geometry") && root.get("minecraft:geometry").isJsonArray()) {
                    for (JsonElement geometry : root.getAsJsonArray("minecraft:geometry")) {
                        if (!geometry.isJsonObject() || !geometry.getAsJsonObject().has("description")) {
                            continue;
                        }
                        JsonObject description = geometry.getAsJsonObject().getAsJsonObject("description");
                        float width = jsonFloat(description, "visible_bounds_width");
                        float height = jsonFloat(description, "visible_bounds_height");
                        float offsetY = 0.0F;
                        if (description.has("visible_bounds_offset")
                                && description.get("visible_bounds_offset").isJsonArray()
                                && description.getAsJsonArray("visible_bounds_offset").size() > 1) {
                            offsetY = description.getAsJsonArray("visible_bounds_offset").get(1).getAsFloat();
                        }
                        if (width > 0.0F && height > 0.0F) {
                            PreviewBounds bounds = new PreviewBounds(width, height, offsetY);
                            previewBoundsCache.put(entityId, bounds);
                            return bounds;
                        }
                    }
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("Could not read preview bounds for vehicle model {}", geometryId, exception);
        }
        missingPreviewBounds.add(entityId);
        return null;
    }

    private static float jsonFloat(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsFloat() : 0.0F;
    }

    private record PreviewBounds(float width, float height, float offsetY) {
    }

    private void cycleFilter(boolean nation) {
        nationDialog = nation;
        filterDialogOpen = true;
        dialogScrollOffset = 0;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (filterDialogOpen) {
            if (button != 0) {
                return true;
            }
            List<String> options = dialogOptions();
            int left = dialogLeft();
            int top = dialogTop(options.size());
            int right = left + dialogWidth();
            int visibleRows = dialogVisibleRows(options.size());
            int rowsTop = top + 42;
            if (mouseX >= right - 31 && mouseX < right - 5
                    && mouseY >= top + 13 && mouseY < top + 37) {
                filterDialogOpen = false;
                return true;
            }
            if (mouseX >= left + 8 && mouseX <= right - 8
                    && mouseY >= rowsTop && mouseY < rowsTop + visibleRows * 22) {
                int optionIndex = dialogScrollOffset + (int) ((mouseY - rowsTop) / 22);
                if (optionIndex >= 0 && optionIndex < options.size()) {
                    applyDialogOption(options.get(optionIndex));
                }
                return true;
            }
            if (mouseX < left || mouseX > right || mouseY < top || mouseY > top + 52 + visibleRows * 22) {
                filterDialogOpen = false;
            }
            return true;
        }
        if (button == 0 || button == 1) {
            int left = listLeft() + 8;
            int right = listRight() - 8;
            if (button == 0 && mouseX >= left && mouseX <= right
                    && mouseY >= panelTop() + 69 && mouseY < panelTop() + 92) {
                cycleFilter(true);
                return true;
            }
            if (button == 0 && mouseX >= left && mouseX <= right
                    && mouseY >= panelTop() + 95 && mouseY < panelTop() + 118) {
                cycleFilter(false);
                return true;
            }
            if (button == 0 && mouseX >= left && mouseX <= right
                    && mouseY >= listTop() && mouseY < listBottom()) {
                if (mouseX >= scrollbarX() - 2) {
                    scrollbarDragging = true;
                    int thumbTop = scrollbarThumbTop();
                    int thumbBottom = thumbTop + scrollbarThumbHeight();
                    scrollbarGrabOffset = mouseY >= thumbTop && mouseY < thumbBottom
                            ? (int) mouseY - thumbTop
                            : scrollbarThumbHeight() / 2;
                    updateScrollbar(mouseY - scrollbarGrabOffset);
                    return true;
                }
                int row = (int) ((mouseY - listTop() - 4) / ROW_HEIGHT);
                int index = scrollOffset + row;
                if (row >= 0 && index >= 0 && index < visibleEntries.size()) {
                    selectedIndex = index;
                    detailsOffset = 0;
                    previewEntity = null;
                    previewPanX = 0;
                    previewPanY = 0;
                    return true;
                }
            }
            if (mouseX >= previewLeft() + 8 && mouseX <= previewRight() - 8
                    && mouseY >= previewTop() && mouseY <= previewBottom()) {
                if (button == 0) {
                    dragging = true;
                    return true;
                }
                if (button == 1) {
                    panning = true;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void applyDialogOption(String option) {
        if (nationDialog) {
            nationFilter = option;
        } else {
            classFilter = option;
        }
        filterDialogOpen = false;
        selectedIndex = 0;
        scrollOffset = 0;
        detailsOffset = 0;
        previewEntity = null;
        previewPanX = 0;
        previewPanY = 0;
        updateEntries();
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            dragging = false;
            scrollbarDragging = false;
        }
        if (button == 1) {
            panning = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (scrollbarDragging && button == 0) {
            updateScrollbar(mouseY - scrollbarGrabOffset);
            return true;
        }
        if (dragging && button == 0) {
            previewYaw -= (float) dragX;
            previewPitch = Math.max(-45.0F, Math.min(65.0F, previewPitch + (float) dragY));
            return true;
        }
        if (panning && button == 1) {
            previewPanX += (float) dragX;
            previewPanY += (float) dragY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (filterDialogOpen) {
            List<String> options = dialogOptions();
            int left = dialogLeft();
            int top = dialogTop(options.size());
            int visibleRows = dialogVisibleRows(options.size());
            if (mouseX >= left && mouseX <= left + dialogWidth()
                    && mouseY >= top && mouseY <= top + 52 + visibleRows * 22) {
                dialogScrollOffset = Math.max(0, Math.min(options.size() - visibleRows,
                        dialogScrollOffset - (int) Math.signum(delta) * 3));
                return true;
            }
            return true;
        }
        if (mouseX >= listLeft() && mouseX <= listRight()
                && mouseY >= panelTop() && mouseY <= panelBottom()) {
            scrollOffset = Math.max(0, Math.min(Math.max(0, visibleEntries.size() - listRows()),
                    scrollOffset - (int) Math.signum(delta) * 3));
            return true;
        }
        if (mouseX >= statsLeft() && mouseX <= statsRight()) {
            detailsOffset = Math.max(0, detailsOffset - (int) Math.signum(delta) * 3);
            return true;
        }
        if (mouseX >= previewLeft() && mouseX <= previewRight()) {
            previewScale = Math.max(0.08F, Math.min(2.8F, previewScale + (float) delta * 0.12F));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
