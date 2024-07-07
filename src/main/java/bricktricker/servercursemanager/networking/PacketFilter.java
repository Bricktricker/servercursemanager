package bricktricker.servercursemanager.networking;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;

enum State {
	READ_NOTHING, READ_HEADER
}

public class PacketFilter extends ReplayingDecoder<State> {

	private static final Logger LOGGER = LogManager.getLogger();

	private static final byte[] HEADER = { 'S', 'C', 'M', '1' };

	private final int maxPacketLength;

	public PacketFilter(int maxPacketLength) {
		super(State.READ_NOTHING);
		this.maxPacketLength = maxPacketLength;
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		State state = state();
		if(state == State.READ_NOTHING) {
			for(int i = 0; i < HEADER.length; i++) {
				if(HEADER[i] != in.readByte()) {
					LOGGER.warn("Received packet with invalid header");
					ctx.close(null);
					return;
				}
			}
			checkpoint(State.READ_HEADER);
		}

		int packetLength = in.readInt();
		if(packetLength <= 0) {
			checkpoint(State.READ_NOTHING);
			LOGGER.warn("Received packet with a negative length {}", packetLength);
			return;
		}
		if(packetLength > this.maxPacketLength) {
			checkpoint(State.READ_NOTHING);
			LOGGER.warn("Received packet that is to big, packet size: {} bytes", packetLength);
			return;
		}
		ByteBuf packet = in.readBytes(packetLength);
		out.add(packet);
		checkpoint(State.READ_NOTHING);
	}

}
