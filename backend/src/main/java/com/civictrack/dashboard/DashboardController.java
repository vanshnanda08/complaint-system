package com.civictrack.dashboard;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public dashboard. Permitted anonymously by the security chain, because a
 * dashboard that measures departments and requires a municipal login to read
 * would be an internal report with extra steps.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboard;

    @GetMapping("/summary")
    public DashboardSummaryDto summary() {
        return dashboard.summary();
    }
}
