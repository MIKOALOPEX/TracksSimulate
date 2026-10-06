package dev.trackssimulate.client;

import dev.trackssimulate.physics.*;
import dev.trackssimulate.wheel.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

public final class SuspensionScreen extends Screen {
    private static final String[] PAGES={"悬挂","尺寸/偏移","显示/轴","履带","状态"};
    private static final String[] LABELS={"压缩端（格）","伸长端（格）","自然位置（格）","约束刚度","约束阻尼","单轮质量","轮径倍率","轮宽倍率","平面横向偏移","平面纵向偏移","沿轮轴偏移"};
    private static final String[] HELP={
        "轮心相对偏移后悬挂基准的最小下降量。越小越靠近基准；必须小于伸长端。",
        "轮心最多向下伸到的位置。上限 4 格，行程至少 0.01 格。",
        "弹簧恢复的目标位置，须在两端之间。自重会使悬空位置进一步下降。",
        "恢复强度，0–10000。越大通常越难压缩。使用引擎系数，不是 N/m。",
        "抑制伸缩速度与弹跳，0–1000。太大也会使响应迟缓，不是现实单位。",
        "1–10000。未连带时为独立轮体质量；连带后为悬挂伸缩的等效质量，不再额外创建轮体刚体。缩放不会自动改变它。",
        "0.25–3 倍。改变模型、绕带路径和显示转速；负重轮碰撞盒随之缩放；不会同比拉长悬挂行程。",
        "0.25–3 倍。改变轮体宽度，负重轮碰撞盒随之缩放，保存时自动将此倍率同步给同环三种轮；带宽随之变化，可在履带页另调带宽倍率。",
        "-4–4 格。X 轴轮沿车体 +Z，Z/Y 轴轮沿 +X；负数反向。",
        "-4–4 格。X/Z 轴轮沿车体 +Y，Y 轴轮沿 +Z。移动悬挂基准，行程从新基准计算。",
        "-4–4 格。沿车体轮轴正方向移动。已连接的所有轮心必须共面，越出带平面的修改会被拒绝。"
    };
    private static final String[] DEFAULTS={"0","1","0.5","100","20","100","1","1","0","0","0"};
    private final BlockPos pos;private final ResourceLocation dimension;private final boolean linked,road,drive;private final WheelBlockEntity wheel;
    private final Map<Integer,EditBox> fields=new LinkedHashMap<>();
    private final String[] values;
    private boolean enabled,visible,strut,wholeLoop,radiusAll,fullAxle,positive;
    private String error="";private int page,left,top,waiting;
    private UUID pending;private Button saveButton;
    public static void open(WheelBlockEntity wheel) { Minecraft.getInstance().setScreen(new SuspensionScreen(wheel)); }
    private SuspensionScreen(WheelBlockEntity wheel) {
        super(Component.literal("轮组调试"));this.wheel=wheel;road=wheel.kind()==WheelKind.ROAD;drive=wheel.kind()==WheelKind.DRIVE;page=1;positive=wheel.getBlockState().getValue(WheelBlock.POSITIVE);fullAxle=wheel.getBlockState().getValue(WheelBlock.FULL_AXLE);pos=wheel.getBlockPos();dimension=wheel.getLevel().dimension().location();linked=wheel.loopId()!=null;
        enabled=wheel.getBlockState().getValue(WheelBlock.SUSPENSION);visible=wheel.wheelVisible();strut=wheel.strutVisible();
        var s=wheel.suspensionSettings();var g=wheel.geometry();
        values=new String[]{""+s.minimum(),""+s.maximum(),""+s.rest(),""+s.stiffness(),""+s.damping(),""+s.mass(),""+g.radiusScale(),""+g.widthScale(),""+g.forward(),""+g.vertical(),""+g.axial()};
    }
    private void capture() { fields.forEach((i,field)->values[i]=field.getValue());fields.clear(); }
    @Override protected void init() {
        capture();clearWidgets();left=width/2-150;top=Math.max(3,(height-234)/2);
        for(int i=0;i<PAGES.length;i++) {
            final int next=i;
            Button tab=addRenderableWidget(Button.builder(Component.literal(PAGES[i]),b->{capture();if(next==3){BeltScreen.open(wheel,this);return;}page=next;init();}).bounds(left+8+i*58,top+23,54,18).build());
            tab.active=page!=i&&pending==null&&(i!=0||road)&&(i!=3||linked);
            if(i==3) tab.setTooltip(Tooltip.create(Component.literal("打开整条履带的摩擦、动力、宽度和厚度设置；关闭后返回，保留当前轮子尚未保存的输入。")));
        }
        int first=page==0?0:6,count=page==0?6:page==1?5:0;
        for(int row=0;row<count;row++) {
            int index=first+row;
            EditBox field=new EditBox(font,left+150,top+48+row*21,140,18,Component.literal(LABELS[index]));
            field.setMaxLength(16);field.setValue(values[index]);field.setTooltip(Tooltip.create(Component.literal(HELP[index])));
            field.setEditable(pending==null);fields.put(index,field);addRenderableWidget(field);
        }
        if(page==1) addRenderableWidget(Button.builder(Component.literal(radiusAll?"轮径：同步所有轮":"轮径：仅当前轮"),b->{radiusAll=!radiusAll;b.setMessage(Component.literal(radiusAll?"轮径：同步所有轮":"轮径：仅当前轮"));})
            .bounds(left+8,top+155,282,18).tooltip(Tooltip.create(Component.literal("仅同步轮径倍率，不覆盖其他轮的偏移、悬挂和显示。轮宽倍率始终同步整环所有轮。"))).build()).active=linked&&pending==null;
        if(page==2) {
            if(drive) addRenderableWidget(Button.builder(Component.literal(positive?"半轴输入：轴正向":"半轴输入：轴负向"),b->{positive=!positive;b.setMessage(Component.literal(positive?"半轴输入：轴正向":"半轴输入：轴负向"));})
                .bounds(left+16,top+145,268,20).tooltip(Tooltip.create(Component.literal("X 正向为东，Y 正向为上，Z 正向为南；全轴时此设置不限制动力连接。"))).build()).active=pending==null;
            if(drive) addRenderableWidget(Button.builder(Component.literal(fullAxle?"导轮：全轴透传":"导轮：半轴"),b->{fullAxle=!fullAxle;b.setMessage(Component.literal(fullAxle?"导轮：全轴透传":"导轮：半轴"));})
                .bounds(left+16,top+118,268,20).tooltip(Tooltip.create(Component.literal("保存后生效；半轴输入侧通过下方按钮选择。"))).build()).active=pending==null;
            addRenderableWidget(Button.builder(Component.literal(visible?"轮体：显示":"轮体：隐藏"),b->{visible=!visible;b.setMessage(Component.literal(visible?"轮体：显示":"轮体：隐藏"));})
                .bounds(left+16,top+58,268,20).tooltip(Tooltip.create(Component.literal("只改变模型显示；碰撞、质量、悬挂和履带连接仍然存在。"))).build()).active=pending==null;
            addRenderableWidget(Button.builder(Component.literal(strut?"连接支杆：显示":"连接支杆：隐藏"),b->{strut=!strut;b.setMessage(Component.literal(strut?"连接支杆：显示":"连接支杆：隐藏"));})
                .bounds(left+16,top+88,268,20).tooltip(Tooltip.create(Component.literal("单独控制安装点到移动轮心的白色连接杆。"))).build()).active=pending==null;
        }
        addRenderableWidget(Button.builder(Component.literal(!road?"此轮无悬挂":enabled?"悬挂：开启":"悬挂：关闭"),b->{enabled=!enabled;b.setMessage(Component.literal(!road?"此轮无悬挂":enabled?"悬挂：开启":"悬挂：关闭"));})
            .bounds(left+8,top+177,110,18).tooltip(Tooltip.create(Component.literal("关闭伸缩，保留配置位置处的固定轮体碰撞；Y 轴轮始终固定。"))).build()).active=road&&pending==null;
        addRenderableWidget(Button.builder(Component.literal(wholeLoop?"悬挂等：同环负重轮":"悬挂等：仅当前轮"),b->{wholeLoop=!wholeLoop;b.setMessage(Component.literal(wholeLoop?"悬挂等：同环负重轮":"悬挂等：仅当前轮"));})
            .bounds(left+122,top+177,168,18).tooltip(Tooltip.create(Component.literal("此范围仅控制负重轮的悬挂、偏移和显示。尺寸页单独选择轮径范围；轮宽始终同步全部轮种。"))).build()).active=road&&linked&&pending==null;
        addRenderableWidget(Button.builder(Component.literal("本页默认"),b->{capture();if(page==2){visible=true;strut=true;fullAxle=true;positive=true;}else {int start=page==0?0:6,end=page==0?6:11;for(int i=start;i<end;i++) values[i]=DEFAULTS[i];if(page==0) enabled=true;}init();})
            .bounds(left+8,top+199,106,20).build()).active=pending==null&&page!=4;
        saveButton=addRenderableWidget(Button.builder(Component.literal(pending==null?"保存":"保存中"),b->save()).bounds(left+122,top+199,80,20).build());saveButton.active=pending==null;
        if(!error.isEmpty()) saveButton.setTooltip(Tooltip.create(Component.literal(error)));
        addRenderableWidget(Button.builder(Component.literal("关闭"),b->onClose()).bounds(left+210,top+199,80,20).build());
    }
    private void save() {
        capture();
        try {
            double[] v=new double[11];for(int i=0;i<11;i++) v[i]=Double.parseDouble(values[i].trim());
            new SuspensionSettings(v[0],v[1],v[2],v[3],v[4],v[5]);new WheelGeometry(v[6],v[7],v[8],v[9],v[10]);
            pending=UUID.randomUUID();waiting=0;error="";
            PacketDistributor.sendToServer(new SuspensionNetwork.Apply(pending,pos,dimension,enabled,visible,strut,wholeLoop,radiusAll,fullAxle,positive,v));
        } catch(IllegalArgumentException ex) { error=ex instanceof NumberFormatException?"请输入有效数字；悬停输入框查看说明":ex.getMessage(); }
        init();
    }
    @Override public void tick() {
        super.tick();if(pending==null) return;
        var reply=SuspensionNetwork.clientReply;
        if(reply!=null&&reply.request().equals(pending)) {
            pending=null;SuspensionNetwork.clientReply=null;
            if(reply.success()) { if(minecraft.player!=null) minecraft.player.displayClientMessage(Component.literal(reply.message()),true);onClose(); }
            else { error=reply.message();init(); }
        } else if(++waiting>100) { pending=null;error="未收到保存结果，请确认连接后重试";init(); }
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics graphics,int mouseX,int mouseY,float partial) {
        // In 1.21.1 Screen.render calls this once before rendering the widgets.
        super.renderBackground(graphics,mouseX,mouseY,partial);
        graphics.fill(left,top,left+300,top+234,0xEE242424);
        graphics.drawCenteredString(font,title,width/2,top+8,0xFFCB74);
        int first=page==0?0:6,count=page==0?6:page==1?5:0;
        for(int row=0;row<count;row++) graphics.drawString(font,LABELS[first+row],left+8,top+53+row*21,0xFFFFFF,false);

        if(page==2&&!drive) {
            graphics.drawWordWrap(font,Component.literal("隐藏保留物理与连接；调试棒可恢复。"),left+16,top+145,268,0xCCCCCC);
        }
        if(page==4) {
            String[] status={"转速："+String.format(Locale.ROOT,"%.2f RPM",wheel.getSpeed()),
                "轮径："+String.format(Locale.ROOT,"%.3f 格",wheel.radius()),
                "轮心下降："+String.format(Locale.ROOT,"%.3f 格",wheel.renderExtension(1)),
                "轮子："+wheel.physicsStatus(),
                "履带："+(wheel.root()==null?"未连接或结构未加载":wheel.root().beltStatus())};
            for(int i=0;i<status.length;i++) graphics.drawWordWrap(font,Component.literal(status[i]),left+12,top+48+i*24,276,0xDDDDDD);
        }
        String footer=error.isEmpty()?"悬停输入框查看说明；修改后点击保存":font.plainSubstrByWidth(error,282);
        graphics.drawString(font,footer,left+8,top+223,error.isEmpty()?0xAAAAAA:0xFF7171,false);
    }
}
