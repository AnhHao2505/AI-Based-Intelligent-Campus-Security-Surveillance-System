package com.fa26se040.icss.validation;

import com.fa26se040.icss.dto.accessrequest.AccessRequestCreateRequest;
import com.fa26se040.icss.dto.accessrequest.GroupAccessRequestCreateRequest;
import com.fa26se040.icss.dto.accessrequest.IndividualAccessRequestCreateRequest;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.service.ReasonValidator;
import com.fa26se040.icss.service.SystemConfigService;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Việc 7: mục đích đơn truy cập dùng chung giới hạn với ô lý do (REASON_MIN_LENGTH / REASON_MAX_LENGTH), đọc cấu hình
 * mỗi lần kiểm. Validator dựng như trong Spring: ReasonLengthValidator nhận ReasonValidator thật đọc SystemConfigService
 * (mock trả giá trị từ map đổi được giữa chừng, thay cho ADMIN sửa cấu hình).
 */
class AccessRequestPurposeLengthTest {

    private final Map<ConfigKey, Integer> config = new ConcurrentHashMap<>();
    private ValidatorFactory factory;
    private Validator validator;

    @BeforeEach
    void setUp() {
        config.put(ConfigKey.REASON_MIN_LENGTH, 10);
        config.put(ConfigKey.REASON_MAX_LENGTH, 500);
        SystemConfigService systemConfigService = Mockito.mock(SystemConfigService.class,
                invocation -> "getInt".equals(invocation.getMethod().getName())
                        ? config.get((ConfigKey) invocation.getArgument(0))
                        : Mockito.RETURNS_DEFAULTS.answer(invocation));
        ReasonValidator reasonValidator = new ReasonValidator(systemConfigService);

        ValidatorFactory defaults = Validation.buildDefaultValidatorFactory();
        ConstraintValidatorFactory fallback = defaults.getConstraintValidatorFactory();
        factory = Validation.byDefaultProvider().configure()
                .constraintValidatorFactory(new ConstraintValidatorFactory() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
                        if (key == ReasonLengthValidator.class) {
                            return (T) new ReasonLengthValidator(reasonValidator);
                        }
                        return fallback.getInstance(key);
                    }

                    @Override
                    public void releaseInstance(ConstraintValidator<?, ?> instance) {
                    }
                })
                .buildValidatorFactory();
        defaults.close();
        validator = factory.getValidator();
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    private Map<String, String> errors(Object dto) {
        Set<ConstraintViolation<Object>> violations = validator.validate(dto);
        return violations.stream().collect(Collectors.toMap(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage));
    }

    private static IndividualAccessRequestCreateRequest individual(String purpose) {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        return new IndividualAccessRequestCreateRequest(UUID.randomUUID(), start, start.plusHours(2), purpose);
    }

    private static GroupAccessRequestCreateRequest group(String purpose) {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        return new GroupAccessRequestCreateRequest(UUID.randomUUID(), start, start.plusHours(2), purpose, List.of("SV-002"));
    }

    private static AccessRequestCreateRequest legacy(String purpose) {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        return new AccessRequestCreateRequest(UUID.randomUUID(), RequestType.INDIVIDUAL, start, start.plusHours(2), purpose, null);
    }

    @Test
    @DisplayName("PL-01: đơn cá nhân — 9 ký tự bị từ chối, 10 và 500 qua, 501 bị từ chối (trước đây tối đa 1000, không có tối thiểu)")
    void individual_boundaries() {
        Map<String, String> tooShort = errors(individual("a".repeat(9)));
        assertThat(tooShort).containsExactly(Map.entry("purpose", "Mục đích phải có từ 10 đến 500 ký tự"));
        assertThat(errors(individual("a".repeat(10)))).isEmpty();
        assertThat(errors(individual("a".repeat(500)))).isEmpty();
        assertThat(errors(individual("a".repeat(501)))).containsExactly(Map.entry("purpose", "Mục đích phải có từ 10 đến 500 ký tự"));
        assertThat(errors(individual("a".repeat(1000)))).containsKey("purpose");
    }

    @Test
    @DisplayName("PL-02: đơn nhóm — 9 ký tự và 501 ký tự bị từ chối, 10 / 500 qua")
    void group_boundaries() {
        assertThat(errors(group("a".repeat(9)))).containsExactly(Map.entry("purpose", "Mục đích phải có từ 10 đến 500 ký tự"));
        assertThat(errors(group("a".repeat(10)))).isEmpty();
        assertThat(errors(group("a".repeat(500)))).isEmpty();
        assertThat(errors(group("a".repeat(501)))).containsExactly(Map.entry("purpose", "Mục đích phải có từ 10 đến 500 ký tự"));
    }

    @Test
    @DisplayName("PL-03: endpoint chung POST /api/access-requests — cùng giới hạn")
    void legacyEndpoint_boundaries() {
        assertThat(errors(legacy("a".repeat(9)))).containsExactly(Map.entry("purpose", "Mục đích phải có từ 10 đến 500 ký tự"));
        assertThat(errors(legacy("a".repeat(10)))).isEmpty();
        assertThat(errors(legacy("a".repeat(501)))).containsKey("purpose");
    }

    @Test
    @DisplayName("PL-04: đổi REASON_MAX_LENGTH 500 -> 50 và REASON_MIN_LENGTH 10 -> 20 -> áp dụng ngay cho mục đích (cá nhân và nhóm)")
    void configChange_appliesImmediately() {
        String purpose60 = "m".repeat(60);
        String purpose15 = "m".repeat(15);
        assertThat(errors(individual(purpose60))).isEmpty();
        assertThat(errors(group(purpose15))).isEmpty();

        config.put(ConfigKey.REASON_MAX_LENGTH, 50);
        config.put(ConfigKey.REASON_MIN_LENGTH, 20);

        assertThat(errors(individual(purpose60))).containsExactly(Map.entry("purpose", "Mục đích phải có từ 20 đến 50 ký tự"));
        assertThat(errors(group(purpose60))).containsExactly(Map.entry("purpose", "Mục đích phải có từ 20 đến 50 ký tự"));
        assertThat(errors(group(purpose15))).containsExactly(Map.entry("purpose", "Mục đích phải có từ 20 đến 50 ký tự"));
        assertThat(errors(individual("m".repeat(50)))).isEmpty();
    }

    @Test
    @DisplayName("PL-05: đo sau trim; chỉ khoảng trắng để @NotBlank báo thiếu mục đích (không báo hai lỗi)")
    void trimmedAndBlank() {
        assertThat(errors(individual("   " + "a".repeat(9) + "   "))).containsExactly(
                Map.entry("purpose", "Mục đích phải có từ 10 đến 500 ký tự"));
        assertThat(errors(individual("     "))).containsExactly(Map.entry("purpose", "Mục đích không được để trống"));
    }
}
