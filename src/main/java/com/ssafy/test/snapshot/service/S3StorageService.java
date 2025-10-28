package com.ssafy.test.snapshot.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class S3StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);
    private static final String SNAPSHOT_PREFIX = "snapshot";
    private static final String GLB_PREFIX = "glb";

    private final S3Service s3Service;

    public String uploadSnapshot(ChunkInfo chunkInfo, long version, String jsonContent) {
        String key = buildS3Key(SNAPSHOT_PREFIX, chunkInfo, version, "json");
        return s3Service.uploadFile(key, jsonContent);
    }

    public String uploadGLB(ChunkInfo chunkInfo, long version, byte[] glbData) {
        String key = buildS3Key(GLB_PREFIX, chunkInfo, version, "glb");
        return s3Service.uploadFile(key, glbData);
    }

    public String getLatestSnapshot(ChunkInfo chunkInfo) {
        return s3Service.getLatestChunkFile(
                SNAPSHOT_PREFIX + "/" + chunkInfo.worldName(),
                chunkInfo.lod(), chunkInfo.x(), chunkInfo.y(), chunkInfo.z()
        );
    }

    private String buildS3Key(String prefix, ChunkInfo info, long version, String ext) {
        return String.format("%s/%s/l%d/x%d/y%d/z%d/v%d.%s",
                prefix, info.worldName(), info.lod(),
                info.x(), info.y(), info.z(), version, ext
        );
    }
}