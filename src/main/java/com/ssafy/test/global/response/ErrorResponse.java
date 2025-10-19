package com.ssafy.test.global.response;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ErrorResponse {

    private final String code;         // 에러 코드 (ex: USER_404)
    private final String message;      // 사용자 메시지
    private final String path;         // 요청 경로 (요청 URI)
    private final LocalDateTime timestamp;  // 발생 시간

    public static ErrorResponse of(String code, String message, String path) {
        return ErrorResponse.builder()
                .code(code)
                .message(message)
                .path(path)
                .timestamp(LocalDateTime.now())
                .build();
    }
}