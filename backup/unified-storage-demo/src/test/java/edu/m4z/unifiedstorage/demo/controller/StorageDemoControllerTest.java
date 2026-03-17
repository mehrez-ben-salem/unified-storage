package edu.m4z.unifiedstorage.demo.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the demo controller.
 * Profile "test": local storage only (file://) + H2 in-memory database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StorageDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static final byte[] TEST_CONTENT = "Test content — UnifiedStorage demo".getBytes();

    // ----------------------------------------------------------------
    // Scenario 5 — Local (always available without cloud credentials)
    // ----------------------------------------------------------------

    @Test
    @DisplayName("Scenario 5 — Write then read local file (file://)")
    void writeAndReadLocal() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", TEST_CONTENT);

        // Write
        mockMvc.perform(multipart("/api/storage/local/test.txt").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("written"))
                .andExpect(jsonPath("$.provider").value("local"))
                .andExpect(jsonPath("$.filename").value("test.txt"));

        // Read back
        mockMvc.perform(get("/api/storage/local/test.txt"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(TEST_CONTENT));
    }

    @Test
    @DisplayName("Scenario 4 — Local directory listing")
    void listLocal() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "list-test.txt", "text/plain", TEST_CONTENT);
        mockMvc.perform(multipart("/api/storage/local/list-test.txt").file(file))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/storage/local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("local"))
                .andExpect(jsonPath("$.count").isNumber());
    }

    @Test
    @DisplayName("404 — File not found")
    void fileNotFound() throws Exception {
        mockMvc.perform(get("/api/storage/local/nonexistent.txt"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("File not found"));
    }
}
