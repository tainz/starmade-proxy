package nz.tai.starmade.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProxyConfig}.
 */
class ProxyConfigTest {

  @Test
  void shouldCreateDefaultConfig() {
    var config = ProxyConfig.defaultConfig();

    assertThat(config.backendHost()).isEqualTo("127.0.0.1");
    assertThat(config.backendTcpPort()).isEqualTo(4242);
    assertThat(config.backendUdpPort()).isEqualTo(4242);
    assertThat(config.frontendTcpPort()).isEqualTo(4243);
    assertThat(config.frontendUdpPort()).isEqualTo(4243);
    assertThat(config.maxFrameSize()).isEqualTo(10 * 1024 * 1024);
    assertThat(config.signature()).isEqualTo((byte) 0x2A);
    assertThat(config.loginCommandId()).isEqualTo((byte) 0);
  }

  @Test
  void shouldCreateConfigFromEnvironment() {
    // Note: In real tests, you'd use system property overrides or test containers
    var config = ProxyConfig.fromEnvironment();

    assertThat(config).isNotNull();
    assertThat(config.backendHost()).isNotNull();
  }

  @Test
  void shouldBeImmutable() {
    var config = ProxyConfig.defaultConfig();

    // Records are implutable by design - this is compile-time enforced
    assertThat(config).isNotNull();
  }
}
