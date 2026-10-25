package com.example.auction;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;

public class AuctionMod implements ModInitializer {
    private final AuctionManager manager = new AuctionManager();

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(manager::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> manager.onJoin(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(manager::cancelOnStop);

        // GUI -> server
        PayloadTypeRegistry.playC2S().register(StartAuctionPayload.ID, StartAuctionPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StartAuctionPayload.ID, (payload, context) -> {
            ServerPlayerEntity p = context.player();
            String err = manager.startFromInventory(context.server(), p,
                payload.item(), payload.count(), payload.minutes(), payload.startingBid());
            if (err != null) p.sendMessage(Text.literal("[Auction] " + err).formatted(Formatting.RED), false);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
            dispatcher.register(CommandManager.literal("auction")
                .then(CommandManager.literal("start")
                    .then(CommandManager.argument("minutes", IntegerArgumentType.integer(1, 1440))
                        .then(CommandManager.argument("startingBid", IntegerArgumentType.integer(1, 100000))
                            .executes(ctx -> {
                                ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                                ItemStack held = p.getMainHandStack();
                                if (held.isEmpty()) return reply(ctx.getSource(), "Hold the item you want to auction in your main hand.");
                                String err = manager.start(ctx.getSource().getServer(), p, held.copy(),
                                    IntegerArgumentType.getInteger(ctx, "minutes"),
                                    IntegerArgumentType.getInteger(ctx, "startingBid"));
                                if (err == null) p.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
                                return reply(ctx.getSource(), err);
                            }))))
                .then(CommandManager.literal("bid")
                    .then(CommandManager.argument("amount", IntegerArgumentType.integer(1, 100000))
                        .executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                            String err = manager.bid(ctx.getSource().getServer(), p,
                                IntegerArgumentType.getInteger(ctx, "amount"));
                            return reply(ctx.getSource(), err);
                        })))
                .then(CommandManager.literal("info").executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> manager.info(), false);
                    return 1;
                }))
                .then(CommandManager.literal("cancel").executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                    String err = manager.cancel(ctx.getSource().getServer(), p,
                        ctx.getSource().hasPermissionLevel(2));
                    return reply(ctx.getSource(), err);
                }))
            ));
    }

    private static int reply(ServerCommandSource src, String err) {
        if (err != null) {
            src.sendError(Text.literal(err));
            return 0;
        }
        return 1;
    }
}
