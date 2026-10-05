package com.cadence.identity.domain;

import com.cadence.common.error.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    @Test
    void requiresTenCharacters() {
        assertThatThrownBy(() -> PasswordPolicy.validate("123456789"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("10");
        assertThatCode(() -> PasswordPolicy.validate("1234567890")).doesNotThrowAnyException();
        assertThatThrownBy(() -> PasswordPolicy.validate(null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsPasswordsBCryptWouldTruncate() {
        assertThatCode(() -> PasswordPolicy.validate("a".repeat(72))).doesNotThrowAnyException();
        assertThatThrownBy(() -> PasswordPolicy.validate("é".repeat(37))) // 74 bytes in UTF-8
                .isInstanceOf(BadRequestException.class);
    }
}
