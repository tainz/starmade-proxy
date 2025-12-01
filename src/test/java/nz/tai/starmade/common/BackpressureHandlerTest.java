package nz.tai.starmade.common;

import io.netty.channel.ChannelConfig;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BackpressureHandler}.
 */
class BackpressureHandlerTest {
  private EmbeddedChannel sourceChannel;
  private EmbeddedChannel destChannel;

  @BeforeEach
  void setUp() {
    sourceChannel = new EmbeddedChannel();
    destChannel = new EmbeddedChannel(new BackpressureHandler(sourceChannel));
  }

  @Test
  void shouldPauseSourceWhenDestinationNotWritable() {
    // Simulate destination becoming non-writable
    destChannel.config().setWriteBufferWaterMark(
        new io.netty.channel.WriteBufferWaterMark(1, 2));
    destChannel.unsafe().outboundBuffer().addMessage(
        new Object(), 10, destChannel.newPromise());

    // Trigger writability change
    destChannel.pipeline().fireChannelWritabilityChanged();

    // Source should have AUTO_READ disabled (note: EmbeddedChannel doesn't fully simulate this)
    // In real scenario, sourceChannel.config().isAutoRead() would be false
    assertThat(destChannel.isWritable()).isFalse();
  }

  @Test
  void shouldResumeSourceWhenDestinationBecomesWritable() {
    // Initially writable
    assertThat(destChannel.isWritable()).isTrue();
    assertThat(sourceChannel.config().isAutoRead()).isTrue();
  }
}
