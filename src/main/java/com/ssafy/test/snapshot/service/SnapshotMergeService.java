package com.ssafy.test.snapshot.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.test.snapshot.dto.DeltaDTO;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class SnapshotMergeService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotMergeService.class);

    private final S3StorageService s3Storage;
    private final ObjectMapper objectMapper;

    public List<DeltaDTO> mergeSnapshot(ChunkInfo chunkInfo,
                                        Map<UUID, DeltaDTO> currentDeltas,
                                        Set<String> tombstoneOpIds) {
        Map<UUID, DeltaDTO> mergedMap = loadLatestSnapshot(chunkInfo);

        mergedMap.putAll(currentDeltas);
        log.info("Delta 병합 후 크기: {}", mergedMap.size());

        if (tombstoneOpIds != null && !tombstoneOpIds.isEmpty()) {
            tombstoneOpIds.forEach(opId -> {
                try {
                    mergedMap.remove(UUID.fromString(opId));
                } catch (IllegalArgumentException e) {
                    log.warn("잘못된 UUID 형식: {}", opId);
                }
            });
            log.info("Tombstone 적용 완료. 제거된 수: {}", tombstoneOpIds.size());
        }

        return new ArrayList<>(mergedMap.values());
    }

    private Map<UUID, DeltaDTO> loadLatestSnapshot(ChunkInfo chunkInfo) {
        Map<UUID, DeltaDTO> snapMap = new HashMap<>();

        try {
            String snapshotJson = s3Storage.getLatestSnapshot(chunkInfo);

            if (snapshotJson == null || snapshotJson.isEmpty() || snapshotJson.equals("[]")) {
                log.info("기존 스냅샷 없음. 새로 생성. 청크: {}", chunkInfo);
                return snapMap;
            }

            try (JsonParser parser = objectMapper.getFactory().createParser(snapshotJson)) {
                if (parser.nextToken() == JsonToken.START_ARRAY) {
                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        DeltaDTO delta = parser.readValueAs(DeltaDTO.class);
                        snapMap.put(delta.opId(), delta);
                    }
                }
            }

            log.info("기존 스냅샷 로드 완료. Delta 수: {}", snapMap.size());

        } catch (Exception e) {
            log.error("스냅샷 로드 실패. 빈 스냅샷으로 시작. 청크: {}", chunkInfo, e);
        }

        return snapMap;
    }
}