package com.ssafy.test.snapshot.service;

import com.ssafy.test.snapshot.dto.DeltaDTO;
import de.javagl.jgltf.impl.v2.*;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.GltfModels;
import de.javagl.jgltf.model.io.GltfModelWriter;
import de.javagl.jgltf.model.io.v2.GltfAssetV2;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

@Service
@RequiredArgsConstructor
public class GLBGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(GLBGeneratorService.class);

    // 복셀 하나의 크기 (월드 좌표계 단위)
    private static final float VOXEL_SIZE = 1.0f;

    // GLB 포맷 상수
    private static final int GLB_MAGIC = 0x46546C67;      // "glTF" in little-endian
    private static final int GLB_VERSION = 2;
    private static final int CHUNK_TYPE_JSON = 0x4E4F534A; // "JSON"
    private static final int CHUNK_TYPE_BIN = 0x004E4942;  // "BIN\0"

    // 복셀의 각 면을 나타내는 비트 마스크 (face culling에 사용)
    private static final int FACE_FRONT  = 0b000001;  // +Z 방향
    private static final int FACE_BACK   = 0b000010;  // -Z 방향
    private static final int FACE_RIGHT  = 0b000100;  // +X 방향
    private static final int FACE_LEFT   = 0b001000;  // -X 방향
    private static final int FACE_TOP    = 0b010000;  // +Y 방향
    private static final int FACE_BOTTOM = 0b100000;  // -Y 방향

    public byte[] generateGLB(List<DeltaDTO> deltas, ChunkInfo chunkInfo) {
        // 1. 청크의 기준 좌표 계산
        float chunkBaseX = chunkInfo.x() * 256.0f;
        float chunkBaseY = chunkInfo.y() * 256.0f;
        float chunkBaseZ = chunkInfo.z() * 256.0f;

        // 2. Geometry 데이터 생성
        List<Float> positions = new ArrayList<>();
        List<Float> colors = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();

        int vertexOffset = 0;

        for (DeltaDTO delta : deltas) {
            // VoxelID 디코딩
            int localX = delta.voxelId() & 0xFF;
            int localY = (delta.voxelId() >> 8) & 0xFF;
            int localZ = (delta.voxelId() >> 16) & 0xFF;

            // 월드 좌표 계산
            float worldX = chunkBaseX + localX * VOXEL_SIZE;
            float worldY = chunkBaseY + localY * VOXEL_SIZE;
            float worldZ = chunkBaseZ + localZ * VOXEL_SIZE;

            // 색상 정규화
            float r = (delta.colorBytes()[0] & 0xFF) / 255.0f;
            float g = (delta.colorBytes()[1] & 0xFF) / 255.0f;
            float b = (delta.colorBytes()[2] & 0xFF) / 255.0f;

            // 큐브 8개 정점 생성
            float[][] cubeVertices = {
                    {worldX, worldY, worldZ},
                    {worldX + VOXEL_SIZE, worldY, worldZ},
                    {worldX + VOXEL_SIZE, worldY + VOXEL_SIZE, worldZ},
                    {worldX, worldY + VOXEL_SIZE, worldZ},
                    {worldX, worldY, worldZ + VOXEL_SIZE},
                    {worldX + VOXEL_SIZE, worldY, worldZ + VOXEL_SIZE},
                    {worldX + VOXEL_SIZE, worldY + VOXEL_SIZE, worldZ + VOXEL_SIZE},
                    {worldX, worldY + VOXEL_SIZE, worldZ + VOXEL_SIZE}
            };

            for (float[] vertex : cubeVertices) {
                positions.add(vertex[0]);
                positions.add(vertex[1]);
                positions.add(vertex[2]);
                colors.add(r);
                colors.add(g);
                colors.add(b);
            }

            // 큐브 인덱스 (6면 × 2삼각형 × 3정점 = 36개)
            int[][] cubeFaces = {
                    {0, 1, 2, 2, 3, 0}, // Front
                    {4, 5, 6, 6, 7, 4}, // Back
                    {0, 1, 5, 5, 4, 0}, // Bottom
                    {3, 2, 6, 6, 7, 3}, // Top
                    {0, 3, 7, 7, 4, 0}, // Left
                    {1, 2, 6, 6, 5, 1}  // Right
            };

            for (int[] face : cubeFaces) {
                for (int idx : face) {
                    indices.add(vertexOffset + idx);
                }
            }

            vertexOffset += 8;
        }

        // 3. jgltf를 사용한 glTF 모델 생성
        GlTF gltf = new GlTF();
        gltf.setAsset(createAsset());

        // Scene 생성
        Scene scene = new Scene();
        scene.addNodes(0);
        gltf.addScenes(scene);
        gltf.setScene(0);

        // Node 생성
        Node node = new Node();
        node.setMesh(0);
        gltf.addNodes(node);

        // Buffer 생성
        int vertexCount = positions.size() / 3;
        int indexCount = indices.size();

        ByteBuffer positionsBuffer = createFloatBuffer(positions);
        ByteBuffer colorsBuffer = createFloatBuffer(colors);
        ByteBuffer indicesBuffer = createIntBuffer(indices);

        int positionsBytes = positionsBuffer.capacity();
        int colorsBytes = colorsBuffer.capacity();
        int indicesBytes = indicesBuffer.capacity();
        int totalBytes = positionsBytes + colorsBytes + indicesBytes;

        // 하나의 통합 버퍼 생성
        ByteBuffer combinedBuffer = ByteBuffer.allocate(totalBytes);
        combinedBuffer.order(ByteOrder.LITTLE_ENDIAN);
        combinedBuffer.put(positionsBuffer);
        combinedBuffer.put(colorsBuffer);
        combinedBuffer.put(indicesBuffer);
        combinedBuffer.flip();

        Buffer buffer = new Buffer();
        buffer.setByteLength(totalBytes);
        gltf.addBuffers(buffer);

        // BufferViews 생성
        BufferView positionsBufferView = new BufferView();
        positionsBufferView.setBuffer(0);
        positionsBufferView.setByteOffset(0);
        positionsBufferView.setByteLength(positionsBytes);
        positionsBufferView.setTarget(34962); // ARRAY_BUFFER
        gltf.addBufferViews(positionsBufferView);

        BufferView colorsBufferView = new BufferView();
        colorsBufferView.setBuffer(0);
        colorsBufferView.setByteOffset(positionsBytes);
        colorsBufferView.setByteLength(colorsBytes);
        colorsBufferView.setTarget(34962); // ARRAY_BUFFER
        gltf.addBufferViews(colorsBufferView);

        BufferView indicesBufferView = new BufferView();
        indicesBufferView.setBuffer(0);
        indicesBufferView.setByteOffset(positionsBytes + colorsBytes);
        indicesBufferView.setByteLength(indicesBytes);
        indicesBufferView.setTarget(34963); // ELEMENT_ARRAY_BUFFER
        gltf.addBufferViews(indicesBufferView);

        // Accessors 생성
        Accessor positionsAccessor = new Accessor();
        positionsAccessor.setBufferView(0);
        positionsAccessor.setComponentType(5126); // FLOAT
        positionsAccessor.setCount(vertexCount);
        positionsAccessor.setType("VEC3");
        gltf.addAccessors(positionsAccessor);

        Accessor colorsAccessor = new Accessor();
        colorsAccessor.setBufferView(1);
        colorsAccessor.setComponentType(5126); // FLOAT
        colorsAccessor.setCount(vertexCount);
        colorsAccessor.setType("VEC3");
        gltf.addAccessors(colorsAccessor);

        Accessor indicesAccessor = new Accessor();
        indicesAccessor.setBufferView(2);
        indicesAccessor.setComponentType(5125); // UNSIGNED_INT
        indicesAccessor.setCount(indexCount);
        indicesAccessor.setType("SCALAR");
        gltf.addAccessors(indicesAccessor);

        // Mesh와 Primitive 생성
        MeshPrimitive primitive = new MeshPrimitive();
        primitive.setIndices(2);
        primitive.addAttributes("POSITION", 0);
        primitive.addAttributes("COLOR_0", 1);

        Mesh mesh = new Mesh();
        mesh.addPrimitives(primitive);
        gltf.addMeshes(mesh);

        // GlTF를 GltfAsset으로 변환
        GltfAssetV2 gltfAsset = new GltfAssetV2(gltf, combinedBuffer);

        // GltfModel 생성
        GltfModel gltfModel = GltfModels.create(gltfAsset);

        // GLB로 변환
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        GltfModelWriter writer = new GltfModelWriter();
        try {
            writer.writeBinary(gltfModel, outputStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return outputStream.toByteArray();
    }

    private Asset createAsset() {
        Asset asset = new Asset();
        asset.setVersion("2.0");
        asset.setGenerator("CustomVoxelConverter");
        return asset;
    }

    private ByteBuffer createFloatBuffer(List<Float> data) {
        ByteBuffer buffer = ByteBuffer.allocate(data.size() * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (Float value : data) {
            buffer.putFloat(value);
        }
        buffer.flip();
        return buffer;
    }

    private ByteBuffer createIntBuffer(List<Integer> data) {
        ByteBuffer buffer = ByteBuffer.allocate(data.size() * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (Integer value : data) {
            buffer.putInt(value);
        }
        buffer.flip();
        return buffer;
    }
}