package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.BulkImportRowResult;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V135: dung lượng tối đa ảnh khuôn mặt tài khoản đọc từ USER_FACE_IMAGE_MAX_KB (mặc định 350, khoảng 100–1024), sửa qua
 * SystemConfigService.update có hiệu lực ngay cho cả đăng ký đơn lẻ (FaceDataService) và nạp theo lô (UserBulkImportHelper).
 * Câu báo dùng String.format theo locale mặc định của JVM (giữ như trước), nên kỳ vọng cũng định dạng bằng String.format.
 * Mọi ca dừng ở bước kiểm dung lượng / định dạng, trước khi gọi ai-service, nên không cần AI hay MinIO.
 */
class FaceImageMaxKbConfigIntegrationTest extends AbstractIntegrationTest {

    private static final String KEY = ConfigKey.USER_FACE_IMAGE_MAX_KB.getKey();

    @Autowired private FaceDataService faceDataService;
    @Autowired private UserBulkImportHelper userBulkImportHelper;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private UserRepository userRepository;

    private User admin;
    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        admin = userRepository.save(User.builder()
                .email("fimk.adm." + suffix.toLowerCase(Locale.ROOT) + "@fpt.edu.vn").userCode("FIMK-ADM-" + suffix)
                .fullName("FIMK admin " + suffix).role(Role.ADMIN).accessLevel(3).isActive(true).build());
        resetToDefault();
    }

    @AfterEach
    void cleanUp() {
        resetToDefault();
        // admin có dòng trong system_configuration_change_logs -> chỉ vô hiệu hoá, không xoá
        admin.setIsActive(false);
        userRepository.save(admin);
    }

    private void resetToDefault() {
        String def = ConfigKey.USER_FACE_IMAGE_MAX_KB.getDefaultValue();
        if (!def.equals(systemConfigService.getString(ConfigKey.USER_FACE_IMAGE_MAX_KB))) {
            systemConfigService.update(KEY, def, admin.getEmail());
        }
    }

    private static MockMultipartFile jpeg(int kb, int extraBytes) {
        return new MockMultipartFile("faceImage", "front.jpg", "image/jpeg", new byte[kb * 1024 + extraBytes]);
    }

    @Test
    @DisplayName("Mặc định 350 KB: ảnh 350 KB + 1 byte bị từ chối, câu báo nêu 350KB như trước")
    void defaultLimit_rejectsOversizedImage() {
        assertThat(faceDataService.getFaceImageMaxKb()).isEqualTo(350);

        assertThatThrownBy(() -> faceDataService.registerFace("FIMK-" + suffix, jpeg(350, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(String.format("Kích thước ảnh vượt quá giới hạn tối đa 350KB (dung lượng file: %.1f KB)", 350.0 + 1 / 1024.0));
    }

    @Test
    @DisplayName("Đổi giá trị qua System Configuration có hiệu lực ngay, không restart")
    void updatedLimit_appliesImmediately() {
        systemConfigService.update(KEY, "200", admin.getEmail());
        assertThat(faceDataService.getFaceImageMaxKb()).isEqualTo(200);
        assertThatThrownBy(() -> faceDataService.registerFace("FIMK-" + suffix, jpeg(250, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(String.format("Kích thước ảnh vượt quá giới hạn tối đa 200KB (dung lượng file: %.1f KB)", 250.0));

        // Nâng lên 500: ảnh 400 KB qua bước dung lượng, dừng ở bước định dạng (file .gif) -> chưa gọi ai-service
        systemConfigService.update(KEY, "500", admin.getEmail());
        MockMultipartFile gif400 = new MockMultipartFile("faceImage", "front.gif", "image/gif", new byte[400 * 1024]);
        assertThatThrownBy(() -> faceDataService.registerFace("FIMK-" + suffix, gif400))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Định dạng file không hợp lệ. Chỉ chấp nhận file ảnh định dạng JPG hoặc PNG.");
    }

    @Test
    @DisplayName("Nạp tài khoản theo lô dùng cùng giới hạn, đổi giá trị có hiệu lực ngay")
    void bulkImport_usesSameLimit() {
        systemConfigService.update(KEY, "200", admin.getEmail());
        String code = "FIMKB" + suffix;
        BulkImportRowResult row = userBulkImportHelper.processSingleRow(1, code, "Nguyen Van Fimk", code.toLowerCase(Locale.ROOT) + "@fpt.edu.vn",
                Role.NORMAL_USER, UUID.randomUUID(), new byte[250 * 1024], code + ".jpg", "Temp@123456");

        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getErrorMessage()).isEqualTo(String.format("Kích thước ảnh vượt quá giới hạn tối đa 200KB (dung lượng file: %.1f KB)", 250.0));
        assertThat(userRepository.findByUserCode(code)).isEmpty();
    }

    @Test
    @DisplayName("Giá trị ngoài 100–1024 hoặc không phải số nguyên bị từ chối khi lưu, giá trị cũ giữ nguyên")
    void outOfBoundsValue_rejectedOnSave() {
        assertThatThrownBy(() -> systemConfigService.update(KEY, "99", admin.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Giá trị không được nhỏ hơn 100");
        assertThatThrownBy(() -> systemConfigService.update(KEY, "1025", admin.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Giá trị không được lớn hơn 1024");
        assertThatThrownBy(() -> systemConfigService.update(KEY, "350.5", admin.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Giá trị cấu hình phải là số nguyên");

        assertThat(faceDataService.getFaceImageMaxKb()).isEqualTo(350);
    }
}
