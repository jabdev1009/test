package com.ssafy.test.snapshot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.test.snapshot.dto.DeltaDTO;
import com.ssafy.test.snapshot.repo.SnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.Executors;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);
    private final StringRedisTemplate redisTemplate;
    private final SnapshotRepository repository;

    private static final String OPID_PATTERN = "op_ids:*";
    private static final String DELTAS_PREFIX = "deltas:";
    private static final String TOMBSTONE_PREFIX = "tombstone:";
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

            // DELTAS에서 hashkey로 조회한 직렬화된 field를 담아둘 Queue?
            // TODO : 자료형에 대한 고민 필요
            Map<String, String> curDeltas = new HashMap<>();
            for (String opId : opIds) {
                log.debug("처리 대상 op_id: {}", opId);

                // Redis Hash에서 field(opId)에 해당하는 직렬화된 JSON 문자열 조회
                String delta = getDelta(deltaKey, opId);
                if (delta == null) {
                    log.warn("op_id에 해당하는 데이터가 없습니다. deltaKey: {}, opId: {}", deltaKey, opId);
                    continue;
                }
                curDeltas.put(opId, delta);
            }

            String tombKey = TOMBSTONE_PREFIX + chunkKey;
            Set<String> tombstoneOpIds = redisTemplate.opsForZSet().rangeByScore(tombKey,Double.NEGATIVE_INFINITY, maxScore);


            // 현재 청크의 최신버전을 postgres에서 확인
//            repository.findLatestChunkIndexUuid()

            // S3에서 현재 청크의 가장 최근 버전의 스냅샷을 조회
            // 스냅샷 + deltas - tombstone을 opid 기준으로 비교
            // 최신 delta 정보들을 다시 S3에 적재
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

}
