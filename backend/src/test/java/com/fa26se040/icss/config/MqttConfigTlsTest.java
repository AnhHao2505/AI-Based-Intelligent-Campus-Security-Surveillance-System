package com.fa26se040.icss.config;

import com.fa26se040.icss.consumer.CameraRecordingMqttConsumer;
import com.fa26se040.icss.consumer.EdgeStatusMqttConsumer;
import com.fa26se040.icss.consumer.SecurityIncidentMqttConsumer;
import com.fa26se040.icss.service.EdgeMqttControlService;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.SSLSocketFactory;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class MqttConfigTlsTest {

    @Mock
    private SecurityIncidentMqttConsumer incidentConsumer;

    @Mock
    private CameraRecordingMqttConsumer recordingConsumer;

    @Mock
    private EdgeMqttControlService edgeMqttControlService;

    @Mock
    private EdgeStatusMqttConsumer edgeStatusConsumer;

    private MqttConfig config;

    @BeforeEach
    void setUp() {
        config = new MqttConfig(incidentConsumer, recordingConsumer, edgeMqttControlService, edgeStatusConsumer);
    }

    @Test
    @DisplayName("URL mặc định tcp:// không khởi tạo custom SocketFactory")
    void defaultTcpUrl_doesNotSetCustomSocketFactory() {
        ReflectionTestUtils.setField(config, "brokerUrl", "tcp://localhost:1883");
        ReflectionTestUtils.setField(config, "username", "icss-backend");
        ReflectionTestUtils.setField(config, "password", "testpass");

        MqttConnectOptions options = config.buildConnectOptions();

        assertNotNull(options);
        assertTrue(options.isAutomaticReconnect());
        assertFalse(options.isCleanSession());
        assertEquals("icss-backend", options.getUserName());
        assertNull(options.getSocketFactory());
    }

    @Test
    @DisplayName("URL ssl:// nhưng không có truststorePath dùng TrustManager mặc định của JVM")
    void sslUrl_withoutCustomTruststore_usesJvmDefault() {
        ReflectionTestUtils.setField(config, "brokerUrl", "ssl://localhost:8883");
        ReflectionTestUtils.setField(config, "truststorePath", "");

        MqttConnectOptions options = config.buildConnectOptions();

        assertNotNull(options);
        // Khi không setSocketFactory, Paho sẽ tự sử dụng SSLSocketFactory.getDefault() khi connect ssl://
        assertNull(options.getSocketFactory());
    }

    @Test
    @DisplayName("URL ssl:// với đường dẫn truststore không tồn tại ném IllegalStateException")
    void sslUrl_withNonExistentTruststore_throwsException() {
        ReflectionTestUtils.setField(config, "brokerUrl", "ssl://localhost:8883");
        ReflectionTestUtils.setField(config, "truststorePath", "non_existent_truststore.jks");
        ReflectionTestUtils.setField(config, "truststorePassword", "changeit");

        assertThrows(IllegalStateException.class, () -> config.buildConnectOptions());
    }

    @Test
    @DisplayName("URL ssl:// với truststore hợp lệ khởi tạo custom SSLSocketFactory")
    void sslUrl_withValidTruststore_initializesSocketFactory(@TempDir Path tempDir) throws Exception {
        // Tạo một KeyStore hợp lệ tạm thời
        Path keyStorePath = tempDir.resolve("test-truststore.jks");
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, "changeit".toCharArray());
        try (FileOutputStream fos = new FileOutputStream(keyStorePath.toFile())) {
            ks.store(fos, "changeit".toCharArray());
        }

        ReflectionTestUtils.setField(config, "brokerUrl", "ssl://localhost:8883");
        ReflectionTestUtils.setField(config, "truststorePath", keyStorePath.toString());
        ReflectionTestUtils.setField(config, "truststorePassword", "changeit");
        ReflectionTestUtils.setField(config, "truststoreType", "JKS");

        MqttConnectOptions options = config.buildConnectOptions();

        assertNotNull(options);
        assertNotNull(options.getSocketFactory(), "SocketFactory phải được khởi tạo khi có truststore hợp lệ");
        assertTrue(options.getSocketFactory() instanceof SSLSocketFactory);
    }
}
