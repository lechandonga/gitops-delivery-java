package com.github.lechandonga.gitopsdeliveryjava.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "delivery.data-dir=target/delivery-api-test",
        "delivery.auto-reconcile-on-startup=false",
        "delivery.max-concurrency=2",
        "delivery.max-queue=16"
})
@AutoConfigureMockMvc
class DeliveryApiTest {

    @BeforeAll
    static void clean() throws IOException {
        Path dir = Path.of("target/delivery-api-test");
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
            }
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void unknownManifestFieldIsRejectedWithReason() throws Exception {
        String json = """
                { "name": "app", "bogus": 1, "stages": [
                    { "id": "s1", "env": "dev", "desiredVersion": "v1" } ] }
                """;
        mvc.perform(post("/api/manifest").content(json).contentType("application/json"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("GATE_UNSUPPORTED_OPTION"));
    }

    @Test
    void protectedEnvBlockedThenApprovedThenConverged() throws Exception {
        String json = """
                { "name": "protected-app", "protectedEnv": true, "requireApproval": true,
                  "stages": [ { "id": "p1", "env": "prod-a", "desiredVersion": "v9" } ] }
                """;
        // 未审批直接提交即触发收敛 -> 422? 不：applyManifest 不校验门禁，收敛内拒绝(200+converged=false)
        mvc.perform(post("/api/manifest").content(json).contentType("application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.converged").value(false))
                .andExpect(jsonPath("$.eventReasons[0]").value("p1:GATE_PROTECTED_NO_APPROVAL"));

        // 已发布版本不变
        mvc.perform(get("/api/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stages[0].appliedVersion").doesNotExist());

        // 审批后收敛成功
        mvc.perform(post("/api/approve").param("env", "prod-a"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/converge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.converged").value(true));
        mvc.perform(get("/api/state"))
                .andExpect(jsonPath("$.envs[0].currentVersion").value("v9"))
                .andExpect(jsonPath("$.envs[0].approved").value(true));

        // 事件流可追溯
        mvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.reasonCode=='GATE_PROTECTED_NO_APPROVAL')]")
                        .isNotEmpty());
    }

    @Test
    void unauthorizedTriggerRejectedWithDistinctCode() throws Exception {
        mvc.perform(post("/api/converge").param("trigger", "intruder"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("GATE_UNAUTHORIZED_TRIGGER"));
    }
}
