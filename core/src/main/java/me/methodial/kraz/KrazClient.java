package me.methodial.kraz;

import me.methodial.kraz.command.KrazCommands;
import me.methodial.kraz.render.post.PostEffects;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Client entry point: wires up shaders, resource reload tracking and the {@code /kraz} command. */
@Mod(value = Kraz.MOD_ID, dist = Dist.CLIENT)
public final class KrazClient {
    private static int resourceGeneration;

    public KrazClient(IEventBus modBus) {
        modBus.addListener(RegisterShadersEvent.class, event -> {
            try {
                PostEffects.register(event);
            } catch (IOException e) {
                throw new UncheckedIOException("Kraz: failed to load post-processing shaders", e);
            }
        });
        modBus.addListener(RegisterShadersEvent.class, me.methodial.kraz.render.shaded.SceneShaders::onRegisterShaders);
        modBus.addListener(RegisterClientReloadListenersEvent.class, event ->
                event.registerReloadListener((ResourceManagerReloadListener) manager -> resourceGeneration++));
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, event -> KrazCommands.register(event.getDispatcher()));
        me.methodial.kraz.world.WorldProjections.install();
    }

    /**
     * Incremented on every resource reload. Baked scene geometry embeds model data and atlas coordinates, so
     * renderers compare this against the value they were built with and rebuild when it differs.
     */
    public static int resourceGeneration() {
        return resourceGeneration;
    }
}
