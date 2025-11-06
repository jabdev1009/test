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
) {

    public enum ColorSchema {
        RGB1, RGB_FACES
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID opId;
        private int vSeq;
        private int voxelId;
        private int faceMask;
        private ColorSchema colorSchema;
        private byte[] colorBytes;
        private String actor;
        private String policyTags;
        private Instant timestamp;

        public Builder opId(UUID opId) { this.opId = opId; return this; }
        public Builder vSeq(int vSeq) { this.vSeq = vSeq; return this; }
        public Builder voxelId(int voxelId) { this.voxelId = voxelId; return this; }
        public Builder faceMask(int faceMask) { this.faceMask = faceMask; return this; }
        public Builder colorSchema(ColorSchema colorSchema) { this.colorSchema = colorSchema; return this; }
        public Builder colorBytes(byte[] colorBytes) { this.colorBytes = colorBytes; return this; }
        public Builder actor(String actor) { this.actor = actor; return this; }
        public Builder policyTags(String policyTags) { this.policyTags = policyTags; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }

        public DeltaDTO build() {
            return new DeltaDTO(opId, vSeq, voxelId, faceMask, colorSchema, colorBytes, actor, policyTags, timestamp);
        }
    }
}