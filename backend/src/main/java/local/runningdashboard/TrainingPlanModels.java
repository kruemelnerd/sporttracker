package local.runningdashboard;

import java.time.LocalDate;
import java.util.UUID;

final class TrainingPlanModels {
    private TrainingPlanModels() { }

    record Plan(UUID id, String name, String goal, LocalDate startDate, LocalDate endDate,
                LocalDate raceDate, Integer targetTime, String description) { }

    record Phase(UUID id, UUID trainingPlanId, String name, LocalDate startDate, LocalDate endDate,
                 String description) { }

    record Session(UUID id, UUID trainingPlanId, LocalDate date, String sport, String sessionType,
                   String title, Double targetDistanceKm, Integer targetDurationMinutes,
                   Double targetPaceFrom, Double targetPaceTo, Integer targetRpe,
                   String instructions, String notes) { }
}
