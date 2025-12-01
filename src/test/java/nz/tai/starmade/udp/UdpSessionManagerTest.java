package nz.tai.starmade.udp;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UdpSessionManager}.
 */
class UdpSessionManagerTest {
  private UdpSessionManager manager;
  private InetSocketAddress clientAddr;
  private EmbeddedChannel backendChannel;

  @BeforeEach
  void setUp() {
    manager = new UdpSessionManager();
    clientAddr = new InetSocketAddress("192.168.1.100", 12345);
    backendChannel = new EmbeddedChannel();
  }

  @Test
  void shouldRegisterAndLookupSession() {
    manager.register(clientAddr, backendChannel);

    assertThat(manager.getBackendChannel(clientAddr)).isEqualTo(backendChannel);
    assertThat(manager.getClientAddress(backendChannel.id())).isEqualTo(clientAddr);
  }

  @Test
  void shouldRemoveSession() {
    manager.register(clientAddr, backendChannel);
    manager.removeSession(backendChannel.id());

    assertThat(manager.getBackendChannel(clientAddr)).isNull();
    assertThat(manager.getClientAddress(backendChannel.id())).isNull();
  }

  @Test
  void shouldReturnNullForUnknownClient() {
    var unknownAddr = new InetSocketAddress("10.0.0.1", 99999);
    assertThat(manager.getBackendChannel(unknownAddr)).isNull();
  }

  @Test
  void shouldClearAllSessions() {
    manager.register(clientAddr, backendChannel);
    var anotherAddr = new InetSocketAddress("192.168.1.101", 54321);
    var anotherChannel = new EmbeddedChannel();
    manager.register(anotherAddr, anotherChannel);

    manager.clear();

    assertThat(manager.getBackendChannel(clientAddr)).isNull();
    assertThat(manager.getBackendChannel(anotherAddr)).isNull();
  }

  @Test
  void shouldHandleInactiveChannelLookup() {
    manager.register(clientAddr, backendChannel);
    backendChannel.close();

    // Should return null and clean up stale mapping
    assertThat(manager.getBackendChannel(clientAddr)).isNull();
  }
}
