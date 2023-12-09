package bricktricker.servercursemanager.networking;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
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
					return;
				}
			}
			checkpoint(State.READ_HEADER);
		}

		int packetLength = in.readInt();
		if(packetLength <= 0) {
			checkpoint(State.READ_NOTHING);
			LOGGER.warn("Received packet with a negative length {}", packetLength);
			LOGGER.info(ByteBufUtil.hexDump(in, 0, 15)); // print the first 15 bytes
			return;
		}
		if(packetLength > this.maxPacketLength) {
			checkpoint(State.READ_NOTHING);
			LOGGER.warn("Received packet that is to big, packet size: {} bytes", packetLength);
			LOGGER.info(ByteBufUtil.hexDump(in, 0, Math.min(100, this.maxPacketLength)));
			return;
		}
		ByteBuf packet = in.readBytes(packetLength);
		String dump = ByteBufUtil.hexDump(packet);
		if(dump.length() > 100) dump = dump.substring(0, 100) + "...";
		LOGGER.debug("Received packet with length {} and content {}", packetLength, dump);
		out.add(packet);
		checkpoint(State.READ_NOTHING);
	}

}
