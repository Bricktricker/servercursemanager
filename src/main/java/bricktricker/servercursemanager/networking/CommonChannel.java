package bricktricker.servercursemanager.networking;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelInboundHandlerAdapter;

public abstract class CommonChannel extends ChannelInboundHandlerAdapter {
    
    protected static final byte[] HEADER = { 'S', 'C', 'M', '1' };
    
    private static final Logger LOGGER = LogManager.getLogger();

    protected static ByteBuf writeHeader(ByteBufAllocator alloc, int contentLength, PacketType packetType) {
        ByteBuf buf = alloc.buffer(HEADER.length + 4 + 1 + contentLength);
        buf.writeBytes(HEADER);
        buf.writeInt(contentLength + 1);
        buf.writeByte(packetType.ordinal());
        return buf;
    }
    
    protected static byte[] readBuffer(ByteBuf buf, int maxLength) {
        int length = buf.readInt();
        if (length > maxLength) {
            LOGGER.warn("tried to read buffer with size {}, but max allowed size is {}", length, maxLength);
            length = maxLength;
        }
        byte[] readBuffer = new byte[length];
        if (length > 0) {
            buf.readBytes(readBuffer);
        }
        return readBuffer;
    }
}
