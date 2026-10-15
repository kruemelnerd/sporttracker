package local.runningdashboard;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class TrainingPlanRepository {
    private final JdbcTemplate jdbc;

    TrainingPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<TrainingPlanModels.Plan> findPlans() {
        return jdbc.query("SELECT id, name, goal, start_date, end_date, race_date, target_time_minutes, description FROM training_plan ORDER BY start_date DESC, name",
                (rs, row) -> plan(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("goal"),
                        rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                        rs.getObject("race_date", LocalDate.class), rs.getObject("target_time_minutes", Integer.class), rs.getString("description")));
    }

    Optional<TrainingPlanModels.Plan> findPlan(UUID id) {
        List<TrainingPlanModels.Plan> plans = jdbc.query("SELECT id, name, goal, start_date, end_date, race_date, target_time_minutes, description FROM training_plan WHERE id = ?",
                (rs, row) -> plan(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("goal"),
                        rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                        rs.getObject("race_date", LocalDate.class), rs.getObject("target_time_minutes", Integer.class), rs.getString("description")), id);
        return plans.stream().findFirst();
    }

    Optional<TrainingPlanModels.Plan> findDuplicate(TrainingPlanModels.Plan candidate) {
        List<TrainingPlanModels.Plan> plans = jdbc.query("SELECT id, name, goal, start_date, end_date, race_date, target_time_minutes, description FROM training_plan WHERE name = ? AND goal = ? AND start_date = ? AND end_date = ? AND race_date IS NOT DISTINCT FROM ?",
                (rs, row) -> plan(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("goal"),
                        rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                        rs.getObject("race_date", LocalDate.class), rs.getObject("target_time_minutes", Integer.class), rs.getString("description")),
                candidate.name(), candidate.goal(), candidate.startDate(), candidate.endDate(), candidate.raceDate());
        return plans.stream().findFirst();
    }

    void insertPlan(TrainingPlanModels.Plan plan) {
        jdbc.update("INSERT INTO training_plan (id, name, goal, start_date, end_date, race_date, target_time_minutes, description) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                plan.id(), plan.name(), plan.goal(), plan.startDate(), plan.endDate(), plan.raceDate(), plan.targetTime(), plan.description());
    }

    void updatePlan(TrainingPlanModels.Plan plan) {
        jdbc.update("UPDATE training_plan SET name = ?, goal = ?, start_date = ?, end_date = ?, race_date = ?, target_time_minutes = ?, description = ? WHERE id = ?",
                plan.name(), plan.goal(), plan.startDate(), plan.endDate(), plan.raceDate(), plan.targetTime(), plan.description(), plan.id());
    }

    void deletePlan(UUID id) {
        jdbc.update("DELETE FROM training_plan WHERE id = ?", id);
    }

    List<TrainingPlanModels.Phase> findPhases(UUID planId) {
        return jdbc.query("SELECT id, training_plan_id, name, start_date, end_date, description FROM training_phase WHERE training_plan_id = ? ORDER BY start_date, id",
                (rs, row) -> new TrainingPlanModels.Phase(rs.getObject("id", UUID.class), rs.getObject("training_plan_id", UUID.class),
                        rs.getString("name"), rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class), rs.getString("description")), planId);
    }

    void replacePhases(UUID planId, List<TrainingPlanModels.Phase> phases) {
        jdbc.update("DELETE FROM training_phase WHERE training_plan_id = ?", planId);
        if (!phases.isEmpty()) jdbc.batchUpdate("INSERT INTO training_phase (id, training_plan_id, name, start_date, end_date, description) VALUES (?, ?, ?, ?, ?, ?)",
                phases, phases.size(), (statement, phase) -> {
                    statement.setObject(1, phase.id());
                    statement.setObject(2, planId);
                    statement.setString(3, phase.name());
                    statement.setObject(4, phase.startDate());
                    statement.setObject(5, phase.endDate());
                    statement.setString(6, phase.description());
                });
    }

    List<TrainingPlanModels.Session> findSessions(UUID planId, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT id, training_plan_id, session_date, sport, session_type, title, target_distance_km, target_duration_minutes, target_pace_from, target_pace_to, target_rpe, instructions, notes FROM planned_session WHERE training_plan_id = ? AND session_date BETWEEN ? AND ? ORDER BY session_date, id",
                (rs, row) -> session(rs), planId, from, to);
    }

    Optional<TrainingPlanModels.Session> findSession(UUID id) {
        List<TrainingPlanModels.Session> sessions = jdbc.query("SELECT id, training_plan_id, session_date, sport, session_type, title, target_distance_km, target_duration_minutes, target_pace_from, target_pace_to, target_rpe, instructions, notes FROM planned_session WHERE id = ?",
                (rs, row) -> session(rs), id);
        return sessions.stream().findFirst();
    }

    void insertSession(TrainingPlanModels.Session session) {
        jdbc.update("INSERT INTO planned_session (id, training_plan_id, session_date, sport, session_type, title, target_distance_km, target_duration_minutes, target_pace_from, target_pace_to, target_rpe, instructions, notes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                session.id(), session.trainingPlanId(), session.date(), session.sport(), session.sessionType(), session.title(), session.targetDistanceKm(), session.targetDurationMinutes(), session.targetPaceFrom(), session.targetPaceTo(), session.targetRpe(), session.instructions(), session.notes());
    }

    void updateSession(TrainingPlanModels.Session session) {
        jdbc.update("UPDATE planned_session SET session_date = ?, sport = ?, session_type = ?, title = ?, target_distance_km = ?, target_duration_minutes = ?, target_pace_from = ?, target_pace_to = ?, target_rpe = ?, instructions = ?, notes = ? WHERE id = ?",
                session.date(), session.sport(), session.sessionType(), session.title(), session.targetDistanceKm(), session.targetDurationMinutes(), session.targetPaceFrom(), session.targetPaceTo(), session.targetRpe(), session.instructions(), session.notes(), session.id());
    }

    void deleteSession(UUID id) {
        jdbc.update("DELETE FROM planned_session WHERE id = ?", id);
    }

    void deleteSessions(UUID planId) {
        jdbc.update("DELETE FROM planned_session WHERE training_plan_id = ?", planId);
    }

    private static TrainingPlanModels.Plan plan(UUID id, String name, String goal, LocalDate start, LocalDate end,
                                                  LocalDate race, Integer targetTime, String description) {
        return new TrainingPlanModels.Plan(id, name, goal, start, end, race, targetTime, description);
    }

    private static TrainingPlanModels.Session session(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TrainingPlanModels.Session(rs.getObject("id", UUID.class), rs.getObject("training_plan_id", UUID.class),
                rs.getObject("session_date", LocalDate.class), rs.getString("sport"), rs.getString("session_type"), rs.getString("title"),
                rs.getObject("target_distance_km", Double.class), rs.getObject("target_duration_minutes", Integer.class),
                rs.getObject("target_pace_from", Double.class), rs.getObject("target_pace_to", Double.class),
                rs.getObject("target_rpe", Integer.class), rs.getString("instructions"), rs.getString("notes"));
    }
}
