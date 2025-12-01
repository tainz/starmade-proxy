package nz.tai.starmade.config;

/**
 * Immutable configuration for the StarMade TCP+UDP proxy.
 *
 * <p>Configuration values are loaded from environment variables with fallback to defaults:
 * <ul>
 *   <li>BACKEND_HOST (default: 127.0.0.1)</li>
 *   <li>BACKEND_TCP_PORT (default: 4242)</li>
 *   <li>BACKEND_UDP_PORT (default: 4242)</li>
 *   <li>FRONTEND_TCP_PORT (default: 4243)</li>
 *   <li>FRONTEND_UDP_PORT (default: 4243)</li>
 *   <li>MAX_FRAME_SIZE (default: 10485760 = 10MB)</li>
 * </ul>
 */
public record ProxyConfig(
    String backendHost,
    int backendTcpPort,
    int backendUdpPort,
    int frontendTcpPort,
    int frontendUdpPort,
    int maxFrameSize,
    byte signature,
    byte loginCommandId) {

  private static final String DEFAULT_BACKEND_HOST = "127.0.0.1";
  private static final int DEFAULT_BACKEND_TCP_PORT = 4242;
  private static final int DEFAULT_BACKEND_UDP_PORT = 4242;
  private static final int DEFAULT_FRONTEND_TCP_PORT = 4243;
  private static final int DEFAULT_FRONTEND_UDP_PORT = 4243;
  private static final int DEFAULT_MAX_FRAME_SIZE = 10 * 1024 * 1024; // 10 MB
  private static final byte SIGNATURE = 0x2A; // 42
  private static final byte LOGIN_COMMAND_ID = 0;

  /**
   * Creates configuration from environment variables with default fallbacks.
   */
  public static ProxyConfig fromEnvironment() {
    return new ProxyConfig(
        getEnv("BACKEND_HOST", DEFAULT_BACKEND_HOST),
        getEnvInt("BACKEND_TCP_PORT", DEFAULT_BACKEND_TCP_PORT),
        getEnvInt("BACKEND_UDP_PORT", DEFAULT_BACKEND_UDP_PORT),
        getEnvInt("FRONTEND_TCP_PORT", DEFAULT_FRONTEND_TCP_PORT),
        getEnvInt("FRONTEND_UDP_PORT", DEFAULT_FRONTEND_UDP_PORT),
        getEnvInt("MAX_FRAME_SIZE", DEFAULT_MAX_FRAME_SIZE),
        SIGNATURE,
        LOGIN_COMMAND_ID
    );
  }

  /**
   * Creates default configuration for localhost proxying.
   */
  public static ProxyConfig defaultConfig() {
    return new ProxyConfig(
        DEFAULT_BACKEND_HOST,
        DEFAULT_BACKEND_TCP_PORT,
        DEFAULT_BACKEND_UDP_PORT,
        DEFAULT_FRONTEND_TCP_PORT,
        DEFAULT_FRONTEND_UDP_PORT,
        DEFAULT_MAX_FRAME_SIZE,
        SIGNATURE,
        LOGIN_COMMAND_ID
    );
  }

  private static String getEnv(String name, String defaultValue) {
    String value = System.getenv(name);
    return value != null ? value : defaultValue;
  }

  private static int getEnvInt(String name, int defaultValue) {
    String value = System.getenv(name);
    if (value != null) {
      try {
        return Integer.parseInt(value);
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }
    return defaultValue;
  }
}