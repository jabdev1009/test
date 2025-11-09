package com.ssafy.test.snapshot.dto;

import java.time.Instant;
import java.util.UUID;

public record DeltaDTO(
        UUID opId,
        int vSeq,
        int voxelId,
        int faceMask,
        ColorSchema colorSchema,
        byte[] colorBytes,
        String actor,
        String policyTags,
        Instant timestamp
){
    public enum ColorSchema {
        RGB1, RGB_FACES
    }
}