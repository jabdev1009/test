package com.ssafy.test.snapshot;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.test.snapshot.dto.DeltaDTO;
import com.ssafy.test.snapshot.repo.ChunkRepository;
import com.ssafy.test.snapshot.service.S3Service;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.Executors;
import java.time.Instant;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);
    private final StringRedisTemplate redisTemplate;
    private final S3Service s3Service;
    private final ChunkRepository repository;
    private final ObjectMapper objectMapper;

    private static final String OPID_PATTERN = "op_ids:*";
    private static final String DELTAS_PREFIX = "deltas:";
    private static final String TOMBSTONE_PREFIX = "tombstone:";
    private static final String SNAPSHOT_PREFIX = "snapshot";
    private static final String GLB_PREFIX = "glb";
    /**
     1. 배치 시작 시 timestamp 기준 범위를 정함
     2. Redis에서 op_ids:<chunkId> 범위 조회 → batch에 포함될 op_id 확보
     3. 해당 op_id로 DELTAS Hash 조회 → delta JSON 확보
     4. tombstones:<chunkId> 범위 조회 → 삭제된 op_id 목록 확보
     5. Postgres에서 해당 청크의 가장 최근 snapshot 조회
     6. 최근 snapshot, delta JSON에서 tombstone에 있는 op_id 제거 → 최종 적용할 delta 리스트 완성
     7. S3에 최종 delta 리스트 업로드 -> 반환된 주소 정보를 Postgres snopshot 테이블에 추가
     7. GLB 생성
     8. 처리 완료 시 op_ids:<chunkId>와 tombstones:<chunkId>에서 사용한 op_id 제거, DELTAS hash에서 field 제거
     */
//    @Scheduled(fixedRateString = "60000")
    public void makeSnapshot() {
        // 1. 배치 시작 시 timestamp 기준 범위를 정함
        Instant batchStartTime = Instant.now();
        log.info("스냅샷 배치 시작 시간: {}", batchStartTime);

        // 2. Redis에서 op_id 패턴 검사로 배치 작업이 필요한 청크 조회
        Set<String> chunkKeys = redisTemplate.keys(OPID_PATTERN);
        if (chunkKeys == null || chunkKeys.isEmpty()) {
            log.info("처리할 chunk 없음");
            return;
        }

        log.info("스냅샷 생성 시작. 대상 청크: {}", chunkKeys);
        // try-with-resources
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String chunkKey : chunkKeys) {
                
                // chunk 별 스냅샷 생성 작업
                executor.submit(() -> processChunk(chunkKey, batchStartTime));
            }
        }

        log.info("스냅샷 생성 완료.");
    }

    // chunkKey : {world:”worldname”}:l0:x-123:y123:z123
    // S3 snapshot 파일명 : snapshot/worldname/l0/x1/y1/z1/v12.json
//     snapshot 파일 : {
//        "opId": "a9a5b29a-1e62-4f50-81bb-d24d5ce86b45",
//        "voxelId": 1203,
//        "faceMask": 3,
//        "colorSchema": "RGB_FACES",
//        "colorBytes": "AQIDBA==",
//        "actor": "user1",
//        "policyTags": "paint",
//        "timestamp": "2025-10-27T10:03:12Z"
//      },
//      {
//        "opId": "1c8b34b2-6ff1-4b80-aef4-9a8b3e7b2f83",
//        "voxelId": 1204,
//        "faceMask": 2,
//        "colorSchema": "RGB1",
//        "colorBytes": "AgMEAA==",
//        "actor": "user2",
//        "policyTags": "erase",
//        "timestamp": "2025-10-27T10:04:01Z"
//      }
    // S3 glb 파일명 : glb/worldname/l0/x1/y1/z1/v12.glb
    private void processChunk(String chunkKey, Instant batchStartTime) {
        try {
            log.info("현재 작업 청크: {}, 배치 시작 시간: {}", chunkKey, batchStartTime);

            // batchStartTime을 기준으로 score(timestamp)가 작은 op_id 조회
            double maxScore = (double) batchStartTime.toEpochMilli();
            Set<String> opIds = getOpIds(chunkKey, maxScore);

            // 추가된 voxel 없음
            if (opIds == null || opIds.isEmpty()) log.info("적용할 작업이 없습니다. chunkKey: {}", chunkKey);

            // 실제 델타 정보를 조회하기 위한 key
            String deltaKey = DELTAS_PREFIX + chunkKey;

            // 현재 배치 처리의 대상 delta 정보
            Map<UUID, DeltaDTO> curDeltaMap = opIds.parallelStream()
                    .map(opId -> {
                        String delta = getDelta(deltaKey, opId);
                        if (delta == null) {
                            log.warn("op_id에 해당하는 데이터가 없습니다. deltaKey: {}, opId: {}", deltaKey, opId);
                        }
                        return delta;
                    })
                    .filter(Objects::nonNull)
                    .map(json -> {
                        try {
                            return objectMapper.readValue(json, DeltaDTO.class);
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toMap(DeltaDTO::opId, Function.identity()));


            // S3에서 현재 청크의 가장 최근 버전의 스냅샷을 조회
            String latestSnap = getLatestSnapshot(chunkKey);
            Map<UUID, DeltaDTO> latestSnapMap = new HashMap<>();
            try (JsonParser parser = objectMapper.getFactory().createParser(latestSnap)) {
                if (parser.nextToken() == JsonToken.START_ARRAY) {
                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        DeltaDTO delta = parser.readValueAs(DeltaDTO.class);
                        latestSnapMap.put(delta.opId(), delta);
                    }
                }
            }

            latestSnapMap.putAll(curDeltaMap);

            // 현재 작업 중인 청크에서 지워져야 하는 op_id의 목록
            String tombKey = TOMBSTONE_PREFIX + chunkKey;
            Set<String> tombstoneOpIds = redisTemplate.opsForZSet().rangeByScore(tombKey,Double.NEGATIVE_INFINITY, maxScore);

            // 스냅샷 + deltas - tombstone을 opid 기준으로 비교
            tombstoneOpIds.forEach(id -> latestSnapMap.remove(UUID.fromString(id)));
            List<DeltaDTO> finalSnapshot = new ArrayList<>(latestSnapMap.values());
            // 최신 delta 정보들을 다시 S3에 적재
            // delta 정보를 사용해서 glb파일 생성




            // Postgres에 메타 데이터 삽입





        } catch (Exception e) {
            log.error("청크 처리 중 오류 발생: {}", chunkKey, e);
        }
    }

    private Set<String> getOpIds(String chunkKey, Double maxScore) {
        return redisTemplate.opsForZSet().rangeByScore(chunkKey, Double.NEGATIVE_INFINITY, maxScore);
    }

    private String getDelta(String hashKey, String opId) {
        return (String) redisTemplate.opsForHash().get(hashKey, opId);
    }

    public String getLatestSnapshot(String chunkKey) {
        String worldName = extractWorldName(chunkKey);

        int lod = extractInt(chunkKey, "l");
        int x = extractInt(chunkKey, "x");
        int y = extractInt(chunkKey, "y");
        int z = extractInt(chunkKey, "z");

        return s3Service.getLatestChunkFile(
                SNAPSHOT_PREFIX + "/" + worldName,
                lod, x, y, z
        );
    }

    private String extractWorldName(String key) {
        int start = key.indexOf("{world:\"") + 8;
        int end = key.indexOf("\"}", start);
        if (start < 0 || end < 0) {
            // throw new CustomException("worldname 파싱 실패: " + key);
        }
        return key.substring(start, end);
    }

    private int extractInt(String key, String prefix) {
        int start = key.indexOf(prefix);
//        if (start < 0) throw new IllegalArgumentException(prefix + " 값 파싱 실패: " + key);

        int end = key.indexOf(":", start + 1);
        if (end < 0) end = key.length();

        String value = key.substring(start + prefix.length(), end);
        return Integer.parseInt(value);
    }
}
