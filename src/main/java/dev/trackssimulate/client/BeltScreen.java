package dev.trackssimulate.client;

import dev.trackssimulate.physics.*;
import dev.trackssimulate.wheel.WheelBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

public final class BeltScreen extends Screen {
    private static final String[] PAGES={"抓地/侧面","动力/惯量","接触/判定框"};
    private static final int[][] FIELDS={{0,7,13,17},{18,1,2,3,4,11,12},{5,6,8,9,10,14,15,16}};
    private static final String[] LABELS={"纵向摩擦系数","驱动力上限","动力限速（格/秒）","滚动阻力","刹车强度","带面厚度（格）","带宽倍率","横向摩擦系数","径向接触扩张","侧向接触扩张","预测距离上限","驱动加速度上限","惯量占比","侧面抓地倍率","接触柔度","穿透恢复速度","显示接触判定框","侧滑过渡速度","驱动倍率"};
    private static final String[] HELP={
        "0–10，默认 1.2。实际受接触压力限制；提高不等于无限抓地，过高抓地仍可能造成真实倾翻。",
        "0–200000。还受整车质量、有效履带数和加速度上限共同限制，实际限制值写入日志。",
        "0.1–16 格/秒。有动力时目标带速上限；不是车体速度的硬锁定。",
        "0–1000。整条带的滚动阻力；0 可用于自由滚动测试。",
        "0–1。接地时制动；上限随车体质量与本环分配份额计算。",
        "0.02–0.25 格，改变显示和基础碰撞厚度；额外碰撞范围由接触扩张设置。",
        "0.25–3。相对最窄轮宽的 85%，改变显示和基础碰撞宽度。",
        "0–10，默认 1.8。侧滑摩擦；差速原地转向必须允许接触面滑移，不能视作侧向刚性锁。",
        "0–0.5 格，默认 0.025。沿带面厚度方向扩大实际碰撞范围。显示的带厚不变；蓝色框展示实际范围。",
        "0–0.5 格。沿轮轴两侧额外扩大碰撞范围，独立于显示带宽。",
        "0.005–0.5 格，默认 0.15。高速时提前查询的最大间隙；预测本身不会提供悬空支撑。",
        "0.1–200 格/秒²，默认 30。每环驱动力不超过 整车质量/有效履带数×此值；仍受驱动力上限限制。过低会使高抓地、长履带的原地转向缺少力矩。",
        "0.005–1，默认 0.05。每环等效带惯量质量=整车质量/有效履带数×此比例；避免轻车携带异常大的虚拟带惯量。",
        "0–1，默认 0.5。带侧面接触时相对踏面的抓地倍率；按实际接触法向平滑混合，水平安装与侧翻也适用。",
        "0.001–1，默认 0.1。越大接触越柔；使相互抵触的接触有有限反力，避免冲量不断累积。",
        "0–1 格/秒，默认 0.12。穿透后的最大恢复速度；不是撞击恢复弹性。",
        "显示蓝色实际接触盒，包含径向/侧向扩张；切换后保存整环。移动悬挂使用插值显示，日志记录真实物理步。",
        "0–2 格/秒，默认 0.15。横向阻力随滑速逐渐达到摩擦上限，允许差速转向侧滑；越大越容易刮擦转向，但斜坡侧向缓慢滑移也会增加。0 恢复刚性静摩擦。",
        "0–16，默认 1。目标带速=输入 RPM 换算带速×倍率；不改变 Create 轴转速。仍受动力限速、驱动力与抓地限制；0 是零目标速度，非自由滚动。"};
    private final BlockPos pos;private final ResourceLocation dimension;private final String status;private final Screen parent;
    private final Map<Integer,EditBox> fields=new LinkedHashMap<>();private String[] values;
    private int left,top,waiting,page;private String error="";private UUID pending;
    public static void open(WheelBlockEntity wheel) { open(wheel,null); }
    public static void open(WheelBlockEntity wheel,Screen parent) {
        if(wheel.root()!=null) Minecraft.getInstance().setScreen(new BeltScreen(wheel,parent));
    }
    private BeltScreen(WheelBlockEntity wheel,Screen parent) {
        this(wheel,parent,wheel.root());
    }
    private BeltScreen(WheelBlockEntity wheel,Screen parent,WheelBlockEntity root) {
        super(Component.literal("整条履带 · 物理设置"));pos=wheel.getBlockPos();dimension=wheel.getLevel().dimension().location();
        this.parent=parent;var s=root.beltSettings();status=root.beltStatus();
        values=Arrays.stream(s.values()).mapToObj(Double::toString).toArray(String[]::new);
    }
    private void capture() {fields.forEach((i,field)->values[i]=field.getValue());fields.clear();}
    @Override protected void init() {
        capture();clearWidgets();left=width/2-160;top=Math.max(1,(height-238)/2);
        for(int i=0;i<3;i++) {
            final int selected=i;
            addRenderableWidget(Button.builder(Component.literal(PAGES[i]),b->{capture();page=selected;init();}).bounds(left+10+i*102,top+23,98,18).build()).active=pending==null&&page!=i;
        }
        for(int row=0;row<FIELDS[page].length;row++) {
            int i=FIELDS[page][row],y=top+48+row*(page==2?17:page==1?19:22);
            if(i==16) {
                addRenderableWidget(Button.builder(Component.literal(values[16].equals("1.0")?"显示":"隐藏"),b->{values[16]=values[16].equals("1.0")?"0.0":"1.0";b.setMessage(Component.literal(values[16].equals("1.0")?"显示":"隐藏"));}).bounds(left+166,y,143,16).tooltip(Tooltip.create(Component.literal(HELP[i]))).build()).active=pending==null;
                continue;
            }
            EditBox field=new EditBox(font,left+166,y,143,16,Component.literal(LABELS[i]));field.setMaxLength(16);field.setValue(values[i]);
            field.setTooltip(Tooltip.create(Component.literal(HELP[i])));field.setEditable(pending==null);fields.put(i,field);addRenderableWidget(field);
        }
        addRenderableWidget(Button.builder(Component.literal("本页默认"),b->{capture();double[] defaults=BeltSettings.DEFAULT.values();for(int i:FIELDS[page])values[i]=Double.toString(defaults[i]);init();}).bounds(left+10,top+197,94,20).build()).active=pending==null;
        var save=addRenderableWidget(Button.builder(Component.literal(pending==null?"保存整环":"保存中"),b->save()).bounds(left+113,top+197,94,20).build());save.active=pending==null;
        if(!error.isEmpty()) save.setTooltip(Tooltip.create(Component.literal(error)));
        addRenderableWidget(Button.builder(Component.literal("关闭"),b->onClose()).bounds(left+216,top+197,94,20).build());
    }
    private void save() {
        capture();
        try {
            double[] v=new double[BeltSettings.KEYS.length];for(int i=0;i<v.length;i++)v[i]=Double.parseDouble(values[i].trim());BeltSettings.from(v);
            pending=UUID.randomUUID();waiting=0;error="";PacketDistributor.sendToServer(new BeltNetwork.Apply(pending,pos,dimension,v));
        } catch(IllegalArgumentException ex) { error=ex instanceof NumberFormatException?"请输入有效数字":ex.getMessage(); }
        init();
    }
    @Override public void tick() {
        super.tick();if(pending==null)return;var reply=SuspensionNetwork.clientReply;
        if(reply!=null&&reply.request().equals(pending)) {
            pending=null;SuspensionNetwork.clientReply=null;
            if(reply.success()) { if(minecraft.player!=null)minecraft.player.displayClientMessage(Component.literal(reply.message()),true);onClose(); }
            else { error=reply.message();init(); }
        } else if(++waiting>100) { pending=null;error="未收到保存结果，请确认连接后重试";init(); }
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void renderBackground(GuiGraphics graphics,int x,int y,float partial) {
        super.renderBackground(graphics,x,y,partial);
        graphics.fill(left,top,left+320,top+238,0xEE242424);graphics.drawCenteredString(font,title,width/2,top+10,0xFFCB74);
        for(int row=0;row<FIELDS[page].length;row++)graphics.drawString(font,LABELS[FIELDS[page][row]],left+10,top+51+row*(page==2?17:page==1?19:22),0xFFFFFF,false);
        graphics.drawString(font,font.plainSubstrByWidth(status,300),left+10,top+184,0xAAAAAA,false);
        graphics.drawString(font,font.plainSubstrByWidth(error.isEmpty()?"对整环生效；悬停输入框查看说明":error,300),left+10,top+223,error.isEmpty()?0xAAAAAA:0xFF7171,false);
    }
}
