//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package cpw.mods.forge.serverpackutility;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.mojang.authlib.GameProfile;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.LamdbaExceptionUtils;
import cpw.mods.modlauncher.api.TypesafeMap.Key;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment.Keys;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("serverpacklocatorutility")
public class UtilityMod {

    public static final Logger LOGGER = LogManager.getLogger();

    public UtilityMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClient);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStart);
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> () -> {
            MinecraftForge.registerConfigScreen(ConfigScreen::new);
        });
    }

    private void onClient(FMLClientSetupEvent event) {
        MinecraftForge.EVENT_BUS.addListener(UtilityMod.Wrapper::onShowGui);
    }

    private void onServerStart(ServerStartedEvent startedEvent) {
        try {
            Optional<ClassLoader> classLoader = Launcher.INSTANCE.environment().getProperty((Key)Keys.LOCATORCLASSLOADER.get());
            Class<?> clz = LamdbaExceptionUtils.uncheck(() -> Class.forName("cpw.mods.forge.serverpacklocator.ModAccessor", true, classLoader.orElse(Thread.currentThread().getContextClassLoader())));
            Method setIsWhiteListed = LamdbaExceptionUtils.uncheck(() -> clz.getMethod("setIsWhiteListed", Function.class));
            Method setIsWhiteListEnabled = LamdbaExceptionUtils.uncheck(() -> clz.getMethod("setIsWhiteListEnabled", Supplier.class));
            Method setNameResolver = LamdbaExceptionUtils.uncheck(() -> clz.getMethod("setNameResolver", Function.class));
            LamdbaExceptionUtils.uncheck(() -> setIsWhiteListed.invoke(null, (Function<UUID, CompletableFuture<Boolean>>)(id) -> startedEvent.getServer().submit(() -> {
                return startedEvent.getServer().getPlayerList().getWhiteList().isWhiteListed(new GameProfile(id, "")); //Name does not matter
            })));
            LamdbaExceptionUtils.uncheck(() -> setIsWhiteListEnabled.invoke(null, (Supplier<CompletableFuture<Boolean>>)() -> startedEvent.getServer().submit(() -> startedEvent.getServer().getPlayerList().isUsingWhitelist())));
            LamdbaExceptionUtils.uncheck(() -> setNameResolver.invoke(null, (Function<UUID, CompletableFuture<Optional<String>>>)(id) -> {
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
                    List<String> branding = (List<String>) LamdbaExceptionUtils.uncheck(() -> (List)brandingList.get(null));
                    if (branding != null) {
                        Builder<String> brd = ImmutableList.builder();
                        brd.addAll(branding);
                        brd.add(statusMessage.get());
                        LamdbaExceptionUtils.uncheck(() -> brandingList.set(null, brd.build()));
                        brandingHacked = true;
                    }

                }
            }
        }

        static {
            Class<?> brdControl = LamdbaExceptionUtils.uncheck(() -> Class.forName("net.minecraftforge.internal.BrandingControl", true, Thread.currentThread().getContextClassLoader()));
            brandingList = LamdbaExceptionUtils.uncheck(() -> brdControl.getDeclaredField("overCopyrightBrandings"));
            brandingList.setAccessible(true);

            Supplier<String> statMessage;
            try {
                Optional<ClassLoader> classLoader = Launcher.INSTANCE.environment().getProperty((Key)Keys.LOCATORCLASSLOADER.get());
                Class<?> clz = LamdbaExceptionUtils.uncheck(() -> Class.forName("cpw.mods.forge.serverpacklocator.ModAccessor", true, classLoader.orElse(Thread.currentThread().getContextClassLoader())));
                Method status = LamdbaExceptionUtils.uncheck(() -> clz.getMethod("getStatusLine"));
                statMessage = () -> LamdbaExceptionUtils.uncheck(() -> (String)status.invoke(null));
            } catch (Throwable var5) {
                LogManager.getLogger().catching(var5);
                statMessage = () -> "ServerPack: FAILED TO LOAD STATUS";
            }

            statusMessage = statMessage;
        }
    }
}
