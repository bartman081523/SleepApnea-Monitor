# Plan 2: Simplified UX, Health Integration & Robust Visualization

## Phase 1: UI Streamlining & Simplified Flow
1. **Dynamic Dashboard Button:**
    - Replace separate Start/Stop/Test buttons with a single MaterialButton.
    - State A (Idle): Green, "Apnea Tracking starten".
    - State B (Running): Red, "Tracking beenden".
2. **Dashboard Cleanup:**
    - Direct "Morgen Feedback" button below the main tracker.
    - "Auto-Record" switch below feedback.
    - Remove "Test Mode" button (keep logic for automation).
3. **Themed Visualization:**
    - Update `ApneaChartView` background to use theme-aware attributes.
    - Fix drawing logic: Use `Path.cubicTo` or smoother segments for curves instead of simple lines.
    - Ensure alarm markers are clearly visible on the chart.

## Phase 2: Health Ecosystem Integration (Google Health Connect)
4. **Health Connect Setup:**
    - Add dependencies: `androidx.health.connect:connect-client`.
    - Implement permission request for Heart Rate and Sleep data.
5. **Data Correlation:**
    - Periodically fetch Heart Rate during tracking.
    - Save Heart Rate values into the night CSV.
    - Display Pulse curve in `AnalysisActivity` alongside ML scores.

## Phase 3: Analysis, File Management & Onboarding
6. **Alarm Parsing:** Fix the CSV parser to correctly identify and count all `ALARM_START` events.
7. **Remove Time Constraints:** Remove the 3-hour minimum threshold for triggering the Morning Questionnaire. Any manual stop triggers it.
8. **File Management:** Implement long-press in the History list to delete CSV and corresponding M4A files with a confirmation dialog.
9. **App Onboarding Tour:**
    - Implement a step-by-step introduction (using a lightweight library or custom overlay) for first-time users.
    - Explain: Dashboard (Start/Stop), Settings (Volume/Thresholds), History (Analysis & Deletion), Morning Questionnaire.

## Phase 4: Rigorous Validation
10. **UI Automation:** Create `ui_automation_final.py` to test the dynamic button flow and chart rendering.
11. **Unit Tests:** Add tests for CSV parsing and Health data correlation.
