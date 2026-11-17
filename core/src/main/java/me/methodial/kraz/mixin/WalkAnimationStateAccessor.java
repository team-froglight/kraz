package me.methodial.kraz.mixin;

import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WalkAnimationState.class)
public interface WalkAnimationStateAccessor {
    @Accessor("position")
    void kraz$setPosition(float position);

    @Accessor("speedOld")
    void kraz$setSpeedOld(float speed);
}
