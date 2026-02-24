package com.example.School_feeding_managment_system.Controller;

import com.example.School_feeding_managment_system.DTO.MealDistributionDTO;
import com.example.School_feeding_managment_system.DTO.StudentDashboardDTO;
import com.example.School_feeding_managment_system.Model.Student;
import com.example.School_feeding_managment_system.Service.StudentDashboardService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/studentdashboard")
public class StudentDashboardController {

    @Autowired
    private StudentDashboardService dashboardService;


    @GetMapping("/dashboard")
    public ResponseEntity<?> getDashboard(
            @RequestParam(required = false) LocalDate date,
            HttpSession session) {

        System.out.println("=== DASHBOARD REQUEST ===");
        System.out.println("Session ID: " + session.getId());
        System.out.println("Is new session: " + session.isNew());

        String studentId = extractStudentIdFromSession(session);
        System.out.println("Extracted studentId: " + studentId);

        if (studentId == null) {
            System.out.println("ERROR: No student ID in session");
            // Debug: print all session attributes
            Enumeration<String> attributeNames = session.getAttributeNames();
            while (attributeNames.hasMoreElements()) {
                String attrName = attributeNames.nextElement();
                System.out.println("Session attr: " + attrName + " = " + session.getAttribute(attrName));
            }

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Student not logged in. Please login first."));
        }

        LocalDate targetDate = (date != null) ? date : LocalDate.now();
        StudentDashboardDTO dashboard = dashboardService.getDashboard(studentId, targetDate);

        return ResponseEntity.ok(dashboard);
    }

    @PostMapping("/register-meal")
    public ResponseEntity<?> registerMeal(
            @RequestParam Long distributionId,
            @RequestParam Integer round,
            HttpSession session) {

        String studentId = extractStudentIdFromSession(session);
        if (studentId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Student not logged in"));
        }

        try {
            dashboardService.registerForMeal(studentId, distributionId, round);
            return ResponseEntity.ok("Meal registered successfully for round " + round);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/meal/{distributionId}")
    public ResponseEntity<MealDistributionDTO> getMealDetails(@PathVariable Long distributionId) {
        MealDistributionDTO dto = dashboardService.getMealDetails(distributionId);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/check-session")
    public ResponseEntity<?> checkSession(HttpSession session) {
        String studentId = extractStudentIdFromSession(session);

        if (studentId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                            "error", "No session found",
                            "sessionId", session.getId(),
                            "isNew", session.isNew()
                    ));
        }

        return ResponseEntity.ok(Map.of(
                "message", "Session active",
                "studentId", studentId,
                "sessionId", session.getId()
        ));
    }

    @GetMapping("/debug-session")
    public ResponseEntity<?> debugSession(HttpSession session) {
        Map<String, Object> debugInfo = new HashMap<>();
        debugInfo.put("sessionId", session.getId());
        debugInfo.put("isNew", session.isNew());
        debugInfo.put("creationTime", new Date(session.getCreationTime()));
        debugInfo.put("lastAccessedTime", new Date(session.getLastAccessedTime()));

        List<String> attributes = new ArrayList<>();
        Enumeration<String> attributeNames = session.getAttributeNames();
        while (attributeNames.hasMoreElements()) {
            String attrName = attributeNames.nextElement();
            Object attrValue = session.getAttribute(attrName);
            attributes.add(attrName + ": " + (attrValue != null ? attrValue.toString() : "null"));
        }
        debugInfo.put("attributes", attributes);

        return ResponseEntity.ok(debugInfo);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    // Helper to make code cleaner and more robust
    private String extractStudentIdFromSession(HttpSession session) {
        Student student = (Student) session.getAttribute("student");
        if (student != null) {
            return student.getStudentId();
        }
        return (String) session.getAttribute("studentId");
    }
}