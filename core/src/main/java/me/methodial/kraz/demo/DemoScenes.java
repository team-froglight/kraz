package me.methodial.kraz.demo;

import me.methodial.kraz.anim.Easing;
import me.methodial.kraz.anim.Timeline;
import me.methodial.kraz.scene.Scene;
import me.methodial.kraz.scene.SceneEnvironment;
import me.methodial.kraz.scene.ScenePart;
import me.methodial.kraz.scene.element.BlockElement;
import me.methodial.kraz.scene.element.ItemElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A small showcase scene; doubles as a worked example of the scene and animation API. */
public final class DemoScenes {
    private DemoScenes() {
    }

    public static Scene island() {
        Scene scene = Scene.create();
        scene.setEnvironment(SceneEnvironment.DEFAULT.withLightMode(SceneEnvironment.LightMode.COMPUTED));

        // Static terrain goes into the main part and is baked into GPU buffers once.
        ScenePart ground = scene.main();
        ground.fill(new BlockPos(0, 0, 0), new BlockPos(8, 0, 8), Blocks.STONE.defaultBlockState());
        ground.fill(new BlockPos(0, 1, 0), new BlockPos(8, 1, 8), Blocks.DIRT.defaultBlockState());
        ground.fill(new BlockPos(0, 2, 0), new BlockPos(8, 2, 8), Blocks.GRASS_BLOCK.defaultBlockState());
        ground.fill(new BlockPos(1, 2, 1), new BlockPos(3, 2, 3), Blocks.WATER.defaultBlockState());
        ground.setBlock(new BlockPos(2, 1, 2), Blocks.SEA_LANTERN.defaultBlockState());
        ground.setBlock(new BlockPos(1, 3, 5), Blocks.POPPY.defaultBlockState());
        ground.setBlock(new BlockPos(2, 3, 6), Blocks.SHORT_GRASS.defaultBlockState());
        ground.setBlock(new BlockPos(4, 3, 1), Blocks.DANDELION.defaultBlockState());
        ground.setBlock(new BlockPos(0, 3, 8), Blocks.TORCH.defaultBlockState());

        BlockState log = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        ground.fill(new BlockPos(6, 3, 6), new BlockPos(6, 6, 6), log);
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true);
        for (BlockPos pos : BlockPos.betweenClosed(5, 5, 5, 7, 7, 7)) {
            if (ground.getBlockState(pos).isAir()) {
                ground.setBlock(pos.immutable(), leaves);
            }
        }
        ground.setBlock(new BlockPos(6, 8, 6), leaves);

        // A little shelter: shows smooth lighting, translucency and (in a world) a block entity.
        ground.fill(new BlockPos(5, 3, 0), new BlockPos(8, 3, 0), Blocks.STONE_BRICKS.defaultBlockState());
        ground.fill(new BlockPos(8, 3, 1), new BlockPos(8, 3, 3), Blocks.STONE_BRICKS.defaultBlockState());
        ground.fill(new BlockPos(5, 4, 0), new BlockPos(8, 4, 0), Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState());
        ground.fill(new BlockPos(8, 4, 1), new BlockPos(8, 4, 3), Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState());
        ground.setBlock(new BlockPos(7, 3, 1), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
        ground.setBlock(new BlockPos(5, 3, 3), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        ground.setBlock(new BlockPos(4, 3, 7), Blocks.LANTERN.defaultBlockState());

        // Anything that moves as a unit gets its own part; animating it never rebuilds geometry.
        ScenePart tower = scene.part("tower");
        tower.fill(new BlockPos(3, 3, 5), new BlockPos(3, 4, 5), Blocks.COPPER_BLOCK.defaultBlockState());
        tower.setBlock(new BlockPos(3, 5, 5), Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true));
        tower.setPivot(3.5f, 3.0f, 5.5f);

        // Free-moving things are elements.
        BlockElement gem = ground.add(new BlockElement(Blocks.DIAMOND_BLOCK.defaultBlockState()))
                .setPosition(2.25f, 4.5f, 2.25f).setScale(0.5f);
        ground.add(new ItemElement(new ItemStack(Items.NETHERITE_PICKAXE)))
                .setPosition(6.5f, 3.6f, 3.5f).setRotation(0, 45, 0).setScale(0.9f);

        scene.fitCamera();
        scene.camera().setPitch(28);

        Timeline timeline = scene.timeline();
        timeline.cameraYaw().key(0, -35f).key(240, 325f, Easing.IN_OUT_SINE);
        timeline.offset(tower)
                .key(0, new Vector3f(0, 9, 0))
                .key(20, new Vector3f(0, 9, 0))
                .key(60, new Vector3f(0, 0, 0), Easing.OUT_BOUNCE);
        timeline.alpha(tower).key(20, 0.0f).key(35, 1.0f);
        timeline.rotation(tower)
                .key(120, new Quaternionf())
                .key(150, new Quaternionf().rotateY((float) Math.PI), Easing.IN_OUT_CUBIC)
                .key(180, new Quaternionf().rotateY((float) Math.PI * 2 - 0.0001f), Easing.IN_OUT_CUBIC);
        // Custom targets work too: any setter can be keyframed.
        timeline.floatTrack(angle -> gem.setRotation(angle * 0.5f, angle, 0)).key(0, 0f).key(240, 720f);
        timeline.floatTrack(y -> gem.setPosition(2.25f, y, 2.25f))
                .key(0, 4.3f).key(60, 4.8f, Easing.IN_OUT_SINE).key(120, 4.3f, Easing.IN_OUT_SINE)
                .key(180, 4.8f, Easing.IN_OUT_SINE).key(240, 4.3f, Easing.IN_OUT_SINE);
        timeline.setLength(240).setLoop(true);
        timeline.seek(0);
        return scene;
    }
}
