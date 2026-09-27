package com.macro.mall.agent.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * 按 Unicode 码点计数校验长度，并有意拒绝 {@code null}。
 *
 * <p>Python {@code len()} 与 Pydantic 的 {@code min_length}/{@code max_length} 按 Unicode 码点计数，
 * 而 Jakarta {@code @Size} 与 {@link String#length()} 按 UTF-16 单元计数，补充平面字符会被算成两倍。
 * 因此这里单独按码点计数。{@code null} 由本约束拒绝，不依赖会额外 trim 掉非 Python 空白控制符的
 * {@code @NotBlank}，从而保持与 Python 校验完全一致。
 */
@Documented
@Constraint(validatedBy = CodePointLength.Validator.class)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface CodePointLength {

    int min() default 0;

    int max() default Integer.MAX_VALUE;

    String message() default "长度必须是 1..1000 个 Unicode 码点";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Unicode 码点计数校验器：{@code null} 视为不合法。 */
    class Validator implements ConstraintValidator<CodePointLength, CharSequence> {

        private int min;
        private int max;

        @Override
        public void initialize(CodePointLength annotation) {
            this.min = annotation.min();
            this.max = annotation.max();
        }

        @Override
        public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
            if (value == null) {
                return false;
            }
            int length = value.toString().codePointCount(0, value.length());
            return length >= min && length <= max;
        }
    }
}
