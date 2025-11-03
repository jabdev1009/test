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

    public byte[] generateGLB(List<DeltaDTO> deltas, ChunkInfo chunkInfo) {
        // 청크의 기준 좌표
        float chunkBaseX = chunkInfo.x() * 256.0f;
        float chunkBaseY = chunkInfo.y() * 256.0f;
        float chunkBaseZ = chunkInfo.z() * 256.0f;

        // mesh를 만들기 위해 필요한 데이터
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

            // 색상 정규화 -> 현재 단일 색상을 기준으로 작성되어 있음.
            float r = (delta.colorBytes()[0] & 0xFF) / 255.0f;
            float g = (delta.colorBytes()[1] & 0xFF) / 255.0f;
            float b = (delta.colorBytes()[2] & 0xFF) / 255.0f;

            // 큐브 8개 정점 생성
            /*
                 7---------6
                /|        /|
               / |       / |
              4---------5  |
              |  3------|--2
              | /       | /
              |/        |/
              0---------1
             */
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
                    {5, 4, 7, 7, 6, 5}, // Back (수정)
                    {0, 4, 5, 5, 1, 0}, // Bottom (수정)
                    {3, 2, 6, 6, 7, 3}, // Top
                    {4, 0, 3, 3, 7, 4}, // Left (수정)
                    {1, 5, 6, 6, 2, 1}  // Right (수정)
            };

            for (int[] face : cubeFaces) {
                for (int idx : face) {
                    indices.add(vertexOffset + idx);
                }
            }
            // voxel의 정점 수 만큼
            vertexOffset += 8;
        }

        // glTF 모델 생성
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


    public byte[] generateGLBWithSeparateMeshes(List<DeltaDTO> deltas, ChunkInfo chunkInfo) {
        float chunkBaseX = chunkInfo.x() * 256.0f;
        float chunkBaseY = chunkInfo.y() * 256.0f;
        float chunkBaseZ = chunkInfo.z() * 256.0f;

        // glTF 기본 구조 생성
        GlTF gltf = new GlTF();
        gltf.setAsset(createAsset());

        Scene scene = new Scene();
        gltf.addScenes(scene);
        gltf.setScene(0);

        // 모든 voxel의 바이너리 데이터를 담을 리스트
        List<ByteBuffer> allBuffers = new ArrayList<>();

        int nodeIndex = 0;
        int meshIndex = 0;
        int accessorIndex = 0;
        int bufferViewIndex = 0;

        // 각 voxel마다 개별 처리
        for (DeltaDTO delta : deltas) {
            // 1. VoxelID 디코딩
            int localX = delta.voxelId() & 0xFF;
            int localY = (delta.voxelId() >> 8) & 0xFF;
            int localZ = (delta.voxelId() >> 16) & 0xFF;

            // 2. 월드 좌표 계산
            float worldX = chunkBaseX + localX * VOXEL_SIZE;
            float worldY = chunkBaseY + localY * VOXEL_SIZE;
            float worldZ = chunkBaseZ + localZ * VOXEL_SIZE;

            // 3. 색상 정규화
            float r = (delta.colorBytes()[0] & 0xFF) / 255.0f;
            float g = (delta.colorBytes()[1] & 0xFF) / 255.0f;
            float b = (delta.colorBytes()[2] & 0xFF) / 255.0f;

            // 4. 이 voxel의 정점 생성 (8개)
            List<Float> positions = new ArrayList<>();
            List<Float> colors = new ArrayList<>();

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

            // 5. 이 voxel의 인덱스 생성 (36개, offset=0부터 시작)
            List<Integer> indices = new ArrayList<>();
            int[][] cubeFaces = {
                    {0, 1, 2, 2, 3, 0}, // Front
                    {5, 4, 7, 7, 6, 5}, // Back (수정)
                    {0, 4, 5, 5, 1, 0}, // Bottom (수정)
                    {3, 2, 6, 6, 7, 3}, // Top
                    {4, 0, 3, 3, 7, 4}, // Left (수정)
                    {1, 5, 6, 6, 2, 1}  // Right (수정)
            };

            for (int[] face : cubeFaces) {
                for (int idx : face) {
                    indices.add(idx);
                }
            }

            // 6. 바이너리 버퍼 생성
            ByteBuffer positionsBuffer = createFloatBuffer(positions);
            ByteBuffer colorsBuffer = createFloatBuffer(colors);
            ByteBuffer indicesBuffer = createIntBuffer(indices);

            int positionsBytes = positionsBuffer.capacity();
            int colorsBytes = colorsBuffer.capacity();
            int indicesBytes = indicesBuffer.capacity();
            int totalBytes = positionsBytes + colorsBytes + indicesBytes;

            ByteBuffer combinedBuffer = ByteBuffer.allocate(totalBytes);
            combinedBuffer.order(ByteOrder.LITTLE_ENDIAN);
            combinedBuffer.put(positionsBuffer);
            combinedBuffer.put(colorsBuffer);
            combinedBuffer.put(indicesBuffer);
            combinedBuffer.flip();

            allBuffers.add(combinedBuffer);

            // 7. BufferViews 정의 (임시로 offset=0으로 설정, 나중에 수정)
            BufferView positionsBufferView = new BufferView();
            positionsBufferView.setBuffer(0); // 처음부터 buffer[0] 참조
            positionsBufferView.setByteOffset(0); // 나중에 수정됨
            positionsBufferView.setByteLength(positionsBytes);
            positionsBufferView.setTarget(34962);
            gltf.addBufferViews(positionsBufferView);

            BufferView colorsBufferView = new BufferView();
            colorsBufferView.setBuffer(0);
            colorsBufferView.setByteOffset(positionsBytes); // 나중에 수정됨
            colorsBufferView.setByteLength(colorsBytes);
            colorsBufferView.setTarget(34962);
            gltf.addBufferViews(colorsBufferView);

            BufferView indicesBufferView = new BufferView();
            indicesBufferView.setBuffer(0);
            indicesBufferView.setByteOffset(positionsBytes + colorsBytes); // 나중에 수정됨
            indicesBufferView.setByteLength(indicesBytes);
            indicesBufferView.setTarget(34963);
            gltf.addBufferViews(indicesBufferView);

            // 8. Accessors 정의
            Accessor positionsAccessor = new Accessor();
            positionsAccessor.setBufferView(bufferViewIndex);
            positionsAccessor.setComponentType(5126);
            positionsAccessor.setCount(8);
            positionsAccessor.setType("VEC3");
            gltf.addAccessors(positionsAccessor);

            Accessor colorsAccessor = new Accessor();
            colorsAccessor.setBufferView(bufferViewIndex + 1);
            colorsAccessor.setComponentType(5126);
            colorsAccessor.setCount(8);
            colorsAccessor.setType("VEC3");
            gltf.addAccessors(colorsAccessor);

            Accessor indicesAccessor = new Accessor();
            indicesAccessor.setBufferView(bufferViewIndex + 2);
            indicesAccessor.setComponentType(5125);
            indicesAccessor.setCount(36);
            indicesAccessor.setType("SCALAR");
            gltf.addAccessors(indicesAccessor);

            // 9. Mesh와 Primitive 생성
            MeshPrimitive primitive = new MeshPrimitive();
            primitive.setIndices(accessorIndex + 2);
            primitive.addAttributes("POSITION", accessorIndex);
            primitive.addAttributes("COLOR_0", accessorIndex + 1);

            Mesh mesh = new Mesh();
            mesh.setName("voxel_" + delta.opId().toString());
            mesh.addPrimitives(primitive);

            // 메타데이터를 extras에 저장
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("opId", delta.opId().toString());
            extras.put("voxelId", delta.voxelId());
            extras.put("faceMask", delta.faceMask());
            extras.put("actor", delta.actor());
            extras.put("policyTags", delta.policyTags());
            extras.put("timestamp", delta.timestamp().toString());
            mesh.setExtras(extras);

            gltf.addMeshes(mesh);

            // 10. Node 생성
            Node node = new Node();
            node.setMesh(meshIndex);
            node.setName("node_voxel_" + delta.opId().toString());
            gltf.addNodes(node);

            // 11. Scene에 node 추가
            scene.addNodes(nodeIndex);

            // 인덱스 증가
            nodeIndex++;
            meshIndex++;
            accessorIndex += 3;
            bufferViewIndex += 3;
        }

        // 12. 모든 버퍼를 하나의 큰 버퍼로 합치기
        int totalBufferSize = allBuffers.stream().mapToInt(ByteBuffer::capacity).sum();
        ByteBuffer combinedAllBuffers = ByteBuffer.allocate(totalBufferSize);
        combinedAllBuffers.order(ByteOrder.LITTLE_ENDIAN);

// 각 voxel의 버퍼를 순차적으로 추가하고 offset 업데이트
        int currentOffset = 0;
        for (int i = 0; i < allBuffers.size(); i++) {
            ByteBuffer voxelBuffer = allBuffers.get(i);
            voxelBuffer.rewind();
            combinedAllBuffers.put(voxelBuffer);

            // BufferView의 offset을 전역 offset으로 업데이트
            int bufferViewStartIndex = i * 3;
            for (int j = 0; j < 3; j++) {
                BufferView bv = gltf.getBufferViews().get(bufferViewStartIndex + j);
                int originalOffset = bv.getByteOffset();
                bv.setByteOffset(currentOffset + originalOffset);
            }

            currentOffset += voxelBuffer.capacity();
        }
        combinedAllBuffers.flip();

// 13. 모든 기존 Buffer를 단일 Buffer로 교체
        Buffer singleBuffer = new Buffer();
        singleBuffer.setByteLength(totalBufferSize);

// 새로운 리스트를 만들어 단일 buffer만 포함
        List<Buffer> newBuffers = new ArrayList<>();
        newBuffers.add(singleBuffer);
        gltf.setBuffers(newBuffers);

// 14. GltfAssetV2 생성
        GltfAssetV2 gltfAsset = new GltfAssetV2(gltf, combinedAllBuffers);

// 15. GLB 출력
        GltfModel gltfModel = GltfModels.create(gltfAsset);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        GltfModelWriter writer = new GltfModelWriter();
        try {
            writer.writeBinary(gltfModel, outputStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return outputStream.toByteArray();
    }


}