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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Пролонгация задач, овертайм, простои/аренда/отпуск и их отражение в аналитике. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class PlanningFlowTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void spannedTaskWithOvertimeIsSpreadAcrossWorkdays() throws Exception {
        long emp = createEmployee("Олег");

        // 20ч + 4ч овертайма на 3 рабочих дня начиная с четверга: Чт 17, Пт 18, Пн 21
        JsonNode task = postJson("/api/tasks", "{\"employeeId\":" + emp + ",\"day\":\"2026-09-17\",\"title\":\"BIG-1\","
            + "\"release\":\"3.0\",\"estimate\":20,\"days\":3,\"overtime\":4}", 201);
        assertThat(task.get("days").asInt()).isEqualTo(3);
        assertThat(task.get("overtime").asDouble()).isEqualTo(4.0);

        // задача видна в периоде, куда попадает только её последний день
        mvc.perform(get("/api/tasks").param("from", "2026-09-21").param("to", "2026-09-21"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].day").value("2026-09-17"));
        // и не видна в выходные между днями
        mvc.perform(get("/api/tasks").param("from", "2026-09-19").param("to", "2026-09-20"))
            .andExpect(jsonPath("$.length()").value(0));

        JsonNode a = analytics("2026-09-14", "2026-09-25");
        JsonNode stat = employee(a, emp);
        assertThat(stat.get("hours").asDouble()).isEqualTo(24.0);
        assertThat(stat.get("overtime").asDouble()).isEqualTo(4.0);
        assertThat(stat.get("maxDayHours").asDouble()).isEqualTo(8.0);
        assertThat(a.get("totals").get("overtime").asDouble()).isEqualTo(4.0);
        assertThat(a.get("totals").get("overtimeEmployees").asInt()).isEqualTo(1);

        // готовность релиза 3.0 — следующий день после последнего дня задачи (Пн 21 → Вт 22)
        JsonNode release = a.get("releases").get(0);
        assertThat(release.get("release").asText()).isEqualTo("3.0");
        assertThat(release.get("lastTaskDay").asText()).isEqualTo("2026-09-21");
        assertThat(release.get("readyDay").asText()).isEqualTo("2026-09-22");

        // растянуть можно только задачу больше 8 часов
        mvc.perform(post("/api/tasks").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeId\":" + emp + ",\"day\":\"2026-09-17\",\"title\":\"small\",\"estimate\":4,\"days\":2}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void absencesAffectCapacityAndTotals() throws Exception {
        long emp = createEmployee("Ирина");
        // период: две рабочие недели 14.09–25.09 (10 рабочих дней, ёмкость 80ч)
        postJson("/api/absences", "{\"employeeId\":" + emp + ",\"type\":\"VACATION\",\"startDay\":\"2026-09-14\",\"endDay\":\"2026-09-16\"}", 201);
        postJson("/api/absences", "{\"employeeId\":" + emp + ",\"type\":\"RENTAL\",\"startDay\":\"2026-09-17\",\"endDay\":\"2026-09-20\",\"note\":\"команда X\"}", 201);
        JsonNode downtime = postJson("/api/absences", "{\"employeeId\":" + emp + ",\"type\":\"DOWNTIME\",\"startDay\":\"2026-09-21\",\"endDay\":\"2026-09-21\",\"hoursPerDay\":4}", 201);
        assertThat(downtime.get("hoursPerDay").asDouble()).isEqualTo(4.0);
        postJson("/api/tasks", "{\"employeeId\":" + emp + ",\"day\":\"2026-09-21\",\"title\":\"T-1\",\"estimate\":4}", 201);

        // список событий за период
        mvc.perform(get("/api/absences").param("from", "2026-09-18").param("to", "2026-09-18"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].type").value("RENTAL"));

        JsonNode a = analytics("2026-09-14", "2026-09-25");
        JsonNode stat = employee(a, emp);
        assertThat(stat.get("vacationDays").asInt()).isEqualTo(3);     // Пн–Ср
        assertThat(stat.get("rentalDays").asInt()).isEqualTo(2);       // Чт, Пт (выходные не считаем)
        assertThat(stat.get("downtimeHours").asDouble()).isEqualTo(4.0);
        assertThat(stat.get("capacity").asDouble()).isEqualTo(40.0);   // 10 рабочих дней минус 5 отсутствий
        assertThat(stat.get("hours").asDouble()).isEqualTo(4.0);
        assertThat(stat.get("utilization").asDouble()).isEqualTo(10.0);
        assertThat(a.get("totals").get("vacationDays").asInt()).isEqualTo(3);
        assertThat(a.get("totals").get("rentalDays").asInt()).isEqualTo(2);
        assertThat(a.get("totals").get("downtimeHours").asDouble()).isEqualTo(4.0);
        // 14.09 Ирина в отпуске, 22.09 никто не отсутствует: ёмкость команды отличается ровно на её 8ч
        assertThat(a.get("dailyCapacity").get(8).asDouble() - a.get("dailyCapacity").get(0).asDouble()).isEqualTo(8.0);
        assertThat(a.get("dailyDowntime").get(7).asDouble()).isEqualTo(4.0);   // 21.09

        // редактирование и удаление
        long id = downtime.get("id").asLong();
        mvc.perform(put("/api/absences/" + id).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeId\":" + emp + ",\"type\":\"DOWNTIME\",\"startDay\":\"2026-09-21\",\"endDay\":\"2026-09-22\",\"hoursPerDay\":8}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hoursPerDay").doesNotExist());
        mvc.perform(delete("/api/absences/" + id).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(put("/api/absences/" + id).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeId\":" + emp + ",\"type\":\"DOWNTIME\",\"startDay\":\"2026-09-21\",\"endDay\":\"2026-09-22\"}"))
            .andExpect(status().isNotFound());

        // некорректный диапазон
        mvc.perform(post("/api/absences").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeId\":" + emp + ",\"type\":\"VACATION\",\"startDay\":\"2026-09-20\",\"endDay\":\"2026-09-10\"}"))
            .andExpect(status().isBadRequest());

        // удаление сотрудника удаляет и его события
        mvc.perform(delete("/api/employees/" + emp).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/absences").param("from", "2026-09-14").param("to", "2026-09-25"))
            .andExpect(jsonPath("$[?(@.employeeId == " + emp + ")].length()").isEmpty());
    }

    private JsonNode analytics(String from, String to) throws Exception {
        return json.readTree(mvc.perform(get("/api/analytics").param("from", from).param("to", to))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static JsonNode employee(JsonNode analytics, long id) {
        for (JsonNode n : analytics.get("employees")) {
            if (n.get("id").asLong() == id) {
                return n;
            }
        }
        throw new AssertionError("Сотрудник " + id + " не найден в аналитике");
    }

    private long createEmployee(String name) throws Exception {
        return postJson("/api/employees", "{\"name\":\"" + name + "\",\"color\":\"#00e5ff\"}", 201).get("id").asLong();
    }

    private JsonNode postJson(String url, String body, int expectedStatus) throws Exception {
        return json.readTree(mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString());
    }
}
