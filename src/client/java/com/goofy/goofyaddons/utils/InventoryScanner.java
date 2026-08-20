package com.goofy.goofyaddons.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InventoryScanner {
    private Minecraft minecraft = Minecraft.getInstance();

    // "You have 5 items to claim!" -> 5
    private static final Pattern CLAIM_AMOUNT = Pattern.compile("You have\\s+([\\d,]+)");
    // "Unit price: 139,890.5 coins" -> 139890.5
    private static final Pattern UNIT_PRICE = Pattern.compile("Unit price:\\s*([\\d,]+(?:\\.\\d+)?)");

    public List<Integer> findInv(String name) {
        List<Integer> slots = new ArrayList<>();
        Inventory playerInv = minecraft.player.getInventory();
        AbstractContainerMenu menu = minecraft.player.containerMenu;

        for (Slot slot : menu.slots) {
            if (slot.container != playerInv) continue;
            ItemStack item = slot.getItem();
            if (item.isEmpty()) continue;
            if (item.getCustomName() == null) continue;
            if (!item.getCustomName().getString().equals(name)) continue;
            slots.add(slot.index);
        }
        return slots;
    }

    public List<Integer> getSellOrder() {
        List<Integer> slots = new ArrayList<>();
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        int end = menu.slots.size() - 36;
        for (int i = 0; i < end; i++) {
            ItemStack item = menu.slots.get(i).getItem();
            if (item.isEmpty()) continue;
            if (item.getCustomName() == null) continue;
            if (!item.getCustomName().getString().contains("SELL")) continue;
            slots.add(i);
        }
        return slots;
    }

    public String getName(int slot) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;

        ItemStack itemStack = menu.slots.get(slot).getItem();
        return itemStack.getCustomName().getString();
    }

    public List<Integer> findContainer(String name) {
        List<Integer> slots = new ArrayList<>();
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        int end = menu.slots.size() - 36;
        for (int i = 0; i < end; i++) {
            ItemStack item = menu.slots.get(i).getItem();
            if (item.isEmpty()) continue;
            if (item.getCustomName() == null) continue;
            if (!item.getCustomName().getString().equals(name)) continue;
            slots.add(i);
        }
        return slots;
    }

    public List<Integer> findLoreInv(String string) {
        List<Integer> slots = new ArrayList<>();
        Inventory playerInv = minecraft.player.getInventory();
        AbstractContainerMenu menu = minecraft.player.containerMenu;

        for (Slot slot : menu.slots) {
            if (slot.container != playerInv) continue;
            ItemStack item = slot.getItem();
            if (item.isEmpty()) continue;
            ItemLore lore = item.get(DataComponents.LORE);
            if (lore == null || !lore.lines().stream().anyMatch(l -> l.getString().equals(string))) continue;
            slots.add(slot.index);
        }
        return slots;
    }

    public List<Integer> findLoreContainer(String string) {
        List<Integer> slots = new ArrayList<>();
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        int end = menu.slots.size() - 36;
        for (int i = 0; i < end; i++) {
            ItemStack item = menu.slots.get(i).getItem();
            if (item.isEmpty()) continue;
            ItemLore lore = item.get(DataComponents.LORE);
            if (lore == null || !lore.lines().stream().anyMatch(l -> l.getString().equals(string))) continue;
            slots.add(i);
        }
        return slots;
    }

    public List<Integer> locate(String string) {
        List<Integer> slots = new ArrayList<>();
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        for (int i = 0; i < menu.slots.size(); i++) {
            ItemStack item = menu.slots.get(i).getItem();
            if (item.isEmpty()) continue;
            ItemLore lore = item.get(DataComponents.LORE);
            if (lore == null || !lore.lines().stream().anyMatch(l -> l.getString().equals(string))) continue;
            slots.add(i);
        }
        return slots;
    }

    /**
     * Siparişte claim edilmeyi bekleyen adet.
     *
     * ESKİ KOD: text.replaceAll("[^0-9]", "") satırdaki TÜM rakamları yan yana
     * yapıştırıyordu; satırda ikinci bir sayı geçtiği anda (miktar/coin/oran)
     * 5 yerine 51234 gibi bir değer dönüyor, bu değer görevin inInventory
     * sayacına eklenip görev fiziksel olarak eksikken "tamamlandı" sayılıyordu.
     * Sonuç: bot eksik havuzla birleştirmeye giriyor ve öksüz parça üretiyordu.
     * Artık "You have" ifadesinden sonraki İLK sayı alınıp döngü kırılıyor.
     */
    public int checkOrder(int slot) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        ItemStack itemStack = menu.slots.get(slot).getItem();
        ItemLore lore = itemStack.get(DataComponents.LORE);
        if (lore == null) return 0;
        for (Component line : lore.lines()) {
            String text = line.getString();
            Matcher matcher = CLAIM_AMOUNT.matcher(text);
            if (!matcher.find()) continue;
            try {
                return Integer.parseInt(matcher.group(1).replace(",", ""));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Sipariş ekranındaki birim fiyat. Eskiden satırdaki tüm rakam/nokta
     * karakterleri birleştiriliyordu; satırda ikinci bir sayı varsa fiyat
     * bozuluyor ve BazaarMonitor sürekli "outbid" sanıyordu (bu da siparişin
     * durmadan iptal edilip yeniden açılmasına, havuzun bölünmesine yol açıyor).
     */
    public double getUnitPrice(int slot) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        ItemStack itemStack = menu.slots.get(slot).getItem();
        ItemLore itemLore = itemStack.get(DataComponents.LORE);
        if (itemLore == null) return 0;
        for (Component line : itemLore.lines()) {
            String text = line.getString();
            Matcher matcher = UNIT_PRICE.matcher(text);
            if (!matcher.find()) continue;
            try {
                return Double.parseDouble(matcher.group(1).replace(",", ""));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    public int getEmptyInventorySlots() {
        int amount = 0;
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        Inventory playerInv = minecraft.player.getInventory();

        for (Slot slot : menu.slots) {
            if (slot.container != playerInv) continue;

            if (slot.hasItem()) continue;
            amount++;
        }

        return amount;
    }

    public int getEmptyContainerSlots() {
        int amount = 0;
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        Inventory playerInv = minecraft.player.getInventory();

        for (Slot slot : menu.slots) {
            if (slot.container == playerInv) continue;

            if (slot.hasItem()) continue;
            amount++;
        }

        return amount;
    }

    public boolean findMisMatch(String string) {
        List<Integer> slots = new ArrayList<>();
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        int end = menu.slots.size() - 36;
        for (int i = 0; i < end; i++) {
            ItemStack item = menu.slots.get(i).getItem();
            if (item.isEmpty()) continue;
            ItemLore lore = item.get(DataComponents.LORE);
            if (lore == null || !lore.lines().stream().anyMatch(l -> l.getString().equals(string))) continue;
            slots.add(i);
        }
        if (getEmptyContainerSlots() == 0 && slots.size() == 1) return true;
        return false;
    }


}
