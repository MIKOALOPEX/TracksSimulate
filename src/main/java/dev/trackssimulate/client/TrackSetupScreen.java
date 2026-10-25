package dev.trackssimulate.client;

import dev.trackssimulate.config.TrackSetting;
import dev.trackssimulate.config.OffsetScope;
import dev.trackssimulate.content.blocks.sable_track.SableTrackBlockEntity;
import dev.trackssimulate.content.blocks.sable_track.SableTrackRole;
import java.util.*;
import dev.trackssimulate.network.SetTrackSettingPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.Locale;

public final class TrackSetupScreen extends Screen {
    private final BlockPos pos;
    private final SettingRow[] rows = Arrays.stream(TrackSetting.values()).map(SettingRow::new).toArray(SettingRow[]::new);
    private int left, top, panelWidth, panelHeight, contentTop, contentBottom, columns, rowsPerColumn, rowHeight, columnWidth;
    private int scroll, maxScroll;
    private OffsetScope scope=OffsetScope.WHEEL;
    private Button syncButton,scopeButton,resetButton;
    private UUID pendingRequest, expectedBelt;
    private boolean snapshotTaken;
    private int pendingTicks;
    private Map<TrackSetting,Double> pendingValues=Map.of();
    private Component status = Component.empty();
    private int statusColor = 0xb8c6d4;

    public TrackSetupScreen(BlockPos pos) {
        super(Component.translatable("screen.trackssimulate.setup"));
        this.pos = pos.immutable();
    }

    public static void feedback(dev.trackssimulate.network.TrackConfigFeedback result) {
        if(net.minecraft.client.Minecraft.getInstance().screen instanceof TrackSetupScreen screen
                &&screen.pos.equals(result.pos())&&result.requestId().equals(screen.pendingRequest)) {
            for(var row:screen.rows)if(screen.pendingValues.containsKey(row.setting)) {
                row.pending=null;
                // An explicit server acknowledgement, never a guessed match with local BE values.
                row.dirty=!result.success();
            }
            screen.pendingRequest=null;screen.pendingValues=Map.of();
            screen.status=Component.literal(result.message());screen.statusColor=result.success()?0x9cdb91:0xff7777;
            screen.refreshControls();
        }
    }

    private SableTrackBlockEntity track() {
        return minecraft != null && minecraft.level != null && minecraft.level.getBlockEntity(pos) instanceof SableTrackBlockEntity t ? t : null;
    }

    @Override protected void init() {
        panelWidth = Math.min(800, width - 12);
        panelHeight = Math.min(420, height - 12);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        columns = panelWidth >= 370 ? 2 : 1;
        rowsPerColumn = (rows.length + columns - 1) / columns;
        columnWidth = (panelWidth - 28) / columns;
        contentTop = top + 37;
        contentBottom = top + panelHeight - 47;
        rowHeight = Math.max(32, Math.min(50, (contentBottom - contentTop) / rowsPerColumn));
        maxScroll = Math.max(0, rowsPerColumn * rowHeight - (contentBottom - contentTop));
        scroll = Math.min(scroll, maxScroll);
        var track = track();
        if(!snapshotTaken&&track!=null){expectedBelt=track.beltId();snapshotTaken=true;}
        for (int i = 0; i < rows.length; i++) {
            SettingRow row = rows[i];
            int x = left + 10 + (i / rowsPerColumn) * columnWidth;
            int buttonHeight = Math.min(28, rowHeight - 14);
            int stepWidth = columnWidth >= 280 ? 36 : 28;
            int applyWidth = columnWidth >= 280 ? 50 : 38;
            int inputWidth = columnWidth - 2 * stepWidth - applyWidth - 20;
            if (row.text == null) row.text = TrackSetting.format(track == null ? row.setting.defaultValue : track.getSettingValue(row.setting.key));
            row.minus = addRenderableWidget(Button.builder(Component.literal("−"), b -> step(row, -1)).bounds(x, 0, stepWidth, buttonHeight).build());
            row.input = addRenderableWidget(new EditBox(font, x + stepWidth + 4, 0, inputWidth, buttonHeight, name(row.setting)));
            row.input.setMaxLength(24);
            row.input.setValue(row.text);
            row.input.setResponder(text -> {
                row.text = text;
                row.dirty = true;
                row.pending = null;
                refreshValidity(row);
                if (row.setting.parse(text) == null) invalid(row);
            });
            row.plus = addRenderableWidget(Button.builder(Component.literal("+"), b -> step(row, 1))
                .bounds(x + stepWidth + inputWidth + 8, 0, stepWidth, buttonHeight).build());
            row.apply = addRenderableWidget(Button.builder(Component.translatable("screen.trackssimulate.apply"), b -> apply(row))
                .bounds(x + 2 * stepWidth + inputWidth + 12, 0, applyWidth, buttonHeight).build());
            refreshValidity(row);
        }
        int footerY = top + panelHeight - 26;
        int footerWidth = Math.min(150, (panelWidth - 32) / 4);
        resetButton=addRenderableWidget(Button.builder(Component.translatable("screen.trackssimulate.reset"), b -> reset())
            .bounds(left + 10, footerY, footerWidth, 20).tooltip(Tooltip.create(Component.translatable("screen.trackssimulate.reset.tooltip"))).build());
        scopeButton=addRenderableWidget(Button.builder(Component.translatable(scope.translationKey()), b -> {
            scope=scope.next(expectedBelt!=null);b.setMessage(Component.translatable(scope.translationKey()));refreshControls();
        }).bounds(left+14+footerWidth,footerY,footerWidth,20)
            .tooltip(Tooltip.create(Component.translatable("screen.trackssimulate.offset_scope.tooltip"))).build());
        syncButton=addRenderableWidget(Button.builder(Component.translatable("screen.trackssimulate.sync_group"), b -> syncAll())
            .bounds(left+18+2*footerWidth,footerY,footerWidth,20)
            .tooltip(Tooltip.create(Component.translatable("screen.trackssimulate.sync_group.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(left + panelWidth - footerWidth - 10, footerY, footerWidth, 20)
            .tooltip(Tooltip.create(Component.translatable("screen.trackssimulate.done.tooltip"))).build());
        positionRows();refreshControls();
    }

    private boolean supported(SettingRow row) {
        var t=track();
        return t!=null&&(scope==OffsetScope.ASSEMBLY||row.setting.supports(t.effectiveRole()==SableTrackRole.SUSPENSION,t.effectiveRole()==SableTrackRole.DRIVE));
    }
    private boolean editable() {
        var t=track();return t!=null&&pendingRequest==null&&Objects.equals(expectedBelt,t.beltId());
    }
    private void refreshControls() {
        for(var row:rows)if(row.input!=null)refreshValidity(row);
        if(syncButton!=null)syncButton.active=editable();
        if(resetButton!=null)resetButton.active=editable();
        if(scopeButton!=null)scopeButton.active=editable();
    }

    private void positionRows() {
        for (int i = 0; i < rows.length; i++) {
            SettingRow row = rows[i];
            row.y = contentTop + (i % rowsPerColumn) * rowHeight - scroll;
            row.visible = row.y >= contentTop && row.y + rowHeight <= contentBottom;
            for (var widget : new net.minecraft.client.gui.components.AbstractWidget[]{row.minus, row.input, row.plus, row.apply}) {
                widget.setY(row.y + 11);
                widget.visible = row.visible;
                if (!row.visible && getFocused() == widget) setFocused(null);
            }
        }
    }

    private static Component name(TrackSetting setting) {
        return Component.translatable("setting.trackssimulate." + setting.key);
    }

    private void refreshValidity(SettingRow row) {
        boolean valid = row.setting.parse(row.text) != null;
        row.input.setTextColor(!valid ? 0xff7777 : row.dirty ? 0xffe077 : 0x9cdb91);
        boolean enabled=editable()&&supported(row);
        row.input.setEditable(enabled);
        row.apply.active = valid&&enabled;
        row.minus.active = valid&&enabled;
        row.plus.active = valid&&enabled;
    }

    private void replaceText(SettingRow row, double value) {
        row.input.setValue(TrackSetting.format(value));
        row.text = row.input.getValue();
    }

    private void invalid(SettingRow row) {
        status = Component.translatable("screen.trackssimulate.invalid", name(row.setting), TrackSetting.format(row.setting.min),
            TrackSetting.format(row.setting.max), Component.translatable(row.setting.integer ? "screen.trackssimulate.integer" : "screen.trackssimulate.number"));
        statusColor = 0xff7777;
    }

    private void step(SettingRow row, int direction) {
        Double current = row.setting.parse(row.text);
        if (current == null) { invalid(row); return; }
        replaceText(row, row.setting.increment(current, direction));
        apply(row);
    }

    private void apply(SettingRow row) {
        if(!editable()||!supported(row))return;
        Double value=row.setting.parse(row.text);
        if(value==null){invalid(row);return;}
        submit(Map.of(row.setting,value));
    }

    private void syncAll() {
        var values=new EnumMap<TrackSetting,Double>(TrackSetting.class);
        for(var row:rows)if(supported(row)) {
            Double value=row.setting.parse(row.text);
            if(value==null){invalid(row);return;}
            values.put(row.setting,value);
        }
        submit(values);
    }
    private void submit(Map<TrackSetting,Double> values) {
        if(!editable()||values.isEmpty())return;
        pendingRequest=UUID.randomUUID();pendingValues=Map.copyOf(values);pendingTicks=0;
        for(var row:rows)if(values.containsKey(row.setting)){row.pending=values.get(row.setting);row.dirty=false;}
        PacketDistributor.sendToServer(new SetTrackSettingPayload(pos,pendingRequest,expectedBelt,scope,values));
        status=Component.translatable("screen.trackssimulate.sync_group.sent");statusColor=0xffe077;
        refreshControls();
    }

    private void reset() {
        if(!editable())return;
        var values=new EnumMap<TrackSetting,Double>(TrackSetting.class);
        for(var row:rows)if(row.setting.resetsWithSuspension()&&supported(row)) {
            double value=track().defaultSettingValue(row.setting);
            replaceText(row,value);values.put(row.setting,value);
        }
        submit(values);
    }

    @Override public void tick() {
        var track=track();if(track==null){onClose();return;}
        if(!Objects.equals(expectedBelt,track.beltId())) {
            status=Component.literal("履带连接状态已变化，请关闭并重新打开 GUI");statusColor=0xff7777;
        }
        if(pendingRequest!=null&&++pendingTicks>=200) {
            for(var row:rows)if(pendingValues.containsKey(row.setting)){row.pending=null;row.dirty=true;}
            pendingRequest=null;pendingValues=Map.of();
            status=Component.literal("未收到服务器确认；输入已保留，请重新打开 GUI 核对实际参数");statusColor=0xff7777;
        }
        // Preserve this editor snapshot: group-only settings may not apply to its source role.
        refreshControls();
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            for (SettingRow row : rows) if (row.input.isFocused()) { apply(row); return true; }
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x >= left && x < left + panelWidth && y >= contentTop && y < contentBottom && maxScroll > 0) {
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int)Math.round(vertical * rowHeight)));
            positionRows();
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        var track = track();
        if (track == null) { onClose(); return; }
        g.fill(left, top, left + panelWidth, top + panelHeight, 0xf522262d);
        g.drawString(font, title, left + 10, top + 8, 0xffe077, false);
        Component extension = Component.translatable("screen.trackssimulate.extension", String.format(Locale.ROOT, "%.3f", track.getLerpedExtension(1)));
        g.drawString(font, extension, left + panelWidth - 10 - font.width(extension), top + 8, 0xaabbcc, false);
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.trackssimulate.editor_hint").getString(), panelWidth - 20), left + 10, top + 23, 0xb8c6d4, false);
        Component hovered = null;
        for (SettingRow row : rows) {
            if (!row.visible) continue;
            int x = row.minus.getX();
            Component label = name(row.setting).copy().append(row.dirty ? " *" : row.pending != null ? " …" : "");
            if(row.setting==TrackSetting.THROUGH_AXLE&&row.setting.parse(row.text)!=null)
                label=label.copy().append("：").append(Component.translatable(dev.trackssimulate.config.DriveAxleMode.fromSaved(row.setting.parse(row.text).intValue(),false).translationKey()));
            g.drawString(font, font.plainSubstrByWidth(label.getString(), columnWidth - 8), x, row.y, 0xffffff, false);
            if (mouseX >= x && mouseX < x + columnWidth - 4 && mouseY >= row.y && mouseY < row.y + rowHeight) {
                hovered = name(row.setting).copy().append("\n")
                    .append(Component.translatable("setting.trackssimulate." + row.setting.key + ".tooltip"))
                    .append("\n").append(Component.translatable("screen.trackssimulate.range", TrackSetting.format(row.setting.min),
                        TrackSetting.format(row.setting.max), TrackSetting.format(track.defaultSettingValue(row.setting)), TrackSetting.format(row.setting.step)))
                    .append("\n").append(Component.translatable(scope.translationKey()))
                    .append(supported(row)?"":"\n当前轮种不使用此项；整组同步时只应用到支持它的轮种。")
                    .append("\n").append(Component.translatable("screen.trackssimulate.input.tooltip"));
            }
        }
        if (maxScroll > 0) {
            int barHeight = Math.max(12, (contentBottom - contentTop) * (contentBottom - contentTop) / (rowsPerColumn * rowHeight));
            int barY = contentTop + (contentBottom - contentTop - barHeight) * scroll / maxScroll;
            g.fill(left + panelWidth - 6, contentTop, left + panelWidth - 3, contentBottom, 0xff495360);
            g.fill(left + panelWidth - 6, barY, left + panelWidth - 3, barY + barHeight, 0xffa6c59e);
        }
        Component footer = status.getString().isEmpty() ? Component.translatable("screen.trackssimulate.extension", String.format(Locale.ROOT, "%.3f", track.getLerpedExtension(1))) : status;
        g.drawString(font, font.plainSubstrByWidth(footer.getString(), panelWidth - 20), left + 10, top + panelHeight - 41, statusColor, false);
        super.render(g, mouseX, mouseY, partial);
        if (hovered != null) setTooltipForNextRenderPass(font.split(hovered, Math.min(360, width - 24)));
        else if (mouseY >= top + panelHeight - 43 && mouseY < top + panelHeight - 28 && mouseX >= left && mouseX < left + panelWidth)
            setTooltipForNextRenderPass(font.split(footer, Math.min(360, width - 24)));
    }

    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {}
    @Override public boolean isPauseScreen() { return false; }

    private static final class SettingRow {
        final TrackSetting setting;
        EditBox input;
        Button minus, plus, apply;
        String text;
        boolean dirty, visible;
        Double pending;
        int y;
        SettingRow(TrackSetting setting) { this.setting = setting; }
    }
}
