package com.dogpound.prideprism;

import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** The two machine items. Recipes: assets/prideprism/recipes (gated mid-game on purpose: you work for wireless). */
@Mod.EventBusSubscriber(modid = "prideprism")
public final class Registry {
    private Registry() {}

    @SubscribeEvent
    public static void items(RegistryEvent.Register<Item> e) {
        Machines.TRANSMITTER = new Machines.ItemTransmitter();
        Machines.TABLET = new Machines.ItemTablet();
        e.getRegistry().registerAll(Machines.TRANSMITTER, Machines.TABLET);
    }

    @SideOnly(Side.CLIENT)
    @Mod.EventBusSubscriber(modid = "prideprism", value = Side.CLIENT)
    public static final class Models {
        @SubscribeEvent
        public static void models(ModelRegistryEvent e) {
            for (Item it : new Item[]{Machines.TRANSMITTER, Machines.TABLET})
                ModelLoader.setCustomModelResourceLocation(it, 0, new ModelResourceLocation(it.getRegistryName(), "inventory"));
        }
    }
}
