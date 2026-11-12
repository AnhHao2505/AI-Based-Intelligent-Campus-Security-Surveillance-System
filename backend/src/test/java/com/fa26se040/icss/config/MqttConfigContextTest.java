package com.fa26se040.icss.config;

import com.fa26se040.icss.consumer.CameraRecordingMqttConsumer;
import com.fa26se040.icss.consumer.EdgeStatusMqttConsumer;
import com.fa26se040.icss.consumer.SecurityIncidentMqttConsumer;
import com.fa26se040.icss.service.EdgeMqttControlService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Spring phải dựng được bean MqttConfig (chọn đúng constructor) và tiêm EdgeStatusMqttConsumer.
 * Unit test gọi constructor bằng tay không bắt được lỗi "nhiều constructor, không có @Autowired".
 */
class MqttConfigContextTest {

    @Test
    void springCanInstantiateMqttConfigWithEdgeStatusConsumer() {
        EdgeStatusMqttConsumer edgeStatusConsumer = mock(EdgeStatusMqttConsumer.class);
        new ApplicationContextRunner()
                .withPropertyValues("app.mqtt.enabled=false")
                .withBean(SecurityIncidentMqttConsumer.class, () -> mock(SecurityIncidentMqttConsumer.class))
                .withBean(CameraRecordingMqttConsumer.class, () -> mock(CameraRecordingMqttConsumer.class))
                .withBean(EdgeMqttControlService.class, () -> mock(EdgeMqttControlService.class))
                .withBean(EdgeStatusMqttConsumer.class, () -> edgeStatusConsumer)
                .withUserConfiguration(MqttConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(MqttConfig.class))
                            .extracting("edgeStatusMqttConsumer").isSameAs(edgeStatusConsumer);
                });
    }
}
