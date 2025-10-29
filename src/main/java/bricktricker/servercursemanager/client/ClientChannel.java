package bricktricker.servercursemanager.client;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bricktricker.servercursemanager.networking.CommonChannel;
import bricktricker.servercursemanager.networking.PacketType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

public class ClientChannel extends CommonChannel {

    private static final Logger LOGGER = LogManager.getLogger();

    private final byte[] currentModpackHash;
    private final Path modpackPath;

    private boolean downloadSuccessful = false;

    public ClientChannel(byte[] currentModpackHash, Path modpackPath) {
        this.currentModpackHash = currentModpackHash;
        this.modpackPath = modpackPath;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        int payloadLen = 4 + this.currentModpackHash.length;
        ByteBuf buf = writeHeader(ctx.alloc(), payloadLen, PacketType.MODPACK_REQUEST);
        buf.writeInt(this.currentModpackHash.length);
        buf.writeBytes(this.currentModpackHash);
        ctx.writeAndFlush(buf);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf packet = (ByteBuf) msg;
        byte packetTypeIdx = packet.readByte();
        PacketType packetType = PacketType.values()[packetTypeIdx];

        try {
            if(packetType == PacketType.MODPACK_RESPONSE) {
                handleModpack(ctx, packet);
            }else if (packetType == PacketType.ERROR) {
                handleServerError(ctx, packet);
            } else {
                LOGGER.warn("Received unkown packet with type {}", packetType.toString());
                ctx.close();
                throw new UncheckedIOException(
                        new IOException("Received unkown encrypted packet with type " + packetType.toString()));
            }
        } finally {
            packet.release();
        }
    }

    private void handleModpack(ChannelHandlerContext ctx, ByteBuf response) {
        LOGGER.debug("Received the modpack");

        byte status = response.readByte();
        if (status == 0) {
            int packLength = response.readInt();
            byte[] modpack = new byte[packLength];
            response.readBytes(modpack);
            try (OutputStream os = Files.newOutputStream(this.modpackPath)) {
                os.write(modpack);
            } catch (IOException e) {
                LOGGER.catching(e);
            }
        }
        this.downloadSuccessful = true;
        ctx.close();
    }

    private void handleServerError(ChannelHandlerContext ctx, ByteBuf packet) {
        byte[] errorBytes = readBuffer(packet, 2048);
        String error = new String(errorBytes, StandardCharsets.UTF_8);
        LOGGER.error("Received error {}", error);
        ctx.close();
        this.downloadSuccessful = false;
        throw new UncheckedIOException(new IOException(error));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Close the connection when an exception is raised.
        LOGGER.catching(cause);
        ctx.close();
    }

    public boolean wasSuccessful() {
        return this.downloadSuccessful;
    }

}
