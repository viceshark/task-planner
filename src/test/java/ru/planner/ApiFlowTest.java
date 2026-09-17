package ru.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class ApiFlowTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void employeeTaskMoveAndAnalyticsFlow() throws Exception {
        long anna = createEmployee("Анна", "#00e5ff");
        long boris = createEmployee("Борис", "#ff2fd6");

        // задача с релизом и оценкой
        JsonNode task = postJson("/api/tasks",
            "{\"employeeId\":" + anna + ",\"day\":\"2026-09-15\",\"title\":\"PROJ-1\\nописание\",\"release\":\"2.14\",\"estimate\":4.5}",
            201);
        long taskId = task.get("id").asLong();
        assertThat(task.get("release").asText()).isEqualTo("2.14");
        assertThat(task.get("estimate").asDouble()).isEqualTo(4.5);

        postJson("/api/tasks",
            "{\"employeeId\":" + anna + ",\"day\":\"2026-09-15\",\"title\":\"PROJ-2\",\"release\":\"2.14\",\"estimate\":5}",
            201);

        // список за период
        mvc.perform(get("/api/tasks").param("from", "2026-09-14").param("to", "2026-09-16"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));

        // перенос задачи другому сотруднику на другой день: релиз и оценка сохраняются
        JsonNode moved = json.readTree(mvc.perform(patch("/api/tasks/" + taskId + "/move").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeId\":" + boris + ",\"day\":\"2026-09-17\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(moved.get("employeeId").asLong()).isEqualTo(boris);
        assertThat(moved.get("day").asText()).isEqualTo("2026-09-17");
        assertThat(moved.get("release").asText()).isEqualTo("2.14");
        assertThat(moved.get("estimate").asDouble()).isEqualTo(4.5);

        // аналитика: 9.5 часов, 2 задачи, 1 релиз, занятость по сотрудникам
        JsonNode analytics = json.readTree(mvc.perform(get("/api/analytics")
                .param("from", "2026-09-14").param("to", "2026-09-18"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(analytics.get("workdays").asInt()).isEqualTo(5);
        assertThat(analytics.get("totals").get("hours").asDouble()).isEqualTo(9.5);
        assertThat(analytics.get("totals").get("tasks").asInt()).isEqualTo(2);
        assertThat(analytics.get("totals").get("releases").asInt()).isEqualTo(1);
        assertThat(findEmployee(analytics.get("employees"), anna).get("hours").asDouble()).isEqualTo(5.0);
        assertThat(findEmployee(analytics.get("employees"), boris).get("hours").asDouble()).isEqualTo(4.5);
        assertThat(analytics.get("releases").get(0).get("release").asText()).isEqualTo("2.14");

        // порядок сотрудников
        mvc.perform(put("/api/employees/order").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + boris + "," + anna + "]}"))
            .andExpect(status().isOk());
        JsonNode list = json.readTree(mvc.perform(get("/api/employees")).andReturn().getResponse().getContentAsString());
        assertThat(indexOf(list, boris)).isLessThan(indexOf(list, anna));

        // удаление сотрудника удаляет его задачи
        mvc.perform(delete("/api/employees/" + boris).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/tasks").param("from", "2026-09-17").param("to", "2026-09-17"))
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void validationErrorsAreReportedAsJson() throws Exception {
        mvc.perform(post("/api/employees").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"color\":\"red\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.name").exists())
            .andExpect(jsonPath("$.errors.color").exists());

        mvc.perform(post("/api/tasks").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeId\":999999,\"day\":\"2026-09-15\",\"title\":\"x\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").exists());

        mvc.perform(get("/api/tasks").param("from", "2026-09-20").param("to", "2026-09-10"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void mutationsRequireCsrf() throws Exception {
        mvc.perform(post("/api/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"color\":\"#ffffff\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void settingsRoundTrip() throws Exception {
        mvc.perform(put("/api/settings").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jiraBaseUrl\":\"https://jira.example.com///\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jiraBaseUrl").value("https://jira.example.com"));
        mvc.perform(get("/api/settings"))
            .andExpect(jsonPath("$.jiraBaseUrl").value("https://jira.example.com"));
    }

    private long createEmployee(String name, String color) throws Exception {
        return postJson("/api/employees", "{\"name\":\"" + name + "\",\"color\":\"" + color + "\"}", 201)
            .get("id").asLong();
    }

    private JsonNode postJson(String url, String body, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post(url).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().is(expectedStatus))
            .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode findEmployee(JsonNode list, long id) {
        for (JsonNode n : list) {
            if (n.get("id").asLong() == id) {
                return n;
            }
        }
        throw new AssertionError("Сотрудник " + id + " не найден в аналитике");
    }

    private static int indexOf(JsonNode list, long id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).get("id").asLong() == id) {
                return i;
            }
        }
        return -1;
    }
}
