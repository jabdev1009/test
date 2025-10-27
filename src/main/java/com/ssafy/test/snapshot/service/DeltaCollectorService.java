package com.ssafy.test.snapshot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.test.snapshot.dto.DeltaDTO;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Redis에서 Delta 데이터 수집 서비스
 */
@Service
@RequiredArgsConstructor
public class DeltaCollectorService {

    private static final Logger log = LoggerFactory.getLogger(DeltaCollectorService.class);
    private static final String DELTAS_PREFIX = "deltas:";
    private static final String TOMBSTONE_PREFIX = "tombstone:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Delta 수집
     * 1. op_ids 조회
     * 2. Delta JSON 조회 및 파싱
     * 3. Tombstone 조회
     */
    public DeltaCollectionResult collectDeltas(String chunkKey, double maxScore) {
        // 1. 처리 대상 op_id 조회
        Set<String> opIds = redisTemplate.opsForZSet()
                .rangeByScore(chunkKey, Double.NEGATIVE_INFINITY, maxScore);

        if (opIds == null || opIds.isEmpty()) {
            return new DeltaCollectionResult(Map.of(), opIds, Set.of());
        }

        // 2. Delta 데이터 조회 및 파싱
        String deltaKey = DELTAS_PREFIX + chunkKey;
        Map<UUID, DeltaDTO> currentDeltas = opIds.parallelStream()
                .map(opId -> fetchAndParseDelta(deltaKey, opId))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(DeltaDTO::opId, Function.identity()));

        // 3. Tombstone 조회
        String tombKey = TOMBSTONE_PREFIX + chunkKey;
        Set<String> tombstoneOpIds = redisTemplate.opsForZSet()
                .rangeByScore(tombKey, Double.NEGATIVE_INFINITY, maxScore);

        if (tombstoneOpIds == null) {
            tombstoneOpIds = Set.of();
        }

        log.info("Delta 수집 완료. 현재: {}, Tombstone: {}",
                currentDeltas.size(), tombstoneOpIds.size());

        return new DeltaCollectionResult(currentDeltas, opIds, tombstoneOpIds);
    }

    /**
     * Delta 조회 및 파싱
     */
    private DeltaDTO fetchAndParseDelta(String deltaKey, String opId) {
        try {
            String deltaJson = (String) redisTemplate.opsForHash().get(deltaKey, opId);
            if (deltaJson == null) {
                log.warn("Delta 데이터 없음. opId: {}", opId);
                return null;
            }
            return objectMapper.readValue(deltaJson, DeltaDTO.class);
        } catch (JsonProcessingException e) {
            log.error("Delta JSON 파싱 실패. opId: {}", opId, e);
            return null;
        }
    }

    /**
     * Delta 수집 결과
     */
    public record DeltaCollectionResult(
            Map<UUID, DeltaDTO> currentDeltas,
            Set<String> opIds,
            Set<String> tombstoneOpIds
    ) {}
}