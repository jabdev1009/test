package com.ssafy.test.snapshot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record DeltaDTO(
        UUID opId,
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