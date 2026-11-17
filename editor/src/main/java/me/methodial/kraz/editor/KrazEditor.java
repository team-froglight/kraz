package me.methodial.kraz.editor;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Entry point of the editor mod: the UI commands ({@code /kraz edit|view|demo}) and the editor key. */
@Mod(value = KrazEditor.MOD_ID, dist = Dist.CLIENT)
public final class KrazEditor {
    public static final String MOD_ID = "kraz_editor";

    /** Opens the editor where you left it. Unbound in production (players can bind it); K while developing. */
    public static final KeyMapping OPEN_EDITOR = new KeyMapping("key.kraz_editor.open", InputConstants.Type.KEYSYM,
            FMLEnvironment.production ? InputConstants.UNKNOWN.getValue() : GLFW.GLFW_KEY_K, "key.categories.kraz_editor");

    public KrazEditor(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> e.register(OPEN_EDITOR));
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, event -> EditorCommands.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> {
            Minecraft mc = Minecraft.getInstance();
            while (OPEN_EDITOR.consumeClick()) {
                if (mc.screen == null) {
                    mc.setScreen(EditorCommands.reopen());
                }
            }
        });
    }
}
