package dev.trackssimulate.wheel;

import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.foundation.block.IBE;
import dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape;
import dev.trackssimulate.TrackContent;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.shapes.*;

public final class WheelBlock extends KineticBlock implements IBE<WheelBlockEntity>,BlockSubLevelCollisionShape,com.simibubi.create.content.equipment.wrench.IWrenchable {
    public static final EnumProperty<Direction.Axis> AXIS=BlockStateProperties.AXIS;
    public static final BooleanProperty FULL_AXLE=BooleanProperty.create("full_axle");
    public static final BooleanProperty POSITIVE=BooleanProperty.create("positive");
    public static final BooleanProperty SUSPENSION=BooleanProperty.create("suspension");
    public static final BooleanProperty BELTED=BooleanProperty.create("belted");
    public final WheelKind kind;
    public WheelBlock(WheelKind kind,Properties properties) {
        super(properties);this.kind=kind;
        registerDefaultState(stateDefinition.any().setValue(AXIS,Direction.Axis.X).setValue(FULL_AXLE,true).setValue(POSITIVE,true).setValue(SUSPENSION,true).setValue(BELTED,false));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(AXIS,FULL_AXLE,POSITIVE,SUSPENSION,BELTED); }
    @Override public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        Direction face=context.getClickedFace();
        return defaultBlockState().setValue(AXIS,face.getAxis())
            .setValue(POSITIVE,face.getOpposite().getAxisDirection()==Direction.AxisDirection.POSITIVE);
    }
    @Override public Direction.Axis getRotationAxis(BlockState state) { return state.getValue(AXIS); }
    @Override protected boolean areStatesKineticallyEquivalent(BlockState oldState,BlockState newState) {
        return super.areStatesKineticallyEquivalent(oldState,newState)
            &&oldState.getValue(FULL_AXLE)==newState.getValue(FULL_AXLE)&&oldState.getValue(POSITIVE)==newState.getValue(POSITIVE);
    }
    @Override public boolean hasShaftTowards(LevelReader level,BlockPos pos,BlockState state,Direction face) {
        return kind==WheelKind.DRIVE && face.getAxis()==state.getValue(AXIS)
            && (state.getValue(FULL_AXLE) || (face.getAxisDirection()==Direction.AxisDirection.POSITIVE)==state.getValue(POSITIVE));
    }
    @Override public Class<WheelBlockEntity> getBlockEntityClass() { return WheelBlockEntity.class; }
    @Override public BlockEntityType<? extends WheelBlockEntity> getBlockEntityType() { return TrackContent.WHEEL_ENTITY.get(); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.ENTITYBLOCK_ANIMATED; }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context) {
        // Keep a selectable installation point; Sable collision is supplied by the wheel or belt.
        double r=Math.min(kind.radius,0.49),w=Math.min(kind.width*0.5,0.49);
        return switch(state.getValue(AXIS)) {
            case X->Shapes.box(.5-w,.5-r,.5-r,.5+w,.5+r,.5+r);
            case Y->Shapes.box(.5-r,.5-w,.5-r,.5+r,.5+w,.5+r);
            case Z->Shapes.box(.5-r,.5-r,.5-w,.5+r,.5+r,.5+w);
        };
    }
    @Override public VoxelShape getSubLevelCollisionShape(BlockGetter level,BlockState state) {
        // A second fixed mount collider snags ramps before the displaced wheel/belt reaches them.
        if(kind==WheelKind.ROAD||state.getValue(BELTED))
            return Shapes.empty();
        return getShape(state,level,BlockPos.ZERO,CollisionContext.empty());
    }
    @Override public void onRemove(BlockState state,Level level,BlockPos pos,BlockState next,boolean moving) {
        if(!state.is(next.getBlock())&&!moving&&!level.isClientSide&&level.getBlockEntity(pos) instanceof WheelBlockEntity wheel) {
            dev.trackssimulate.track.BeltLifecycle.breakLoop(wheel,null,"wheel_removed");
            var paid=wheel.materialItem();wheel.discardMaterialItem();
            if(!paid.isEmpty())Block.popResource(level,pos,paid);
        }
        super.onRemove(state,level,pos,next,moving);
    }
    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState state,net.minecraft.world.entity.player.Player player) {
        if(player.isCreative()&&level.getBlockEntity(pos) instanceof WheelBlockEntity wheel)wheel.discardMaterialItem();
        return super.playerWillDestroy(level,pos,state,player);
    }
}
