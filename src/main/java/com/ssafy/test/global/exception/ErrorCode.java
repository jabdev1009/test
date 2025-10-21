package com.ssafy.test.global.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    // 예시
    // 공통 오류
    INVALID_INPUT("E001", "잘못된 입력입니다.", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("E002", "인증이 필요합니다.", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("E003", "권한이 없습니다.", HttpStatus.FORBIDDEN),
    NOT_FOUND("E004", "리소스를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED("E005", "허용되지 않은 메서드입니다.", HttpStatus.METHOD_NOT_ALLOWED),

    // 사용자 관련
    USER_NOT_FOUND("U001", "사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    DUPLICATE_USER("U002", "이미 존재하는 사용자입니다.", HttpStatus.CONFLICT),

    // 서버 내부 오류
    INTERNAL_SERVER_ERROR("S001", "서버 내부 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus status;
}