package com.nori.tc.ui.adapters.web.dto.request;

import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 관리 요청 DTO의 길이 validation 계약을 검증합니다.
 */
class ManagementRequestValidationContractTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    @Test
    @DisplayName("모델 요청 DTO는 확장된 modelName/modelVersion 길이를 허용합니다")
    void modelRequestsAllowExtendedLengths() {
        final ModelRootCreateRequest rootCreateRequest = new ModelRootCreateRequest(
                repeat('M', 1000),
                UiProtocolType.SECS,
                "NORI"
        );
        final ModelUpsertRequest upsertRequest = new ModelUpsertRequest(
                repeat('M', 1000),
                repeat('V', 100),
                UiProtocolType.SECS,
                UiModelStatus.OPERATE,
                "desc",
                "maker",
                "SYSTEM",
                "SYSTEM"
        );

        assertEquals(0, validator.validate(rootCreateRequest).size());
        assertEquals(0, validator.validate(upsertRequest).size());
    }

    @Test
    @DisplayName("모델 요청 DTO는 modelName/modelVersion 길이 초과를 차단합니다")
    void modelRequestsRejectExceededLengths() {
        final Set<ConstraintViolation<ModelRootCreateRequest>> rootViolations = validator.validate(
                new ModelRootCreateRequest(repeat('M', 1001), UiProtocolType.SECS, "NORI")
        );
        final Set<ConstraintViolation<ModelUpsertRequest>> upsertViolations = validator.validate(
                new ModelUpsertRequest(
                        "MODEL",
                        repeat('V', 101),
                        UiProtocolType.SECS,
                        UiModelStatus.OPERATE,
                        null,
                        null,
                        "SYSTEM",
                        "SYSTEM"
                )
        );

        assertHasMessage(rootViolations, "modelName은 1000자 이하여야 합니다.");
        assertHasMessage(upsertViolations, "modelVersion은 100자 이하여야 합니다.");
    }

    @Test
    @DisplayName("EQP 요청 DTO는 현재 생성/수정 필드 조합을 허용합니다")
    void eqpRequestsAllowCurrentCreateAndUpdateShape() {
        final EqpCreateRequest createRequest = new EqpCreateRequest(
                "EQP-01",
                1L,
                UiProtocolType.SECS,
                "ACTIVE",
                false,
                1,
                "127.0.0.1",
                5000,
                101L,
                201L,
                301L,
                null,
                null,
                null
        );
        final EqpUpdateRequest updateRequest = new EqpUpdateRequest(
                1L,
                "ACTIVE",
                false,
                1,
                "127.0.0.1",
                5000,
                null,
                null,
                null
        );

        assertEquals(0, validator.validate(createRequest).size());
        assertEquals(0, validator.validate(updateRequest).size());
    }

    @Test
    @DisplayName("EQP 생성 요청 DTO는 0 이하 jar ref를 차단합니다")
    void eqpCreateRequestRejectsNonPositiveJarRefs() {
        final Set<ConstraintViolation<EqpCreateRequest>> createViolations = validator.validate(
                new EqpCreateRequest(
                        "EQP-01",
                        1L,
                        UiProtocolType.SECS,
                        "ACTIVE",
                        false,
                        1,
                        "127.0.0.1",
                        5000,
                        101L,
                        0L,
                        0L,
                        null,
                        null,
                        null
                )
        );

        assertHasMessage(createViolations, "gatewayJarRef는 1 이상이어야 합니다.");
        assertHasMessage(createViolations, "businessJarRef는 1 이상이어야 합니다.");
    }

    private static void assertHasMessage(
            final Set<? extends ConstraintViolation<?>> violations,
            final String expectedMessage
    ) {
        assertTrue(
                violations.stream().map(ConstraintViolation::getMessage).anyMatch(expectedMessage::equals),
                () -> "예상 validation 메시지가 없습니다. messages="
                        + violations.stream().map(ConstraintViolation::getMessage).toList()
        );
    }

    private static String repeat(final char value, final int count) {
        return String.valueOf(value).repeat(count);
    }
}
