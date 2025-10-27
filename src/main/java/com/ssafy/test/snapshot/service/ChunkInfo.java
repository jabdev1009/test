package com.ssafy.test.snapshot.service;

import com.ssafy.test.global.exception.CustomException;

/**
 * 청크 정보 (Redis 키 파싱)
 */
public record ChunkInfo(String worldName, int lod, int x, int y, int z) {

    /**
     * Redis 키에서 ChunkInfo 생성
     * 형식: op_ids:{world:"worldname"}:l0:x123:y456:z789
     */
    public static ChunkInfo fromKey(String chunkKey) {
        try {
            String worldName = extractWorldName(chunkKey);
            int lod = extractInt(chunkKey, ":l");
            int x = extractInt(chunkKey, ":x");
            int y = extractInt(chunkKey, ":y");
            int z = extractInt(chunkKey, ":z");

            return new ChunkInfo(worldName, lod, x, y, z);
        } catch (Exception e) {
//            throw new CustomException("청크 키 파싱 실패: " + chunkKey, e);
        }
        return null;
    }

    private static String extractWorldName(String key) {
        int start = key.indexOf("{world:\"") + 8;
        int end = key.indexOf("\"}", start);
        if (start < 8 || end < 0) {
//            throw new CustomException("worldname 파싱 실패: " + key);
        }
        return key.substring(start, end);
    }

    private static int extractInt(String key, String prefix) {
        int start = key.indexOf(prefix);
        if (start < 0) {
//            throw new CustomException(prefix + " 값 파싱 실패: " + key);
        }

        start += prefix.length();
        int end = key.indexOf(":", start);
        if (end < 0) end = key.length();

        String value = key.substring(start, end);
        // 음수 처리 (x-123 형태)
        return Integer.parseInt(value.replace("x", "").replace("y", "").replace("z", ""));
    }
}