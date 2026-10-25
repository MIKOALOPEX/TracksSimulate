package dev.trackssimulate.mixin;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.*;
import dev.trackssimulate.physics.ConnectedBodies;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Pseudo
@Mixin(targets="dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline",remap=false)
public abstract class ConstraintTopologyMixin {
    @Inject(method="addConstraint",at=@At("RETURN"),remap=false)
    private void tracks$capture(PhysicsPipelineBody a,PhysicsPipelineBody b,PhysicsConstraintConfiguration<?> config,
            CallbackInfoReturnable<PhysicsConstraintHandle> cir){ConnectedBodies.added(this,a,b,cir.getReturnValue());}
    @Inject(method="dispose",at=@At("HEAD"),remap=false)
    private void tracks$clear(CallbackInfo ci){ConnectedBodies.disposed(this);}
}
