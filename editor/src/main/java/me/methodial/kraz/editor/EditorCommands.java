package me.methodial.kraz.editor;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import me.methodial.kraz.command.KrazCommands;
import me.methodial.kraz.demo.DemoScenes;
import me.methodial.kraz.scene.Scene;
import me.methodial.kraz.scene.SceneIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.nio.file.Files;
import java.util.function.Supplier;

/**
 * The UI half of the client-side {@code /kraz} command (Brigadier merges it with the library's half):
 * <pre>
 *   /kraz edit           back to the last editing session (or a new scene)
 *   /kraz edit  *   /kraz edit [name]    open the scene editor: a new scene, or a saved onelt;name *   /kraz edit [name]    open the scene editor: a new scene, or a saved onegt;    open a saved scene in the editor (a new one if there is none)
 *   /kraz view &lt;name&gt;    open a saved scene in the viewer
 *   /kraz demo           open the showcase scene in the viewer
 * </pre>
 */
public final class EditorCommands {
    private EditorCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("kraz")
                .then(Commands.literal("demo").executes(context -> {
                    open(() -> new SceneViewerScreen(DemoScenes.island(), "demo", null));
                    return 1;
                }))
                .then(Commands.literal("edit")
                        .executes(context -> {
                            open(EditorCommands::reopen);
                            return 1;
                        })
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(KrazCommands.SAVED_SCENES)
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "name");
                                    // An unknown name starts a new scene that will be saved under it.
                                    Scene scene = Files.isRegularFile(SceneIO.scenePath(name)) ? KrazCommands.load(context, name) : Scene.create();
                                    if (scene == null) {
                                        return 0;
                                    }
                                    open(() -> new SceneEditorScreen(scene, name, null));
                                    return 1;
                                })))
                .then(Commands.literal("view")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(KrazCommands.SAVED_SCENES)
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "name");
                                    Scene scene = KrazCommands.load(context, name);
                                    if (scene == null) {
                                        return 0;
                                    }
                                    open(() -> new SceneViewerScreen(scene, name, null));
                                    return 1;
                                }))));
    }

    /** The last editing session (keeps what was being edited and its projection), or a new scene. */
    public static Screen reopen() {
        EditorModel last = SceneEditorScreen.lastSession();
        return last != null ? new SceneEditorScreen(last, null) : new SceneEditorScreen(Scene.create(), "untitled", null);
    }

    private static void open(Supplier<Screen> screen) {
        // The chat screen closes itself after running a command; open ours once it is gone.
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> mc.setScreen(screen.get()));
    }
}
