package cpw.mods.forge.serverpackutility;

import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.LambdaExceptionUtils;
import cpw.mods.modlauncher.api.TypesafeMap.Key;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.apache.commons.lang3.tuple.Pair;

public class ConfigScreen extends Screen {

    private final Screen modsScreen;
    private final List<Checkbox> checkboxs;
    
    public ConfigScreen(Screen modsScreen) {
        super(Component.literal("Config screen"));
        this.modsScreen = modsScreen;
        this.checkboxs = new ArrayList<>();
    }
    
    @Override
    protected void init() {
        super.init();
        this.checkboxs.clear();

        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> {
            minecraft.setScreen(modsScreen);
        })
        .bounds(20, this.height - 40, 80, 20)
        .build()
        );

        addRenderableWidget(Button.builder(Component.translatable("selectWorld.edit.save"), b -> {
            var checked = this.checkboxs.stream().map(box -> {
                boolean selected = box.selected();
                var message = box.getMessage().getString();
                return Pair.<String, Boolean>of(message, selected);
            }).toList();
            savePacksSelection(checked);
        })
        .bounds(120, this.height - 40, 80, 20)
        .build()
        );

        var packs = getClientPacks();
        int height = 30;
        for(int i = 0; i < packs.size(); i++) {
            var pack = packs.get(i);
            Checkbox box = Checkbox.builder(Component.literal(pack.getLeft()), this.font)
                .selected(pack.getRight())
                .pos(40, height)
                .build();
            //Checkbox box = new Checkbox(40, height, 20, 20, Component.literal(pack.getLeft()), pack.getRight()); // 1.20.1
            addRenderableWidget(box);
            this.checkboxs.add(box);
            height += 25;
       }
    }
    
    @Override
    public void render(GuiGraphics pGuiGraphics, int pMouseX, int pMouseY, float pPartialTick) {
        //renderBackground(pGuiGraphics);
        super.render(pGuiGraphics, pMouseX, pMouseY, pPartialTick);
        pGuiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 17, -1);
    }

    private static Method getClientPacks = null;
    private static Method savePacksSelection = null;
    static {
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            Class<?> modAccessor = LambdaExceptionUtils.uncheck(() -> Class.forName("cpw.mods.forge.serverpacklocator.ModAccessor", true, classLoader));

            getClientPacks = LambdaExceptionUtils.uncheck(() -> modAccessor.getMethod("getClientPacks"));
            savePacksSelection = LambdaExceptionUtils.uncheck(() -> modAccessor.getMethod("savePacksSelection", List.class));
        } catch (Throwable error) {
            UtilityMod.LOGGER.error("Failed to fetch client packs!", error);
        }
    }

    private static List<Pair<String, Boolean>> getClientPacks() {
        var clientPacksObj = LambdaExceptionUtils.uncheck(() -> getClientPacks.invoke(null));
        return (List<Pair<String, Boolean>>)clientPacksObj;
    }

    private static void savePacksSelection(List<Pair<String, Boolean>> checked) {
        LambdaExceptionUtils.uncheck(() -> savePacksSelection.invoke(null, checked));
    }

}
