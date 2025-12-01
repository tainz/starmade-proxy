package nz.tai.starmade.udp;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe manager for UDP client-to-backend channel mappings.
 *
 * <p>Maintains bidirectional mappings:
 * <ul>
 *   <li>Client address → Backend channel (for forwarding client packets)</li>
 *   <li>Backend channel ID → Client address (for routing backend responses)</li>
 * </ul>
 *
 * <p>Thread safety: All public methods use atomic operations on {@link ConcurrentHashMap}
 * to ensure safe concurrent access from multiple event loop threads.
 */
public final class UdpSessionManager {
  private static final Logger logger = LoggerFactory.getLogger(UdpSessionManager.class);

  private final Map<InetSocketAddress, Channel> clientToBackend = new ConcurrentHashMap<>();
  private final Map<ChannelId, InetSocketAddress> backendToClient = new ConcurrentHashMap<>();

  /**
   * Registers a new client-backend mapping.
   *
   * @param clientAddr client's socket address
   * @param backendChannel channel connected to backend server
   */
  public void register(InetSocketAddress clientAddr, Channel backendChannel) {
    clientToBackend.put(clientAddr, backendChannel);
    backendToClient.put(backendChannel.id(), clientAddr);
    logger.debug("[UDP] Registered session for client {} -> backend channel {}",
        clientAddr, backendChannel.id());
  }

  /**
   * Looks up the backend channel for a given client address.
   *
   * @param clientAddr client's socket address
   * @return backend channel if active, null otherwise
   */
  public Channel getBackendChannel(InetSocketAddress clientAddr) {
    var channel = clientToBackend.get(clientAddr);
    if (channel != null && !channel.isActive()) {
      // Clean up stale mapping
      removeSession(channel.id());
      return null;
    }
    return channel;
  }

  /**
   * Looks up the client address for a given backend channel ID.
   *
   * @param backendChannelId backend channel ID
   * @return client address, or null if not found
   */
  public InetSocketAddress getClientAddress(ChannelId backendChannelId) {
    return backendToClient.get(backendChannelId);
  }

  /**
   * Removes a session by backend channel ID, cleaning both mappings atomically.
   *
   * @param backendChannelId backend channel ID
   */
  public void removeSession(ChannelId backendChannelId) {
    var clientAddr = backendToClient.remove(backendChannelId);
    if (clientAddr != null) {
      clientToBackend.remove(clientAddr);
      logger.debug("[UDP] Removed session for client {} (backend channel {})",
          clientAddr, backendChannelId);
    }
  }

  /**
   * Removes all sessions for testing or shutdown.
   */
  public void clear() {
    int count = clientToBackend.size();
    clientToBackend.clear();
    backendToClient.clear();
    logger.debug("[UDP] Cleared {} sessions", count);
  }
}
