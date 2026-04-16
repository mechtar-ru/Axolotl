package com.agent.orchestrator.controller;

import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.SchemaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SchemaControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SchemaRepository schemaRepository;

    @BeforeEach
    void setUp() {
        schemaRepository.findAll().forEach(s -> schemaRepository.delete(s.getId()));
    }

    @Test
    void shouldCreateAndRetrieveSchema() throws Exception {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setName("Test Schema");
        schema.setDescription("Integration test");

        String json = objectMapper.writeValueAsString(schema);

        MvcResult result = mockMvc.perform(post("/api/schemas")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test Schema"))
                .andReturn();

        WorkflowSchema created = objectMapper.readValue(
                result.getResponse().getContentAsString(), WorkflowSchema.class);

        assertNotNull(created.getId());

        mockMvc.perform(get("/api/schemas/{id}", created.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test Schema"));
    }

    @Test
    void shouldListSchemas() throws Exception {
        WorkflowSchema schema1 = new WorkflowSchema();
        schema1.setName("Schema 1");
        schemaRepository.save(schema1);

        WorkflowSchema schema2 = new WorkflowSchema();
        schema2.setName("Schema 2");
        schemaRepository.save(schema2);

        mockMvc.perform(get("/api/schemas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void shouldDeleteSchema() throws Exception {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setName("To Delete");
        schemaRepository.save(schema);

        String id = schema.getId();

        mockMvc.perform(delete("/api/schemas/{id}", id))
                .andExpect(status().isOk());

        assertNull(schemaRepository.findById(id));
    }

    @Test
    void shouldExportToMermaid() throws Exception {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setName("Mermaid Test");
        schemaRepository.save(schema);

        mockMvc.perform(get("/api/schemas/{id}/export/mermaid", schema.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mermaid").exists());
    }

    @Test
    void shouldReturnHealthStatus() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
