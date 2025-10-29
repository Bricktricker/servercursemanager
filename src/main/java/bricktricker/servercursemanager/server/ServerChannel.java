package bricktricker.servercursemanager.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bricktricker.servercursemanager.Utils;
import bricktricker.servercursemanager.networking.CommonChannel;
import bricktricker.servercursemanager.networking.PacketType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

public class ServerChannel extends CommonChannel {

    private static final Logger LOGGER = LogManager.getLogger();

    private final byte[] modpackData;
    private final byte[] modpackHash;

    public ServerChannel(byte[] modpackData) {
        this.modpackData = modpackData;
        this.modpackHash = Utils.computeSha1(new ByteArrayInputStream(modpackData));
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf packet = (ByteBuf) msg;

        byte packetTypeIdx = packet.readByte();
        PacketType packetType = PacketType.values()[packetTypeIdx];

        try {
            if(packetType == PacketType.MODPACK_REQUEST) {
                handleClientRequest(ctx, packet);
            } else {
                LOGGER.warn("Received unkown packet with type {}", packetType.toString());
                ctx.close();
                throw new UncheckedIOException(new IOException("Received unkown packet with type " + packetType.toString()));
            }
        } finally {
            packet.release();
        }
    }
    
    private void handleClientRequest(ChannelHandlerContext ctx, ByteBuf packHashBuf) {
        LOGGER.debug("handle client modpack request");
        
        byte[] currentPackHash = readBuffer(packHashBuf, 32);
        LOGGER.debug("Client send hash: {}, server modpack hash: {}", ByteBufUtil.hexDump(currentPackHash), ByteBufUtil.hexDump(this.modpackHash));
        boolean hashesEqual = Arrays.equals(currentPackHash, this.modpackHash);
        
        // Send modpack back
        int responseLength = 1/* status */ + (hashesEqual ? 0 : this.modpackData.length + 4);
        ByteBuf response = ctx.alloc().buffer(responseLength);
        response.writeByte(hashesEqual ? 1 : 0);
        if(!hashesEqual) {
            response.writeInt(this.modpackData.length);
            response.writeBytes(this.modpackData);
        }
        
        ByteBuf buf = writeHeader(ctx.alloc(), responseLength, PacketType.MODPACK_RESPONSE);
        buf.writeBytes(response);
        ctx.writeAndFlush(buf).addListener(ChannelFutureListener.CLOSE);
        LOGGER.debug("Send modpack to client");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Close the connection when an exception is raised.
        LOGGER.catching(cause);
        ctx.close();
    }

}
