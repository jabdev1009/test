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
    private static final float VOXEL_SIZE = 1.0f; // voxel 단위 크기 (1m 혹은 상대적 단위)

    /**
     * 상대좌표 기반 GLB 생성
     * 각 DeltaDTO는 청크 내 상대 위치(voxel index)를 기반으로 변환
     */
    public byte[] generateGLBWithSeparateMeshes(List<DeltaDTO> deltas) {
        GlTF gltf = new GlTF();
        gltf.setAsset(createAsset());

        Scene scene = new Scene();
        gltf.addScenes(scene);
        gltf.setScene(0);

        List<ByteBuffer> allBuffers = new ArrayList<>();

        int nodeIndex = 0;
        int meshIndex = 0;
        int accessorIndex = 0;
        int bufferViewIndex = 0;

        for (DeltaDTO delta : deltas) {

            // ✅ [변경됨] — voxelId에서 local index 추출 (청크 내 상대 좌표)
            int localX = (delta.voxelId() >> 16) & 0xFF;
            int localY = (delta.voxelId() >> 8) & 0xFF;
            int localZ = delta.voxelId() & 0xFF;

            // ✅ [변경됨] — 절대좌표계 개념 제거, 청크 내 상대 좌표만 사용
            float relativeX = localX * VOXEL_SIZE;
            float relativeY = localY * VOXEL_SIZE;
            float relativeZ = localZ * VOXEL_SIZE;

            // 색상 정규화
            float r = (delta.colorBytes()[0] & 0xFF) / 255.0f;
            float g = (delta.colorBytes()[1] & 0xFF) / 255.0f;
            float b = (delta.colorBytes()[2] & 0xFF) / 255.0f;

            // ✅ [변경됨] — 명시적으로 “상대좌표 기준” cubeVertices 변수명 변경
            float[][] relativeCubeVertices = {
                    {relativeX, relativeY, relativeZ},
                    {relativeX + VOXEL_SIZE, relativeY, relativeZ},
                    {relativeX + VOXEL_SIZE, relativeY + VOXEL_SIZE, relativeZ},
                    {relativeX, relativeY + VOXEL_SIZE, relativeZ},
                    {relativeX, relativeY, relativeZ + VOXEL_SIZE},
                    {relativeX + VOXEL_SIZE, relativeY, relativeZ + VOXEL_SIZE},
                    {relativeX + VOXEL_SIZE, relativeY + VOXEL_SIZE, relativeZ + VOXEL_SIZE},
                    {relativeX, relativeY + VOXEL_SIZE, relativeZ + VOXEL_SIZE}
            };

            List<Float> positions = new ArrayList<>();
            List<Float> colors = new ArrayList<>();

            for (float[] vertex : relativeCubeVertices) {
                positions.add(vertex[0]);
                positions.add(vertex[1]);
                positions.add(vertex[2]);
                colors.add(r);
                colors.add(g);
                colors.add(b);
            }

            List<Integer> indices = new ArrayList<>();
            int[][] cubeFaces = {
                    {0, 1, 2, 2, 3, 0}, // Front
                    {5, 4, 7, 7, 6, 5}, // Back
                    {0, 4, 5, 5, 1, 0}, // Bottom
                    {3, 2, 6, 6, 7, 3}, // Top
                    {4, 0, 3, 3, 7, 4}, // Left
                    {1, 5, 6, 6, 2, 1}  // Right
            };
            for (int[] face : cubeFaces) for (int idx : face) indices.add(idx);

            // 버퍼 생성
            ByteBuffer positionsBuffer = createFloatBuffer(positions);
            ByteBuffer colorsBuffer = createFloatBuffer(colors);
            ByteBuffer indicesBuffer = createIntBuffer(indices);

            // ✅ [변경됨] — 단일 voxel 단위 버퍼만 생성 후 전체 합치는 방식 유지
            int positionsBytes = positionsBuffer.capacity();
            int colorsBytes = colorsBuffer.capacity();
            int indicesBytes = indicesBuffer.capacity();

            ByteBuffer combinedBuffer = ByteBuffer.allocate(positionsBytes + colorsBytes + indicesBytes)
                    .order(ByteOrder.LITTLE_ENDIAN);
            combinedBuffer.put(positionsBuffer);
            combinedBuffer.put(colorsBuffer);
            combinedBuffer.put(indicesBuffer);
            combinedBuffer.flip();

            allBuffers.add(combinedBuffer);

            // BufferView 및 Accessor는 기존과 동일 (상대좌표 기반이라 변경 없음)
            gltf.addBufferViews(createBufferView(0, 0, positionsBytes, 34962));
            gltf.addBufferViews(createBufferView(0, positionsBytes, colorsBytes, 34962));
            gltf.addBufferViews(createBufferView(0, positionsBytes + colorsBytes, indicesBytes, 34963));

            gltf.addAccessors(createAccessor(bufferViewIndex, 5126, 8, "VEC3"));     // positions
            gltf.addAccessors(createAccessor(bufferViewIndex + 1, 5126, 8, "VEC3")); // colors
            gltf.addAccessors(createAccessor(bufferViewIndex + 2, 5125, 36, "SCALAR")); // indices

            MeshPrimitive primitive = new MeshPrimitive();
            primitive.setIndices(accessorIndex + 2);
            primitive.addAttributes("POSITION", accessorIndex);
            primitive.addAttributes("COLOR_0", accessorIndex + 1);

            // 재질 설정
            Material material = new Material();
            material.setDoubleSided(true);
            gltf.addMaterials(material);
            primitive.setMaterial(gltf.getMaterials().size() - 1);

            Mesh mesh = new Mesh();
            mesh.setName("voxel_" + delta.opId());
            mesh.addPrimitives(primitive);
            gltf.addMeshes(mesh);

            Node node = new Node();
            node.setMesh(meshIndex);
            node.setName("node_voxel_" + delta.opId());
            gltf.addNodes(node);
            scene.addNodes(nodeIndex);

            nodeIndex++;
            meshIndex++;
            accessorIndex += 3;
            bufferViewIndex += 3;
        }

        // ✅ [유지됨] — 버퍼 통합 및 오프셋 조정
        ByteBuffer combinedAllBuffers = mergeAllBuffers(allBuffers, gltf);

        Buffer singleBuffer = new Buffer();
        singleBuffer.setByteLength(combinedAllBuffers.capacity());
        gltf.setBuffers(List.of(singleBuffer));

        GltfAssetV2 assetV2 = new GltfAssetV2(gltf, combinedAllBuffers);
        GltfModel gltfModel = GltfModels.create(assetV2);

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            new GltfModelWriter().writeBinary(gltfModel, outputStream);
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ================== Utility methods ==================

    private Asset createAsset() {
        Asset asset = new Asset();
        asset.setVersion("2.0");
        asset.setGenerator("RelativeVoxelGLBGenerator");
        return asset;
    }

    private BufferView createBufferView(int buffer, int offset, int length, int target) {
        BufferView bv = new BufferView();
        bv.setBuffer(buffer);
        bv.setByteOffset(offset);
        bv.setByteLength(length);
        bv.setTarget(target);
        return bv;
    }

    private Accessor createAccessor(int bufferView, int componentType, int count, String type) {
        Accessor acc = new Accessor();
        acc.setBufferView(bufferView);
        acc.setComponentType(componentType);
        acc.setCount(count);
        acc.setType(type);
        return acc;
    }

    private ByteBuffer mergeAllBuffers(List<ByteBuffer> allBuffers, GlTF gltf) {
        int totalSize = allBuffers.stream().mapToInt(ByteBuffer::capacity).sum();
        ByteBuffer merged = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);

        int offset = 0;
        for (int i = 0; i < allBuffers.size(); i++) {
            ByteBuffer buf = allBuffers.get(i);
            buf.rewind();
            merged.put(buf);

            for (int j = 0; j < 3; j++) {
                BufferView bv = gltf.getBufferViews().get(i * 3 + j);
                bv.setByteOffset(offset + bv.getByteOffset());
            }
            offset += buf.capacity();
        }
        merged.flip();
        return merged;
    }

    private ByteBuffer createFloatBuffer(List<Float> data) {
        ByteBuffer buffer = ByteBuffer.allocate(data.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
        data.forEach(buffer::putFloat);
        buffer.flip();
        return buffer;
    }

    private ByteBuffer createIntBuffer(List<Integer> data) {
        ByteBuffer buffer = ByteBuffer.allocate(data.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
        data.forEach(buffer::putInt);
        buffer.flip();
        return buffer;
    }
}
