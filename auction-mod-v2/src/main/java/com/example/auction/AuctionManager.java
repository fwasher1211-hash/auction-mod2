package com.example.auction;

import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.*;

public class AuctionManager {
    /** The "money" used for bids. Change this to any item you like. */
    public static final Item CURRENCY = Items.DIAMOND;
    private static final String CUR_NAME = "diamonds";

    private static class Auction {
        UUID seller; String sellerName;
        ItemStack item;
        long startMs, endMs;
        int startingBid;
        int highBid;
        UUID bidder; String bidderName;
    }

    private Auction current;
    private int tickCounter;
    private final ServerBossBar bar = new ServerBossBar(Text.literal("Auction"), BossBar.Color.YELLOW, BossBar.Style.PROGRESS);
    private final Map<UUID, List<ItemStack>> pending = new HashMap<>();

    // ---------- starting ----------

    /** Used by the GUI: takes `count` of `itemId` out of the player's inventory and starts the auction. */
    public String startFromInventory(MinecraftServer server, ServerPlayerEntity p, Identifier itemId,
                                     int count, int minutes, int startingBid) {
        Item item = Registries.ITEM.getOrEmpty(itemId).orElse(Items.AIR);
        if (item == Items.AIR) return "Unknown item.";
        if (count < 1 || count > item.getMaxCount()) return "Invalid amount.";
        if (minutes < 1 || minutes > 1440) return "Duration must be 1-1440 minutes.";
        if (startingBid < 1 || startingBid > 100000) return "Invalid minimum bid.";
        if (current != null) return "An auction is already running. Wait for it to finish.";
        if (countItem(p, item) < count)
            return "You don't have " + count + "x " + new ItemStack(item).getName().getString() + " in your inventory.";

        takeItem(p, item, count);
        String err = start(server, p, new ItemStack(item, count), minutes, startingBid);
        if (err != null) giveItem(server, p.getUuid(), new ItemStack(item, count));
        return err;
    }

    /** Starts an auction for an item that was already taken from the player. */
    public String start(MinecraftServer server, ServerPlayerEntity p, ItemStack item, int minutes, int startingBid) {
        if (current != null) return "An auction is already running. Wait for it to finish.";
        Auction a = new Auction();
        a.seller = p.getUuid();
        a.sellerName = p.getName().getString();
        a.item = item;
        a.startingBid = startingBid;
        a.startMs = System.currentTimeMillis();
        a.endMs = a.startMs + minutes * 60_000L;
        current = a;

        bar.clearPlayers();
        for (ServerPlayerEntity pl : server.getPlayerManager().getPlayerList()) bar.addPlayer(pl);
        bar.setVisible(true);
        updateBar();

        broadcast(server, Text.literal("[Auction] ").formatted(Formatting.GOLD)
            .append(Text.literal(a.sellerName + " is auctioning ").formatted(Formatting.YELLOW))
            .append(stackText(a.item))
            .append(Text.literal(" for " + minutes + " min. Starting bid: " + startingBid + " " + CUR_NAME
                + ". Use /auction bid <amount>").formatted(Formatting.YELLOW)));
        return null;
    }

    // ---------- commands ----------

    public String bid(MinecraftServer server, ServerPlayerEntity p, int amount) {
        if (current == null) return "There is no active auction.";
        Auction a = current;
        if (p.getUuid().equals(a.seller)) return "You can't bid on your own auction.";
        if (p.getUuid().equals(a.bidder)) return "You're already the highest bidder.";
        int min = a.bidder == null ? a.startingBid : a.highBid + 1;
        if (amount < min) return "Your bid must be at least " + min + " " + CUR_NAME + ".";
        if (countCurrency(p) < amount) return "You need " + amount + " " + CUR_NAME + " in your inventory (held in escrow).";

        takeCurrency(p, amount);
        if (a.bidder != null) {
            giveCurrency(server, a.bidder, a.highBid);
            notifyUuid(server, a.bidder, Text.literal("[Auction] You were outbid by " + p.getName().getString()
                + ". Your " + a.highBid + " " + CUR_NAME + " were refunded.").formatted(Formatting.RED));
        }
        a.highBid = amount;
        a.bidder = p.getUuid();
        a.bidderName = p.getName().getString();
        updateBar();

        broadcast(server, Text.literal("[Auction] ").formatted(Formatting.GOLD)
            .append(Text.literal(a.bidderName + " is the highest bidder with " + amount + " " + CUR_NAME + "!")
                .formatted(Formatting.GREEN)));
        return null;
    }

    public Text info() {
        if (current == null) return Text.literal("No active auction.").formatted(Formatting.GRAY);
        Auction a = current;
        long secs = Math.max(0, (a.endMs - System.currentTimeMillis()) / 1000);
        Text bidLine = a.bidder == null
            ? Text.literal("No bids yet (starting bid " + a.startingBid + " " + CUR_NAME + ")")
            : Text.literal("Highest bid: " + a.highBid + " " + CUR_NAME + " by " + a.bidderName);
        return Text.literal("[Auction] ").formatted(Formatting.GOLD)
            .append(stackText(a.item))
            .append(Text.literal(" sold by " + a.sellerName + " | ").formatted(Formatting.YELLOW))
            .append(bidLine.copy().formatted(Formatting.GREEN))
            .append(Text.literal(" | " + fmt(secs) + " left").formatted(Formatting.YELLOW));
    }

    public String cancel(MinecraftServer server, ServerPlayerEntity p, boolean isOp) {
        if (current == null) return "There is no active auction.";
        if (!isOp && !p.getUuid().equals(current.seller)) return "Only the seller or an operator can cancel.";
        Auction a = current;
        current = null;
        bar.setVisible(false);
        bar.clearPlayers();
        giveItem(server, a.seller, a.item);
        if (a.bidder != null) giveCurrency(server, a.bidder, a.highBid);
        broadcast(server, Text.literal("[Auction] The auction was cancelled. Item and bids returned.").formatted(Formatting.RED));
        return null;
    }

    // ---------- events ----------

    public void tick(MinecraftServer server) {
        if (current == null) return;
        if (System.currentTimeMillis() >= current.endMs) { finish(server); return; }
        if (++tickCounter % 10 == 0) updateBar();
    }

    public void onJoin(ServerPlayerEntity p) {
        if (current != null) bar.addPlayer(p);
        List<ItemStack> list = pending.remove(p.getUuid());
        if (list != null) {
            for (ItemStack s : list) p.getInventory().offerOrDrop(s);
            p.sendMessage(Text.literal("[Auction] You received items/diamonds from an auction while you were away.")
                .formatted(Formatting.GOLD), false);
        }
    }

    public void cancelOnStop(MinecraftServer server) {
        if (current == null) return;
        Auction a = current;
        current = null;
        giveItem(server, a.seller, a.item);
        if (a.bidder != null) giveCurrency(server, a.bidder, a.highBid);
    }

    // ---------- internals ----------

    private void finish(MinecraftServer server) {
        Auction a = current;
        current = null;
        bar.setVisible(false);
        bar.clearPlayers();
        if (a.bidder == null) {
            giveItem(server, a.seller, a.item);
            broadcast(server, Text.literal("[Auction] Ended with no bids. Item returned to " + a.sellerName + ".")
                .formatted(Formatting.GOLD));
        } else {
            giveItem(server, a.bidder, a.item);
            giveCurrency(server, a.seller, a.highBid);
            broadcast(server, Text.literal("[Auction] ").formatted(Formatting.GOLD)
                .append(Text.literal(a.bidderName + " won ").formatted(Formatting.GREEN))
                .append(stackText(a.item))
                .append(Text.literal(" for " + a.highBid + " " + CUR_NAME + "!").formatted(Formatting.GREEN)));
        }
    }

    private void updateBar() {
        Auction a = current;
        if (a == null) return;
        long left = Math.max(0, a.endMs - System.currentTimeMillis());
        float pct = Math.min(1f, Math.max(0f, (float) left / (a.endMs - a.startMs)));
        String bid = a.bidder == null
            ? "No bids (start " + a.startingBid + ")"
            : "Highest: " + a.highBid + " " + CUR_NAME + " by " + a.bidderName;
        bar.setName(Text.literal("Auction: " + a.item.getCount() + "x ").append(a.item.getName())
            .append(Text.literal(" | " + bid + " | " + fmt(left / 1000))));
        bar.setPercent(pct);
    }

    private static Text stackText(ItemStack s) {
        return Text.literal(s.getCount() + "x ").append(s.toHoverableText()).copy().formatted(Formatting.AQUA);
    }

    private static String fmt(long secs) {
        return String.format("%d:%02d", secs / 60, secs % 60);
    }

    private static void broadcast(MinecraftServer server, Text t) {
        server.getPlayerManager().broadcast(t, false);
    }

    private static void notifyUuid(MinecraftServer server, UUID id, Text t) {
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
        if (p != null) p.sendMessage(t, false);
    }

    // ----- plain items (what the GUI auctions) -----

    private static boolean isPlain(ItemStack s, Item item) {
        return s.isOf(item) && s.getComponentChanges().isEmpty();
    }

    public static int countItem(PlayerEntity p, Item item) {
        PlayerInventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (isPlain(s, item)) n += s.getCount();
        }
        return n;
    }

    private static void takeItem(ServerPlayerEntity p, Item item, int amount) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size() && amount > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (isPlain(s, item)) {
                int t = Math.min(amount, s.getCount());
                s.decrement(t);
                amount -= t;
            }
        }
    }

    // ----- currency -----

    private static int countCurrency(ServerPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(CURRENCY)) n += s.getCount();
        }
        return n;
    }

    private static void takeCurrency(ServerPlayerEntity p, int amount) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size() && amount > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(CURRENCY)) {
                int t = Math.min(amount, s.getCount());
                s.decrement(t);
                amount -= t;
            }
        }
    }

    private void giveItem(MinecraftServer server, UUID id, ItemStack stack) {
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
        if (p != null) p.getInventory().offerOrDrop(stack.copy());
        else pending.computeIfAbsent(id, k -> new ArrayList<>()).add(stack.copy());
    }

    private void giveCurrency(MinecraftServer server, UUID id, int amount) {
        while (amount > 0) {
            int n = Math.min(amount, CURRENCY.getMaxCount());
            giveItem(server, id, new ItemStack(CURRENCY, n));
            amount -= n;
        }
    }
}
