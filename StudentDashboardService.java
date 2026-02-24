package com.example.School_feeding_managment_system.Service;

import com.example.School_feeding_managment_system.DTO.*;
import com.example.School_feeding_managment_system.Model.*;
import com.example.School_feeding_managment_system.Repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class StudentDashboardService {

    @Autowired
    private StudentRepository studentRepo;

    @Autowired
    private MealDistributionRepository distributionRepo;

    @Autowired
    private MealAttendanceRepository attendanceRepo;

    @Autowired
    private DeliveryApprovalRepository approvalRepo;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public StudentDashboardDTO loginAndGetDashboard(String email, String password, String studentId, LocalDate date) {
        Student student = studentRepo.findByStudentId(studentId);
        if (student == null) {
            throw new IllegalArgumentException("Invalid student ID");
        }

        if (!student.getEmail().equalsIgnoreCase(email)) {
            throw new IllegalArgumentException("Email does not match this student ID");
        }

        if (!passwordEncoder.matches(password, student.getPassword())) {
            throw new IllegalArgumentException("Invalid password");
        }

        return getDashboard(studentId, date);
    }

    @Transactional(readOnly = true)
    public StudentDashboardDTO getDashboard(String studentId, LocalDate date) {
        Student student = studentRepo.findByStudentId(studentId);
        if (student == null) {
            throw new IllegalArgumentException("Student not found");
        }

        // Show meals from 3 days before to 7 days after the selected date
        LocalDate fromDate = date.minusDays(3);
        LocalDate toDate = date.plusDays(7);

        List<MealDistribution> distributions = distributionRepo.findAllByDateRangeWithItems(fromDate, toDate);

        Map<LocalDate, List<MealOptionDTO>> mealsByDate = new LinkedHashMap<>();

        // Sort distributions by date
        distributions.stream()
                .sorted(Comparator.comparing(MealDistribution::getDistributionDate))
                .forEach(dist -> {
                    LocalDate distDate = dist.getDistributionDate();

                    // Only include if meal is approved (for future/present) OR it's a past meal (show history)
                    if (shouldIncludeMeal(dist)) {
                        mealsByDate.computeIfAbsent(distDate, k -> new ArrayList<>());

                        int registeredRounds = attendanceRepo.countDistinctRoundsByStudentAndDistribution(student, dist);
                        int maxRounds = dist.getRoundsAllowed() != null ? dist.getRoundsAllowed() : 1;
                        boolean canRegisterMore = registeredRounds < maxRounds;

                        // Determine if meal is serving now based on date and time
                        boolean isServingNow = isMealCurrentlyServing(dist);

                        // Can take more only if serving now AND can register more
                        boolean canTakeMore = canRegisterMore && isServingNow;

                        // Convert to FoodItemDTO for frontend compatibility
                        List<FoodItemDTO> foods = new ArrayList<>();
                        if (dist.getDistributionItems() != null) {
                            foods = dist.getDistributionItems().stream()
                                    .filter(item -> item != null && item.getFood() != null)
                                    .map(item -> {
                                        String foodName = item.getFood().getName();
                                        String quantity = item.getDistributedQuantity() != null
                                                ? item.getDistributedQuantity().toString()
                                                : "1";
                                        String unit = getSafeUnit(foodName);

                                        return new FoodItemDTO(foodName, quantity, unit);
                                    })
                                    .collect(Collectors.toList());
                        }

                        MealOptionDTO option = new MealOptionDTO(
                                dist.getId(),
                                dist.getMealType() != null ? dist.getMealType().name() : "MEAL",
                                dist.getStartDistributionTime(),
                                dist.getEndDistributionTime(),
                                maxRounds,
                                registeredRounds,
                                canTakeMore,
                                isServingNow,
                                foods
                        );

                        mealsByDate.get(distDate).add(option);
                    }
                });

        // Ensure the map is sorted by date
        Map<LocalDate, List<MealOptionDTO>> sortedMeals = new TreeMap<>(mealsByDate);

        byte[] photoBytes = student.getPhoto();

        return new StudentDashboardDTO(
                student.getStudentId(),
                student.getFirstName() + " " + student.getLastName(),
                student.getEmail(),
                student.getAge(),
                photoBytes,
                sortedMeals
        );
    }

    /**
     * Determines if a meal should be included in the dashboard
     * - Past meals: Always include for history
     * - Future meals: Only include if approved
     */
    private boolean shouldIncludeMeal(MealDistribution dist) {
        LocalDate today = LocalDate.now();

        // Past meals - always show for history
        if (dist.getDistributionDate().isBefore(today)) {
            return true;
        }

        // Today or future - only show if approved
        return isMealApproved(dist);
    }

    /**
     * Checks if a meal is currently being served
     */
    private boolean isMealCurrentlyServing(MealDistribution dist) {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();

        // Not today
        if (!dist.getDistributionDate().equals(today)) {
            return false;
        }

        // Check time window
        LocalTime start = dist.getStartDistributionTime();
        LocalTime end = dist.getEndDistributionTime();

        if (start == null || end == null) {
            return false;
        }

        return !now.isBefore(start) && !now.isAfter(end);
    }

    private String getSafeUnit(String foodName) {
        if (foodName == null) return "serving";
        String name = foodName.toLowerCase();

        if (name.contains("rice") || name.contains("beans") || name.contains("yam") ||
                name.contains("cassava") || name.contains("garri") || name.contains("flour") ||
                name.contains("potato") || name.contains("maize")) {
            return "kg";
        }
        if (name.contains("oil") || name.contains("water") || name.contains("milk") ||
                name.contains("juice") || name.contains("drink") || name.contains("soda")) {
            return "liters";
        }
        if (name.contains("bread") || name.contains("egg") || name.contains("chicken") ||
                name.contains("fish") || name.contains("meat") || name.contains("plantain") ||
                name.contains("banana") || name.contains("apple") || name.contains("orange")) {
            return "pieces";
        }
        return "serving";
    }

    private boolean isMealApproved(MealDistribution dist) {
        if (dist.getSourceMealPlan() == null) return true; // If no source plan, consider approved

        try {
            return approvalRepo.findLatestByMealPlanId(dist.getSourceMealPlan().getId())
                    .stream()
                    .anyMatch(da -> da.getStatus() == DeliveryApproval.ApprovalStatus.APPROVED);
        } catch (Exception e) {
            // If approval check fails, default to true to not hide meals
            return true;
        }
    }

    @Transactional
    public void registerForMeal(String studentId, Long distributionId, Integer round) {
        Student student = studentRepo.findByStudentId(studentId);
        if (student == null) {
            throw new IllegalArgumentException("Student not found");
        }

        MealDistribution dist = distributionRepo.findByIdWithItems(distributionId)
                .orElseThrow(() -> new IllegalArgumentException("Meal distribution not found"));

        LocalDate today = LocalDate.now();

        // Validate date
        if (dist.getDistributionDate().isBefore(today)) {
            throw new IllegalStateException("Cannot register for past meals");
        }

        // For today's meals, check serving time
        if (dist.getDistributionDate().equals(today)) {
            if (!isMealCurrentlyServing(dist)) {
                throw new IllegalStateException("Cannot register: meal not currently being served");
            }
        }

        // Validate round
        Integer maxRounds = dist.getRoundsAllowed() != null ? dist.getRoundsAllowed() : 1;
        if (round < 1 || round > maxRounds) {
            throw new IllegalArgumentException("Invalid round. Allowed rounds: 1-" + maxRounds);
        }

        // Check if already registered
        if (attendanceRepo.existsByStudentAndMealDistributionAndRound(student, dist, round)) {
            throw new IllegalStateException("Already registered for round " + round);
        }

        // Check if reached max rounds
        int registeredRounds = attendanceRepo.countDistinctRoundsByStudentAndDistribution(student, dist);
        if (registeredRounds >= maxRounds) {
            throw new IllegalStateException("You have already taken all " + maxRounds + " allowed rounds");
        }

        MealAttendance attendance = new MealAttendance();
        attendance.setStudent(student);
        attendance.setMealDistribution(dist);
        attendance.setRound(round);
        attendance.setStatus(MealAttendance.AttendanceStatus.REGISTERED);
        attendance.setTakenAt(LocalTime.now());

        attendanceRepo.save(attendance);
    }

    @Transactional(readOnly = true)
    public MealDistributionDTO getMealDetails(Long distributionId) {
        MealDistribution dist = distributionRepo.findByIdWithItems(distributionId)
                .orElseThrow(() -> new IllegalArgumentException("Meal not found"));

        List<DistributionItemDTO> items = dist.getDistributionItems().stream()
                .filter(item -> item != null && item.getFood() != null)
                .map(item -> new DistributionItemDTO(
                        item.getId(),
                        item.getFood().getId(),
                        item.getFood().getName(),
                        getSafeUnit(item.getFood().getName()),
                        item.getDistributedQuantity(),
                        null
                ))
                .collect(Collectors.toList());

        MealDistributionDTO dto = new MealDistributionDTO();
        dto.setId(dist.getId());
        dto.setDistributionDate(dist.getDistributionDate());
        dto.setMealType(dist.getMealType() != null ? dist.getMealType().name() : "MEAL");
        dto.setStatus(dist.getStatus() != null ? dist.getStatus().name() : "PLANNED");
        dto.setStartDistributionTime(dist.getStartDistributionTime());
        dto.setEndDistributionTime(dist.getEndDistributionTime());
        dto.setNumberOfStudents(dist.getNumberOfStudents());
        dto.setItems(items);

        return dto;
    }
}