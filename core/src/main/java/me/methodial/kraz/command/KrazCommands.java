package me.methodial.kraz.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import me.methodial.kraz.export.SceneExporter;
import me.methodial.kraz.render.RenderSettings;
import me.methodial.kraz.scene.Scene;
import me.methodial.kraz.scene.SceneIO;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Client-side {@code /kraz} command:
 * <pre>
 *   /kraz capture &lt;from&gt; &lt;to&gt; &lt;name&gt; [entities]   save a box of the world as a scene
 *   /kraz export &lt;name&gt; png|gif [size]           render a saved scene to kraz/exports
 *   /kraz list                                   list saved scenes
 *   /kraz project  *   /kraz list                                   list saved sceneslt;name *   /kraz list                                   list saved scenesgt; [pos] [scale] [yaw]    show a saved scene in the world
 *   /kraz project clear                          remove every projection
 * </pre>
 */
public final class KrazCommands {
    /** Captures beyond this many blocks are refused; they are almost certainly a typo. */
    private static final long MAX_CAPTURE_VOLUME = 256L * 256 * 256;

    public static final SuggestionProvider<CommandSourceStack> SAVED_SCENES =
            (context, builder) -> SharedSuggestionProvider.suggest(savedScenes(), builder);

    private KrazCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("kraz")
                .then(Commands.literal("list").executes(context -> {
                    List<String> names = savedScenes();
                    context.getSource().sendSuccess(() -> names.isEmpty()
                            ? Component.translatable("kraz.command.list.empty")
                            : Component.translatable("kraz.command.list", String.join(", ", names)), false);
                    return names.size();
                }))
                .then(Commands.literal("project")
                        .then(Commands.literal("clear").executes(context -> {
                            int count = me.methodial.kraz.world.WorldProjections.all().size();
                            Minecraft.getInstance().execute(me.methodial.kraz.world.WorldProjections::clear);
                            context.getSource().sendSuccess(() -> Component.translatable("kraz.command.project.cleared", count), false);
                            return count;
                        }))
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(SAVED_SCENES)
                                .executes(context -> project(context, BlockPos.containing(context.getSource().getPosition()), 1, 0))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(context -> project(context, BlockPosArgument.getBlockPos(context, "pos"), 1, 0))
                                        .then(Commands.argument("scale", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.01f, 64f))
                                                .executes(context -> project(context, BlockPosArgument.getBlockPos(context, "pos"),
                                                        com.mojang.brigadier.arguments.FloatArgumentType.getFloat(context, "scale"), 0))
                                                .then(Commands.argument("yaw", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(-360f, 360f))
                                                        .executes(context -> project(context, BlockPosArgument.getBlockPos(context, "pos"),
                                                                com.mojang.brigadier.arguments.FloatArgumentType.getFloat(context, "scale"),
                                                                com.mojang.brigadier.arguments.FloatArgumentType.getFloat(context, "yaw"))))))))
                .then(Commands.literal("capture")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(context -> capture(context, false))
                                                .then(Commands.argument("entities", BoolArgumentType.bool())
                                                        .executes(context -> capture(context, BoolArgumentType.getBool(context, "entities"))))))))
                .then(Commands.literal("export")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(SAVED_SCENES)
                                .then(Commands.literal("png")
                                        .executes(context -> export(context, false, 2048))
                                        .then(Commands.argument("size", IntegerArgumentType.integer(16, 8192))
                                                .executes(context -> export(context, false, IntegerArgumentType.getInteger(context, "size")))))
                                .then(Commands.literal("gif")
                                        .executes(context -> export(context, true, 512))
                                        .then(Commands.argument("size", IntegerArgumentType.integer(16, 2048))
                                                .executes(context -> export(context, true, IntegerArgumentType.getInteger(context, "size"))))))));
    }

    private static int capture(CommandContext<CommandSourceStack> context, boolean entities) {
        CommandSourceStack source = context.getSource();
        BlockPos from = BlockPosArgument.getBlockPos(context, "from");
        BlockPos to = BlockPosArgument.getBlockPos(context, "to");
        String name = StringArgumentType.getString(context, "name");
        long volume = (Math.abs(from.getX() - to.getX()) + 1L) * (Math.abs(from.getY() - to.getY()) + 1L) * (Math.abs(from.getZ() - to.getZ()) + 1L);
        if (volume > MAX_CAPTURE_VOLUME) {
            source.sendFailure(Component.translatable("kraz.command.capture.too_large", volume, MAX_CAPTURE_VOLUME));
            return 0;
        }
        Scene scene = SceneIO.capture(source.getUnsidedLevel(), from, to, entities);
        Path path = SceneIO.scenePath(name);
        try {
            SceneIO.write(scene, path);
        } catch (IOException e) {
            source.sendFailure(Component.translatable("kraz.command.io_error", String.valueOf(e.getMessage())));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("kraz.command.capture.done", name, name), false);
        return 1;
    }

    private static int export(CommandContext<CommandSourceStack> context, boolean gif, int size) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        Scene scene = load(context, name);
        if (scene == null) {
            return 0;
        }
        // Rendering needs the GL context; a command may run mid-tick, so hop to the next render-thread task.
        Minecraft.getInstance().tell(() -> {
            CompletableFuture<Path> result = gif
                    ? SceneExporter.exportGif(scene, SceneExporter.exportPath(name, "gif"), size, size, 20, RenderSettings.HIGH_QUALITY)
                    : SceneExporter.exportPng(scene, SceneExporter.exportPath(name, "png"), size, size, RenderSettings.HIGH_QUALITY);
            result.whenCompleteAsync((path, error) -> {
                if (error == null) {
                    source.sendSuccess(() -> Component.translatable("kraz.viewer.exported", path.getFileName().toString()).withStyle(ChatFormatting.GREEN), false);
                } else {
                    source.sendFailure(Component.translatable("kraz.viewer.export_failed", String.valueOf(error.getMessage())));
                }
            }, Minecraft.getInstance());
        });
        return 1;
    }

    /** Loads a saved scene, reporting failure to the command source; {@code null} if it could not be loaded. */
    public static Scene load(CommandContext<CommandSourceStack> context, String name) {
        Path path = SceneIO.scenePath(name);
        if (!Files.isRegularFile(path)) {
            context.getSource().sendFailure(Component.translatable("kraz.command.not_found", name));
            return null;
        }
        try {
            return SceneIO.read(path);
        } catch (IOException e) {
            context.getSource().sendFailure(Component.translatable("kraz.command.io_error", String.valueOf(e.getMessage())));
            return null;
        }
    }

    /** Shows a saved scene in the world, standing on the given block. */
    private static int project(CommandContext<CommandSourceStack> context, BlockPos pos, float scale, float yaw) {
        String name = StringArgumentType.getString(context, "name");
        Scene scene = load(context, name);
        if (scene == null) {
            return 0;
        }
        var projection = new me.methodial.kraz.world.WorldProjection(scene, net.minecraft.world.phys.Vec3.atBottomCenterOf(pos))
                .setScale(scale).setYaw(yaw);
        Minecraft.getInstance().execute(() -> me.methodial.kraz.world.WorldProjections.add(projection));
        context.getSource().sendSuccess(() -> Component.translatable("kraz.command.project.done", name, pos.toShortString(), scale), false);
        return 1;
    }

    /** Names of the scenes saved in {@code kraz/scenes}. */
    public static List<String> savedScenes() {
        Path dir = SceneIO.scenePath("x").getParent();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".nbt"))
                    .map(n -> n.substring(0, n.length() - 4))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
