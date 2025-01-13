//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package cpw.mods.forge.serverpackutility;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.mojang.authlib.GameProfile;
import cpw.mods.modlauncher.api.IEnvironment.Keys;
import cpw.mods.modlauncher.api.LambdaExceptionUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.ModContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("serverpacklocatorutility")
public class UtilityMod {

    public static final Logger LOGGER = LogManager.getLogger();

    public UtilityMod(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.addListener(this::onServerStart);

        if(FMLLoader.getDist() == Dist.CLIENT) {
            ClientWrapper.handleClient(modEventBus, modContainer);
        }
    }

    private static class ClientWrapper {
        private static void handleClient(IEventBus modEventBus, ModContainer modContainer) {
            modEventBus.addListener(ClientWrapper::onClient);

            modContainer.registerExtensionPoint(
                IConfigScreenFactory.class,
                (mc, modsScreen) -> new ConfigScreen(modsScreen)
            );
        }

        private static void onClient(FMLClientSetupEvent event) {
            NeoForge.EVENT_BUS.addListener(UtilityMod.Wrapper::onShowGui);
        }
    }

    private void onServerStart(ServerStartedEvent startedEvent) {
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            Class<?> clz = LambdaExceptionUtils.uncheck(() -> Class.forName("cpw.mods.forge.serverpacklocator.ModAccessor", true, classLoader));
            Method setIsWhiteListed = LambdaExceptionUtils.uncheck(() -> clz.getMethod("setIsWhiteListed", Function.class));
            Method setIsWhiteListEnabled = LambdaExceptionUtils.uncheck(() -> clz.getMethod("setIsWhiteListEnabled", Supplier.class));
            Method setNameResolver = LambdaExceptionUtils.uncheck(() -> clz.getMethod("setNameResolver", Function.class));
            LambdaExceptionUtils.uncheck(() -> setIsWhiteListed.invoke(null, (Function<UUID, CompletableFuture<Boolean>>)(id) -> startedEvent.getServer().submit(() -> {
                return startedEvent.getServer().getPlayerList().getWhiteList().isWhiteListed(new GameProfile(id, "")); //Name does not matter
            })));
            LambdaExceptionUtils.uncheck(() -> setIsWhiteListEnabled.invoke(null, (Supplier<CompletableFuture<Boolean>>)() -> startedEvent.getServer().submit(() -> startedEvent.getServer().getPlayerList().isUsingWhitelist())));
            LambdaExceptionUtils.uncheck(() -> setNameResolver.invoke(null, (Function<UUID, CompletableFuture<Optional<String>>>)(id) -> {
                return startedEvent.getServer().submit(() -> {
                    return startedEvent.getServer().getProfileCache().get(id).map(GameProfile::getName);
                });
            }));

        } catch (Throwable error) {
            LOGGER.error("Failed to setup Blackboard!", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static class Wrapper {
        private static boolean brandingHacked = false;
        private static final Supplier<String> statusMessage;
        private static final Field brandingList;

        private Wrapper() {
        }

        static void onShowGui(ScreenEvent.Render.Pre event) {
            if (!brandingHacked) {
                if (event.getScreen() instanceof TitleScreen) {
                    List<String> branding = (List<String>) LambdaExceptionUtils.uncheck(() -> (List)brandingList.get(null));
                    if (branding != null) {
                        Builder<String> brd = ImmutableList.builder();
                        brd.addAll(branding);
                        brd.add(statusMessage.get());
                        LambdaExceptionUtils.uncheck(() -> brandingList.set(null, brd.build()));
                        brandingHacked = true;
                    }

                }
            }
        }

        static {
            Class<?> brdControl = LambdaExceptionUtils.uncheck(() -> Class.forName("net.neoforged.neoforge.internal.BrandingControl", true, Thread.currentThread().getContextClassLoader()));
            brandingList = LambdaExceptionUtils.uncheck(() -> brdControl.getDeclaredField("overCopyrightBrandings"));
            brandingList.setAccessible(true);

            Supplier<String> statMessage;
            try {
                ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
                Class<?> clz = LambdaExceptionUtils.uncheck(() -> Class.forName("cpw.mods.forge.serverpacklocator.ModAccessor", true, classLoader));
                Method status = LambdaExceptionUtils.uncheck(() -> clz.getMethod("getStatusLine"));
                statMessage = () -> LambdaExceptionUtils.uncheck(() -> (String)status.invoke(null));
            } catch (Throwable var5) {
                LogManager.getLogger().catching(var5);
                statMessage = () -> "ServerPack: FAILED TO LOAD STATUS";
            }

            statusMessage = statMessage;
        }
    }
}
