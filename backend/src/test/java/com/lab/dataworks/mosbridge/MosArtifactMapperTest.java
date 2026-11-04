package com.lab.dataworks.mosbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-FW-2 纯判定单测：形态映射（契约 §4.2/§2.2）、dataset_ref、sha256、退避、终态集。
 *
 * 跑法：{@code mvn -q -Dtest=MosArtifactMapperTest test}
 */
class MosArtifactMapperTest {

    @Test
    void artifactTypeFromMethod() {
        assertEquals("merged", MosArtifactMapper.artifactType("export"));
        assertEquals("merged", MosArtifactMapper.artifactType("Export"));
        assertEquals("awq", MosArtifactMapper.artifactType("quant"));
        assertEquals("adapter", MosArtifactMapper.artifactType("sft"));
        assertEquals("adapter", MosArtifactMapper.artifactType(null));
        assertEquals("adapter", MosArtifactMapper.artifactType(""));
    }

    @Test
    void datasetRefNeverFabricatesNullPrefix() {
        assertEquals("ds_9@v3", MosArtifactMapper.datasetRef("ds_9", "v3"));
        assertEquals("ds_9@", MosArtifactMapper.datasetRef("ds_9", null));
        assertNull(MosArtifactMapper.datasetRef(null, "v3"));
        assertNull(MosArtifactMapper.datasetRef("", "v3"));
        assertEquals("ds_9@", MosArtifactMapper.datasetRef("ds_9", "  "));
    }

    @Test
    void sha256KnownVectors() {
        // echo -n "" | sha256sum
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                MosArtifactMapper.sha256Hex(""));
        // echo -n "abc" | sha256sum
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                MosArtifactMapper.sha256Hex("abc"));
    }

    @Test
    void backoffDoublesAndCaps() {
        assertEquals(30, MosArtifactMapper.backoffSeconds(1, 30));
        assertEquals(60, MosArtifactMapper.backoffSeconds(2, 30));
        assertEquals(120, MosArtifactMapper.backoffSeconds(3, 30));
        assertEquals(900, MosArtifactMapper.backoffSeconds(6, 30));
        assertEquals(900, MosArtifactMapper.backoffSeconds(50, 30)); // 封顶不溢出
        assertEquals(30, MosArtifactMapper.backoffSeconds(0, 30));   // attempts 从 0 也按 1 档
    }

    @Test
    void terminalSetsMatchMosStateMachines() {
        // job：jobs.py:783-794 / runner.py:474-475
        assertTrue(MosArtifactMapper.isTerminal("job", "finished"));
        assertTrue(MosArtifactMapper.isTerminal("job", "failed"));
        assertTrue(MosArtifactMapper.isTerminal("job", "stopped"));
        assertFalse(MosArtifactMapper.isTerminal("job", "running"));
        assertFalse(MosArtifactMapper.isTerminal("job", "queued"));
        assertFalse(MosArtifactMapper.isTerminal("job", "unknown"));
        // eval：services/eval.py:163-176
        assertTrue(MosArtifactMapper.isTerminal("eval", "done"));
        assertTrue(MosArtifactMapper.isTerminal("eval", "failed"));
        assertFalse(MosArtifactMapper.isTerminal("eval", "running"));
    }
}
