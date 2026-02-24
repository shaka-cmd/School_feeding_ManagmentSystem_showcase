package com.example.School_feeding_managment_system.Service;

import com.example.School_feeding_managment_system.DTO.VendorDashboardDTO;
import com.example.School_feeding_managment_system.Model.*;
import com.example.School_feeding_managment_system.Repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class VendorDashboardService {

    @Autowired private StaffRepository staffRepository;
    @Autowired private MealPlanRepository mealPlanRepository;
    @Autowired private MealPlanService mealPlanService;
    @Autowired private FoodRepository foodRepository;
    @Autowired private MealDeliveryDetailRepository deliveryDetailRepository;
    @Autowired private DeliveryApprovalRepository deliveryApprovalRepository;

    public VendorDashboardDTO getDashboard(Long vendorId) {
        Staff vendor = staffRepository.findById(vendorId)
                .orElseThrow(() -> new IllegalArgumentException("Vendor not found"));
        if (!"VENDOR".equalsIgnoreCase(vendor.getRole().getName())) {
            throw new IllegalArgumentException("Not a vendor");
        }

        List<MealPlan> assignedMeals = mealPlanRepository.findByVendorId(vendorId);

        assignedMeals.forEach(mealPlan -> {
            mealPlanService.calculateNutrition(mealPlan);

            List<DeliveryApproval> approvals = deliveryApprovalRepository
                    .findLatestByMealPlanId(mealPlan.getId());

            if (!approvals.isEmpty()) {
                DeliveryApproval latest = approvals.get(0);
                mealPlan.setApprovalStatus(latest.getStatus().name());
                mealPlan.setApprovalReason(latest.getReason());
            } else if (deliveryDetailRepository.findByMealPlanId(mealPlan.getId()).size() > 0) {
                mealPlan.setApprovalStatus("PENDING_APPROVAL");
            } else {
                mealPlan.setApprovalStatus(null);
            }
        });

        String fullName = vendor.getFirstName() + " " + vendor.getLastName();
        String companyName = vendor.getCompanyName() != null ? vendor.getCompanyName() : "N/A";
        String foodCategoryName = vendor.getFoodCategory() != null ? vendor.getFoodCategory().getName() : "N/A";
        String photo = vendor.getPhoto();

        return new VendorDashboardDTO(
                vendorId, vendor.getStaffId(), fullName, vendor.getEmail(),
                companyName, foodCategoryName, photo, assignedMeals
        );
    }

    public List<MealPlan> getMealsByDate(Long vendorId, LocalDate date) {
        validateVendor(vendorId);
        return mealPlanRepository.findByVendorIdAndDate(vendorId, date);
    }

    public List<MealPlan> getMealsByDayOfWeek(Long vendorId, DayOfWeek day) {
        validateVendor(vendorId);
        return mealPlanRepository.findByVendorId(vendorId).stream()
                .filter(m -> m.getDate().getDayOfWeek() == day)
                .collect(Collectors.toList());
    }

    public MealPlan startPreparation(Long mealId, Long vendorId) {
        return mealPlanService.startPreparation(mealId, vendorId);
    }

    public MealPlan markDelivered(Long mealId, Long vendorId, List<MealDeliveryDetail> suppliedDetails) {
        MealPlan meal = mealPlanRepository.findById(mealId)
                .orElseThrow(() -> new IllegalArgumentException("Meal not found"));
        if (!meal.getVendor().getId().equals(vendorId)) {
            throw new IllegalArgumentException("This meal is not assigned to you");
        }
        if (meal.getStatus() != MealPlan.Status.IN_PROGRESS) {
            throw new IllegalStateException("Can only mark delivered from IN_PROGRESS status");
        }
        if (suppliedDetails == null || suppliedDetails.isEmpty()) {
            throw new IllegalArgumentException("Delivery details are required");
        }
        var plannedFoodIds = meal.getFoods().stream()
                .map(Food::getId).collect(Collectors.toSet());
        var suppliedFoodIds = suppliedDetails.stream()
                .map(d -> d.getFood().getId()).collect(Collectors.toSet());
        if (!plannedFoodIds.equals(suppliedFoodIds)) {
            throw new IllegalArgumentException("You must provide quantity for every food in the meal plan");
        }
        int totalSupplied = suppliedDetails.stream()
                .mapToInt(MealDeliveryDetail::getSuppliedQuantity).sum();
        if (totalSupplied != meal.getQuantity()) {
            throw new IllegalArgumentException(
                    "Total supplied quantity (" + totalSupplied + ") does not match planned quantity (" + meal.getQuantity() + ")");
        }
        suppliedDetails.forEach(d -> {
            d.setMealPlan(meal);
            deliveryDetailRepository.save(d);
        });
        return mealPlanService.markDelivered(mealId, vendorId);
    }

    private void validateVendor(Long vendorId) {
        Staff vendor = staffRepository.findById(vendorId)
                .orElseThrow(() -> new IllegalArgumentException("Vendor not found"));
        if (!"VENDOR".equalsIgnoreCase(vendor.getRole().getName())) {
            throw new IllegalArgumentException("Not a vendor");
        }
    }
}