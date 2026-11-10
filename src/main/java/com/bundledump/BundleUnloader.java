package com.bundledump;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.lwjgl.glfw.GLFW;

/**
 * Empties a bundle by replaying the vanilla clicks a player would make by hand:
 * right-click the bundle (pulls the most recently added stack onto the cursor),
 * then left-click an empty slot (drops it there). Repeats until the bundle is
 * empty or no suitable empty slot is left, so anything that doesn't fit stays
 * in the bundle.
 *
 * Because it only sends ordinary click packets, it works on vanilla servers and
 * stays in sync with whatever the server-side menu (e.g. Sophisticated Backpacks)
 * decides.
 */
public final class BundleUnloader {
    /** Sophisticated Core's backpack/storage slot class (checked by name so it is an optional dependency). */
    private static final String SOPHISTICATED_STORAGE_SLOT = "net.p3pp3rf1y.sophisticatedcore.common.gui.StorageInventorySlot";

    /** Player Inventory container indices: 0-8 hotbar, 9-35 main. 36+ are armor/offhand and are never targets. */
    private static final int PLAYER_HOTBAR_SIZE = 9;
    private static final int PLAYER_STORAGE_SIZE = 36;

    private static final int LEFT = 0;
    private static final int RIGHT = 1;

    private BundleUnloader() {
    }

    static void onMouseClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT || !Screen.hasShiftDown()) {
            return;
        }
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)
                || screen instanceof CreativeModeInventoryScreen) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) {
            return;
        }

        AbstractContainerMenu menu = screen.getMenu();
        Slot bundleSlot = screen.getSlotUnderMouse();
        if (bundleSlot == null || !menu.getCarried().isEmpty() || !isNonEmptyBundle(bundleSlot.getItem())) {
            return;
        }

        // From here on we own the click: shift-right-clicking a bundle no longer quick-moves the bundle itself.
        event.setCanceled(true);
        unload(mc, player, menu, bundleSlot);
    }

    private static void unload(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, Slot bundleSlot) {
        List<Slot> candidates = targetSlots(menu, bundleSlot);
        int containerId = menu.containerId;

        // A bundle holds at most 64 stacks; the cap is just a guard against an unexpected loop.
        for (int guard = 0; guard < 64; guard++) {
            if (!isNonEmptyBundle(bundleSlot.getItem())) {
                break;
            }

            // Right-click the bundle: vanilla moves its most recently added stack onto the cursor.
            mc.gameMode.handleInventoryMouseClick(containerId, bundleSlot.index, RIGHT, ClickType.PICKUP, player);
            ItemStack carried = menu.getCarried();
            if (carried.isEmpty()) {
                break; // the server-side menu refused to release anything
            }

            if (!placeCarried(mc, player, menu, candidates)) {
                // Not all of it fit: put the rest back on top of the bundle and stop.
                // (The bundle always hands out its top stack, so trying further would loop.)
                mc.gameMode.handleInventoryMouseClick(containerId, bundleSlot.index, LEFT, ClickType.PICKUP, player);
                break;
            }
        }
    }

    /**
     * Left-clicks the carried stack into matching partial stacks first, then into empty slots,
     * until the cursor is empty. Returns false if something is still on the cursor.
     */
    private static boolean placeCarried(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, List<Slot> candidates) {
        List<Slot> remaining = new ArrayList<>(candidates);
        while (!menu.getCarried().isEmpty()) {
            ItemStack carried = menu.getCarried();
            Slot target = findMatchingStackFor(remaining, carried);
            if (target == null) {
                target = findEmptySlotFor(remaining, carried);
            }
            if (target == null) {
                return false;
            }

            ItemStack expected = carried.copy();
            // Left-click: merges into a matching stack (as much as fits) or drops into the empty slot.
            mc.gameMode.handleInventoryMouseClick(menu.containerId, target.index, LEFT, ClickType.PICKUP, player);
            // Each slot is tried once: if it had room it took everything it could.
            remaining.remove(target);

            ItemStack after = menu.getCarried();
            if (!after.isEmpty() && !ItemStack.isSameItemSameComponents(after, expected)) {
                // Safety net: the slot swapped a different item onto the cursor. Swap it back
                // so only bundle items ever go back into the bundle.
                mc.gameMode.handleInventoryMouseClick(menu.containerId, target.index, LEFT, ClickType.PICKUP, player);
                return menu.getCarried().isEmpty();
            }
        }
        return true;
    }

    /** A slot already holding the same item (and components) with room left in its stack. */
    private static Slot findMatchingStackFor(List<Slot> candidates, ItemStack stack) {
        if (!stack.isStackable()) {
            return null;
        }
        for (Slot slot : candidates) {
            ItemStack existing = slot.getItem();
            if (!existing.isEmpty() && slot.isActive() && ItemStack.isSameItemSameComponents(existing, stack)
                    && slot.mayPlace(stack) && existing.getCount() < slot.getMaxStackSize(existing)) {
                return slot;
            }
        }
        return null;
    }

    private static boolean isNonEmptyBundle(ItemStack stack) {
        BundleContents contents = stack.get(DataComponents.BUNDLE_CONTENTS);
        return contents != null && !contents.isEmpty();
    }

    /**
     * The slots the bundle may unload into, in fill order. Items never leave the inventory
     * the bundle is in:
     *  - bundle in a Sophisticated backpack/storage -> only that backpack's storage slots
     *  - bundle in the player inventory           -> only the player's main inventory, then hotbar
     *  - bundle in any other container (chest...) -> only that container's slots
     */
    private static List<Slot> targetSlots(AbstractContainerMenu menu, Slot bundleSlot) {
        List<Slot> result = new ArrayList<>();

        if (isSophisticatedStorageSlot(bundleSlot)) {
            Method inaccessibleCheck = findInaccessibleSlotCheck(menu);
            for (Slot slot : menu.slots) {
                if (slot != bundleSlot && isSophisticatedStorageSlot(slot) && !isInaccessible(menu, inaccessibleCheck, slot)) {
                    result.add(slot);
                }
            }
            return result;
        }

        if (bundleSlot.container instanceof Inventory) {
            List<Slot> hotbar = new ArrayList<>();
            for (Slot slot : menu.slots) {
                if (slot == bundleSlot || !(slot.container instanceof Inventory)) {
                    continue;
                }
                int index = slot.getContainerSlot();
                if (index < PLAYER_HOTBAR_SIZE) {
                    hotbar.add(slot);
                } else if (index < PLAYER_STORAGE_SIZE) {
                    result.add(slot);
                }
            }
            result.addAll(hotbar);
            return result;
        }

        for (Slot slot : menu.slots) {
            if (slot != bundleSlot && isSameInventory(slot, bundleSlot)) {
                result.add(slot);
            }
        }
        return result;
    }

    private static Slot findEmptySlotFor(List<Slot> candidates, ItemStack stack) {
        for (Slot slot : candidates) {
            if (!slot.hasItem() && slot.isActive() && slot.mayPlace(stack) && slot.getMaxStackSize(stack) > 0) {
                return slot;
            }
        }
        return null;
    }

    private static boolean isSameInventory(Slot a, Slot b) {
        if (a instanceof SlotItemHandler ha && b instanceof SlotItemHandler hb) {
            return ha.getItemHandler() == hb.getItemHandler();
        }
        return a.container == b.container;
    }

    private static boolean isSophisticatedStorageSlot(Slot slot) {
        for (Class<?> c = slot.getClass(); c != null && c != Slot.class; c = c.getSuperclass()) {
            if (c.getName().equals(SOPHISTICATED_STORAGE_SLOT)) {
                return true;
            }
        }
        return false;
    }

    /** Sophisticated menus expose isInaccessibleSlot(int) for slots locked after a downgrade. */
    private static Method findInaccessibleSlotCheck(AbstractContainerMenu menu) {
        try {
            return menu.getClass().getMethod("isInaccessibleSlot", int.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static boolean isInaccessible(AbstractContainerMenu menu, Method check, Slot slot) {
        if (check == null) {
            return false;
        }
        try {
            return (boolean) check.invoke(menu, slot.index);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
