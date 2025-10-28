package com.ssafy.test.snapshot.service;

public record ChunkInfo(String worldName, int lod, int x, int y, int z) {

    public static ChunkInfo fromKey(String chunkKey) {
        try {
            String worldName = extractWorldName(chunkKey);
            int lod = extractInt(chunkKey, ":l");
            int x = extractInt(chunkKey, ":x");
            int y = extractInt(chunkKey, ":y");
            int z = extractInt(chunkKey, ":z");
            return new ChunkInfo(worldName, lod, x, y, z);
        } catch (Exception e) {
            throw new IllegalArgumentException("잘못된 chunkKey: " + chunkKey, e);
        }
    }

    private static String extractWorldName(String key) {
        int start = key.indexOf("{world:") + 7;
        int end = key.indexOf("}", start);
        if (start < 7 || end < 0) {
            throw new IllegalArgumentException("WorldName 추출 실패: " + key);
        }
        return key.substring(start, end);
    }

    private static int extractInt(String key, String prefix) {
        int start = key.indexOf(prefix);
        if (start < 0) {
            throw new IllegalArgumentException("Prefix '" + prefix + "' 없음: " + key);
        }
        start += prefix.length();
        int end = key.indexOf(":", start);
        if (end < 0) end = key.length();
        return Integer.parseInt(key.substring(start, end));
    }
}