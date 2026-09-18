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

        // оценка 20ч + 4ч сверх оценки: 24ч растягиваются автоматически на 3 рабочих дня по 8ч: Чт 17, Пт 18, Пн 21
        JsonNode task = postJson("/api/tasks", "{\"employeeId\":" + emp + ",\"day\":\"2026-09-17\",\"title\":\"BIG-1\","
            + "\"release\":\"3.0\",\"epic\":\"Платежи\",\"estimate\":20,\"overtime\":4}", 201);
        assertThat(task.get("days").asInt()).isEqualTo(3);
        assertThat(task.get("hours").asDouble()).isEqualTo(24.0);
        assertThat(task.get("epic").asText()).isEqualTo("Платежи");
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
        assertThat(stat.get("overtimeTasks").asInt()).isEqualTo(1);
        assertThat(a.get("totals").get("overtime").asDouble()).isEqualTo(4.0);
        assertThat(a.get("totals").get("overtimeTasks").asInt()).isEqualTo(1);
        assertThat(a.get("totals").get("overtimeEmployees").asInt()).isEqualTo(1);
        // перерасход ложится на последний день: если взять период только до пятницы, сверх оценки ещё нет
        assertThat(employee(analytics("2026-09-14", "2026-09-18"), emp).get("overtime").asDouble()).isEqualTo(0.0);

        // готовность релиза 3.0 — следующий рабочий день после последнего дня задачи (Пн 21 → Вт 22)
        JsonNode release = a.get("releases").get(0);
        assertThat(release.get("release").asText()).isEqualTo("3.0");
        assertThat(release.get("lastTaskDay").asText()).isEqualTo("2026-09-21");
        assertThat(release.get("readyDay").asText()).isEqualTo("2026-09-22");

        // релиз, последняя задача которого в пятницу, готов в понедельник, а не в субботу
        postJson("/api/tasks", "{\"employeeId\":" + emp + ",\"day\":\"2026-09-25\",\"title\":\"FRI-1\",\"release\":\"3.1\",\"estimate\":2}", 201);
        JsonNode friday = analytics("2026-09-25", "2026-09-25").get("releases").get(0);
        assertThat(friday.get("release").asText()).isEqualTo("3.1");
        assertThat(friday.get("readyDay").asText()).isEqualTo("2026-09-28");

        // задача не больше 8 часов в один день; 12ч → 2 дня (8 + 4)
        assertThat(postJson("/api/tasks", "{\"employeeId\":" + emp + ",\"day\":\"2026-09-17\",\"title\":\"small\",\"estimate\":4}", 201)
            .get("days").asInt()).isEqualTo(1);
        JsonNode twelveTask = postJson("/api/tasks", "{\"employeeId\":" + emp + ",\"day\":\"2026-09-28\",\"title\":\"twelve\",\"estimate\":12}", 201);
        assertThat(twelveTask.get("days").asInt()).isEqualTo(2);
        JsonNode twelve = employee(analytics("2026-09-28", "2026-09-29"), emp);
        assertThat(twelve.get("hours").asDouble()).isEqualTo(12.0);
        assertThat(twelve.get("maxDayHours").asDouble()).isEqualTo(8.0);

        // эпики в аналитике и в справочнике
        assertThat(analytics("2026-09-14", "2026-09-25").get("epics").get(0).get("name").asText()).isEqualTo("Платежи");
        mvc.perform(get("/api/tasks/epics")).andExpect(jsonPath("$[?(@ == 'Платежи')]").exists());

        // досрочное завершение: 12ч задача сделана за 5ч — занимает один день, сэкономлено 7ч
        long twelveId = twelveTask.get("id").asLong();
        JsonNode early = json.readTree(mvc.perform(put("/api/tasks/" + twelveId).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"twelve\",\"estimate\":12,\"overtime\":3,\"completedEarly\":true,\"spent\":5}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(early.get("completedEarly").asBoolean()).isTrue();
        assertThat(early.get("days").asInt()).isEqualTo(1);
        assertThat(early.get("hours").asDouble()).isEqualTo(5.0);
        assertThat(early.get("overtime").isNull()).isTrue();   // сверх оценки у досрочной задачи не бывает
        JsonNode earlyStat = employee(analytics("2026-09-28", "2026-09-29"), emp);
        assertThat(earlyStat.get("hours").asDouble()).isEqualTo(5.0);
        assertThat(earlyStat.get("earlyTasks").asInt()).isEqualTo(1);
        assertThat(earlyStat.get("savedHours").asDouble()).isEqualTo(7.0);
        assertThat(analytics("2026-09-28", "2026-09-29").get("totals").get("earlyTasks").asInt()).isEqualTo(1);
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
        assertThat(stat.get("utilization").asDouble()).isEqualTo(10.0);   // простой в рабочее время не входит
        assertThat(stat.get("idleWorkdays").asInt()).isEqualTo(4);        // 22–25.09 без задач; 21.09 есть задача
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

    @Test
    void halfRateEmployeeStretchesTasksFromFourHours() throws Exception {
        long emp = postJson("/api/employees", "{\"name\":\"Полставки\",\"color\":\"#ffd166\",\"rate\":0.5}", 201)
            .get("id").asLong();
        // 12ч при норме 4ч в день: Пн 5.10, Вт 6.10, Ср 7.10 по 4ч
        JsonNode t = postJson("/api/tasks", "{\"employeeId\":" + emp + ",\"day\":\"2026-10-05\",\"title\":\"HALF-1\",\"estimate\":12}", 201);
        assertThat(t.get("days").asInt()).isEqualTo(3);
        JsonNode stat = employee(analytics("2026-10-05", "2026-10-09"), emp);
        assertThat(stat.get("rate").asDouble()).isEqualTo(0.5);
        assertThat(stat.get("capacity").asDouble()).isEqualTo(20.0);   // 5 рабочих дней × 4ч
        assertThat(stat.get("hours").asDouble()).isEqualTo(12.0);
        assertThat(stat.get("maxDayHours").asDouble()).isEqualTo(4.0);
        assertThat(stat.get("overloadedDays").asInt()).isEqualTo(0);
        assertThat(stat.get("utilization").asDouble()).isEqualTo(60.0);

        // смена ставки на полную пересчитывает растяжку: 12ч → 2 дня
        mvc.perform(put("/api/employees/" + emp).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Полставки\",\"color\":\"#ffd166\",\"rate\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rate").value(1.0));
        mvc.perform(get("/api/tasks").param("from", "2026-10-05").param("to", "2026-10-05"))
            .andExpect(jsonPath("$[0].days").value(2));
        // и на среду задача больше не попадает
        mvc.perform(get("/api/tasks").param("from", "2026-10-07").param("to", "2026-10-07"))
            .andExpect(jsonPath("$.length()").value(0));
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
