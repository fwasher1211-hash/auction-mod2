package com.example.auction;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Sent from the client GUI to the server to start an auction. */
public record StartAuctionPayload(Identifier item, int count, int startingBid, int minutes) implements CustomPayload {
    public static final CustomPayload.Id<StartAuctionPayload> ID =
        new CustomPayload.Id<>(Identifier.of("auctionmod", "start"));

    public static final PacketCodec<RegistryByteBuf, StartAuctionPayload> CODEC = PacketCodec.tuple(
        Identifier.PACKET_CODEC, StartAuctionPayload::item,
        PacketCodecs.VAR_INT, StartAuctionPayload::count,
        PacketCodecs.VAR_INT, StartAuctionPayload::startingBid,
        PacketCodecs.VAR_INT, StartAuctionPayload::minutes,
        StartAuctionPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
