package br.com.sport.accesscontrol.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CpfValidatorTests {

    @Test
    void acceptsValidCpfAndNormalizesDigits() {
        assertThat(CpfValidator.normalizeOrThrow("529.982.247-25")).isEqualTo("52998224725");
    }

    @Test
    void rejectsInvalidCpf() {
        assertThatThrownBy(() -> CpfValidator.normalizeOrThrow("529.982.247-26"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(CpfValidator.INVALID_CPF_MESSAGE);
    }

    @Test
    void rejectsSequentialRepeatedCpf() {
        assertThatThrownBy(() -> CpfValidator.normalizeOrThrow("111.111.111-11"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(CpfValidator.INVALID_CPF_MESSAGE);
    }
}
