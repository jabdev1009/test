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
            // TODO: CustomException
            return null;
        }
    }

    private static String extractWorldName(String key) {
        int start = key.indexOf("{world:") + 7;
        int end = key.indexOf("}", start);
        if (start < 7 || end < 0) {
            // TODO: CustomException
            return null;
        }
        return key.substring(start, end);
    }

    private static int extractInt(String key, String prefix) {
        int start = key.indexOf(prefix);
        if (start < 0) {
            // TODO: CustomException
            return 0;
        }
        start += prefix.length();
        int end = key.indexOf(":", start);
        if (end < 0) end = key.length();
        return Integer.parseInt(key.substring(start, end));
    }
}