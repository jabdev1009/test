package com.ssafy.test.snapshot.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class S3StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);
    private static final String SNAPSHOT_PREFIX = "snapshot";
    private static final String GLB_PREFIX = "glb";

    private final S3Service s3Service;

    public String uploadSnapshot(ChunkInfo chunkInfo, int version, String jsonContent) {
        String key = buildS3Key(SNAPSHOT_PREFIX, chunkInfo, version, "json");
        return s3Service.uploadFile(key, jsonContent);
    }

    public String uploadGLB(ChunkInfo chunkInfo, int version, byte[] glbData) {
        String key = buildS3Key(GLB_PREFIX, chunkInfo, version, "glb");
        return s3Service.uploadFile(key, glbData);
    }

    public Optional<String> getLatestSnapshot(ChunkInfo chunkInfo, int version) {
        try {
            String snapshot = s3Service.getChunkFile(
                    SNAPSHOT_PREFIX + "/" + chunkInfo.worldName(),
                    chunkInfo.lod(),
                    chunkInfo.x(),
                    chunkInfo.y(),
                    chunkInfo.z(),
                    version
            );

            // null 또는 빈 문자열 체크
            if (snapshot == null || snapshot.isEmpty()) {
                log.info("기존 스냅샷 없음 (최초 생성). 청크: {}", chunkInfo);
                return Optional.empty();
            }

            return Optional.of(snapshot);

        } catch (Exception e) {
            // S3에서 파일을 찾지 못한 경우 (최초 생성)
            log.info("기존 스냅샷 없음 (S3 조회 실패). 청크: {}", chunkInfo);
            return Optional.empty();
        }
    }

    private String buildS3Key(String prefix, ChunkInfo info, int version, String ext) {
        return String.format("%s/%s/l%d/x%d/y%d/z%d/v%d.%s",
                prefix, info.worldName(), info.lod(),
                info.x(), info.y(), info.z(), version, ext
        );
    }










//    public String uploadSnapshot(ChunkInfo chunkInfo, int version, String jsonContent) {
//        String key = buildS3Key(SNAPSHOT_PREFIX, chunkInfo, version, "json");
//        return s3Service.uploadFile(key, jsonContent);
//    }
//
//    public String uploadGLB(ChunkInfo chunkInfo, int version, byte[] glbData) {
//        String key = buildS3Key(GLB_PREFIX, chunkInfo, version, "glb");
//        return s3Service.uploadFile(key, glbData);
//    }
}