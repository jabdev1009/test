package com.ssafy.test.snapshot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@RequiredArgsConstructor
public class DeltaDTO implements Serializable {
    /** 클라이언트의 멱등성 키 */
    @JsonProperty("op_id")
    private UUID opId;

    /** 블록의 고유 voxel id */
    @JsonProperty("voxel_id")
    private int voxelId;

    /** 블록의 노출면 마스크 (0~63) */
    @JsonProperty("face_mask")
    private int faceMask;

    /** 색상 스키마 (RGB1 or RGB_FACES) */
    @JsonProperty("color_schema")
    private ColorSchema colorSchema;

    /** 색상 정보 (3B 또는 18B, RGB) */
    @JsonProperty("color_bytes")
    private byte[] colorBytes;

    /** 작업을 수행한 사용자 */
    @JsonProperty("actor")
    private String actor;

    /** 정책 태그 (예: "user:trusted" 등) */
    @JsonProperty("policy_tags")
    private String policyTags;

    /** 생성 시각 (ISO-8601 형식) */
    @JsonProperty("ts")
    private Instant timestamp;

    // enum 내부 정의
    public enum ColorSchema {
        RGB1, RGB_FACES
    }

}
