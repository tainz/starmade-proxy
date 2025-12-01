package nz.tai.starmade.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import nz.tai.starmade.config.ProxyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Handler that sniffs TCP packets for StarMade login commands and logs usernames.
 *
 * <p>Frame structure:
 * <pre>
 * [4 bytes: length][1 byte: signature][2 bytes: packetId][1 byte: commandId][1 byte: timestamp]
 * [4 bytes: paramCount][1 byte: param0Type][2 bytes: param0Length][N bytes: param0Data]...
 * </pre>
 *
 * <p>This handler operates in read-only mode: it peeks into the buffer without modifying the
 * reader index, ensuring the original frame is forwarded intact to the backend.
 *
 * <p>Thread safety: Marked as {@code @Sharable} since it has no mutable state and can be reused
 * across multiple channels.
 */
@ChannelHandler.Sharable
public final class LoginSnifferHandler extends ChannelInboundHandlerAdapter {
  private static final Logger logger = LoggerFactory.getLogger(LoginSnifferHandler.class);

  // Frame layout constants
  private static final int LENGTH_FIELD_SIZE = 4;
  private static final int HEADER_SIZE = 5;
  private static final int MIN_FRAME_SIZE = LENGTH_FIELD_SIZE + HEADER_SIZE;
  private static final int SIGNATURE_OFFSET = LENGTH_FIELD_SIZE;
  private static final int COMMAND_ID_OFFSET = LENGTH_FIELD_SIZE + 3;
  private static final int PARAM_COUNT_SIZE = 4;
  private static final int TYPE_FIELD_SIZE = 1;
  private static final int STRING_LENGTH_SIZE = 2;
  private static final int STRING_TYPE = 4;

  private final ProxyConfig config;

  public LoginSnifferHandler(ProxyConfig config) {
    this.config = config;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (msg instanceof ByteBuf buf) {
      trySniffLogin(buf);
    }
    ctx.fireChannelRead(msg);
  }

  /**
   * Attempts to extract username from a login packet.
   *
   * <p>Uses guard clauses and early returns to avoid deep nesting. All buffer access uses
   * {@code getXXX} methods that don't modify the reader index.
   */
  private void trySniffLogin(ByteBuf buf) {
    try {
      if (!hasMinimumFrameSize(buf)) {
        return;
      }

      int baseIdx = buf.readerIndex();
      if (!isLoginCommand(buf, baseIdx)) {
        return;
      }

      int payloadStart = baseIdx + MIN_FRAME_SIZE;
      String username = extractUsername(buf, baseIdx, payloadStart);
      if (username != null) {
        logger.info("[Login] User connected: {}", username);
      }
    } catch (Exception e) {
      logger.debug("[Login Sniffer] Failed to parse packet: {}", e.getMessage());
    }
  }

  private boolean hasMinimumFrameSize(ByteBuf buf) {
    return buf.readableBytes() >= MIN_FRAME_SIZE;
  }

  private boolean isLoginCommand(ByteBuf buf, int baseIdx) {
    short signature = buf.getUnsignedByte(baseIdx + SIGNATURE_OFFSET);
    if (signature != (config.signature() & 0xFF)) {
      return false;
    }

    short commandId = buf.getUnsignedByte(baseIdx + COMMAND_ID_OFFSET);
    return commandId == (config.loginCommandId() & 0xFF);
  }

  private String extractUsername(ByteBuf buf, int baseIdx, int payloadStart) {
    int minPayloadSize = payloadStart - baseIdx + PARAM_COUNT_SIZE;
    if (buf.readableBytes() < minPayloadSize) {
      return null;
    }

    int paramCount = buf.getInt(payloadStart);
    if (paramCount < 1) {
      return null;
    }

    int param0TypeIdx = payloadStart + PARAM_COUNT_SIZE;
    if (!hasStringParameter(buf, baseIdx, param0TypeIdx)) {
      return null;
    }

    return readStringParameter(buf, param0TypeIdx);
  }

  private boolean hasStringParameter(ByteBuf buf, int baseIdx, int param0TypeIdx) {
    int minSize = param0TypeIdx - baseIdx + TYPE_FIELD_SIZE + STRING_LENGTH_SIZE;
    if (buf.readableBytes() < minSize) {
      return false;
    }

    int param0Type = buf.getUnsignedByte(param0TypeIdx);
    return param0Type == STRING_TYPE;
  }

  private String readStringParameter(ByteBuf buf, int param0TypeIdx) {
    int lengthIdx = param0TypeIdx + TYPE_FIELD_SIZE;
    int strLen = buf.getUnsignedShort(lengthIdx);

    int dataStartIdx = lengthIdx + STRING_LENGTH_SIZE;
    int totalNeeded = dataStartIdx - buf.readerIndex() + strLen;
    if (buf.readableBytes() < totalNeeded) {
      return null;
    }

    var nameBytes = new byte[strLen];
    buf.getBytes(dataStartIdx, nameBytes);
    return new String(nameBytes, StandardCharsets.UTF_8);
  }
}
