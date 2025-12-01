package nz.tai.starmade.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import nz.tai.starmade.config.ProxyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LoginSnifferHandler} using Netty's {@link EmbeddedChannel}.
 */
class LoginSnifferHandlerTest {
  private EmbeddedChannel channel;
  private ProxyConfig config;

  @BeforeEach
  void setUp() {
    config = ProxyConfig.defaultConfig();
    channel = new EmbeddedChannel(new LoginSnifferHandler(config));
  }

  @AfterEach
  void tearDown() {
    channel.finishAndReleaseAll();
  }

  @Test
  void shouldPassThroughNonLoginPackets() {
    ByteBuf input = createNonLoginPacket();

    channel.writeInbound(input);

    ByteBuf output = channel.readInbound();
    assertThat(output).isNotNull();
    assertThat(output.readableBytes()).isEqualTo(input.readableBytes());
    output.release();
  }

  @Test
  void shouldSniffValidLoginPacket() {
    String username = "TestPlayer";
    ByteBuf input = createLoginPacket(username);

    channel.writeInbound(input);

    ByteBuf output = channel.readInbound();
    assertThat(output).isNotNull();
    assertThat(output.readerIndex()).isEqualTo(0); // Buffer not modified
    output.release();
  }

  @Test
  void shouldHandleMalformedPacketGracefully() {
    ByteBuf input = Unpooled.buffer();
    input.writeInt(10); // Length
    input.writeByte(config.signature()); // Valid signature
    // Missing rest of header

    channel.writeInbound(input);

    ByteBuf output = channel.readInbound();
    assertThat(output).isNotNull(); // Packet still forwarded
    output.release();
  }

  @Test
  void shouldHandleEmptyPacket() {
    ByteBuf input = Unpooled.buffer();

    channel.writeInbound(input);

    ByteBuf output = channel.readInbound();
    assertThat(output).isNotNull();
    output.release();
  }

  private ByteBuf createLoginPacket(String username) {
    ByteBuf buf = Unpooled.buffer();

    // Calculate total length
    int headerSize = 5; // signature(1) + packetId(2) + commandId(1) + timestamp(1)
    int paramCountSize = 4;
    int param0TypeSize = 1;
    int param0LengthSize = 2;
    byte[] usernameBytes = username.getBytes(StandardCharsets.UTF_8);
    int totalPayloadSize = headerSize + paramCountSize + param0TypeSize
        + param0LengthSize + usernameBytes.length;

    // Write frame
    buf.writeInt(totalPayloadSize); // Length field
    buf.writeByte(config.signature()); // Signature
    buf.writeShort(1); // Packet ID
    buf.writeByte(config.loginCommandId()); // Command ID (login)
    buf.writeByte(0); // Timestamp

    // Write parameters
    buf.writeInt(1); // Param count
    buf.writeByte(4); // Type: string
    buf.writeShort(usernameBytes.length); // String length
    buf.writeBytes(usernameBytes); // String data

    return buf;
  }

  private ByteBuf createNonLoginPacket() {
    ByteBuf buf = Unpooled.buffer();
    buf.writeInt(5); // Length
    buf.writeByte(config.signature()); // Signature
    buf.writeShort(1); // Packet ID
    buf.writeByte(1); // Command ID (not login)
    buf.writeByte(0); // Timestamp
    return buf;
  }
}
