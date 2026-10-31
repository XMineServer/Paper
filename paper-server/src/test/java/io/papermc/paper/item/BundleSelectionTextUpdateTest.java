package io.papermc.paper.item;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.BundleContents;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

@AllFeatures
class BundleSelectionTextUpdateTest {

    @Test
    void changingNameAndLoreOnAClonePreservesSelectedBundleIndex() {
        var contents = new BundleContents(List.of(
            ItemStackTemplate.fromNonEmptyStack(new net.minecraft.world.item.ItemStack(Items.PAPER)),
            ItemStackTemplate.fromNonEmptyStack(new net.minecraft.world.item.ItemStack(Items.STONE)),
            ItemStackTemplate.fromNonEmptyStack(new net.minecraft.world.item.ItemStack(Items.DIRT))
        ));
        var selection = new BundleContents.Mutable(contents);
        selection.toggleSelectedItem(1);

        var original = new net.minecraft.world.item.ItemStack(Items.BUNDLE);
        original.set(DataComponents.BUNDLE_CONTENTS, selection.toImmutable());
        ItemStack updated = CraftItemStack.asCraftMirror(original).clone();

        updated.setData(DataComponentTypes.CUSTOM_NAME, Component.text("Efficiency IV"));
        updated.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(Component.text("Selected: IV"))));

        var updatedHandle = CraftItemStack.asNMSCopy(updated);
        var updatedContents = updatedHandle.get(DataComponents.BUNDLE_CONTENTS);
        assertNotSame(original, updatedHandle);
        assertEquals(3, updatedContents.size());
        assertEquals(1, updatedContents.getSelectedItemIndex());
        assertEquals(contents.items(), updatedContents.items());
        assertEquals(Component.text("Efficiency IV"), updated.getData(DataComponentTypes.CUSTOM_NAME));
        assertEquals(List.of(Component.text("Selected: IV")), updated.getData(DataComponentTypes.LORE).lines());
        assertEquals(1, original.get(DataComponents.BUNDLE_CONTENTS).getSelectedItemIndex());
    }
}
