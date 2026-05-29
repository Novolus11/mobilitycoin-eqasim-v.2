# PID Controller Backup - December 17, 2024

## Original (Non-Normalized) PID Parameters

```java
// PID controller parameters (BEFORE normalization)
private static final double kp = 0.01; 
private static final double ki = 0.0002; 
private static final double kd = 0.15; 
private static final double maxPriceStep = 0.3; 
private static final double deadband = 300.0;
private static final double integralLimit = 50.0;
private static final double smoothingFactor = 0.3;
```

## Original calculatePIDOutput Method

```java
private double calculatePIDOutput(double error) {
    // Apply deadband to prevent small oscillations
    if (Math.abs(error) < deadband) {
        return 0.0;
    }

    // Dynamic gain adjustment based on error magnitude
    double errorMagnitude = Math.abs(error);
    double gainFactor = 1.0;
    
    if (errorMagnitude > 50000) {
        gainFactor = 0.5; // Reduce gains for very large errors
    } else if (errorMagnitude > 10000) {
        gainFactor = 0.7; // Moderate reduction for medium errors
    } else if (errorMagnitude < 2000) {
        gainFactor = 0.8; // Slightly reduce gains for small errors
    }

    // Calculate PID terms with dynamic gains
    double pTerm = kp * gainFactor * error;
    
    // Update integral with anti-windup
    integralError += error;
    if (Math.abs(integralError) > integralLimit) {
        integralError = Math.signum(integralError) * integralLimit;
    }
    double iTerm = ki * gainFactor * integralError;
    
    // Calculate derivative term
    double dTerm = kd * gainFactor * (error - lastError);
    lastError = error;

    // Calculate total PID output
    double pidOutput = pTerm + iTerm + dTerm;

    // Adaptive maximum step size based on error magnitude
    double adaptiveMaxStep = calculateAdaptiveMaxStep(errorMagnitude);
    
    // Limit the maximum price change per iteration with adaptive step size
    if (Math.abs(pidOutput) > adaptiveMaxStep) {
        pidOutput = Math.signum(pidOutput) * adaptiveMaxStep;
    }

    return pidOutput;
}
```

## Issues with Original Implementation

1. **Error magnitude varies 160x** across scenarios (14k to 2.3M coins)
2. **Fixed gains** don't scale well across different charge/reduction combinations
3. **Dynamic gain adjustment** based on absolute error magnitude doesn't account for different target scales
4. **Deadband of 300 coins** may be too small for high-charge scenarios and too large for low-charge scenarios

## Reason for Change

Implementing normalized PID to ensure consistent behavior across all 9,000 simulation scenarios with varying:
- Reduction percentages (1-10%)
- Charge levels (0.0005 - 0.008 coins/gCO2)
- Incentive levels (0 - 0.5 coins/km)

---

## Update: Deadband Removed (December 17, 2024)

### What was changed:
- **Deadband completely removed** from normalized PID controller
- Previously had `deadbandPercent = 0.01` (1% of target)

### Why deadband was removed:

1. **Termination is independent of PID convergence:**
   - Simulation terminates based on **mode share stability** (`eqasim_termination.csv`)
   - Uses `ModeShareCriterion.java` which tracks mode share changes over iterations
   - When `|modeShare[now] - modeShare[horizon_ago]| < threshold` → terminate

2. **Integral term handles steady-state:**
   - Without deadband, integral will accumulate small errors
   - This ensures precise convergence to target
   - No artificial "good enough" zone

3. **Deadband created steady-state error:**
   - With 1% deadband, system could settle 1% away from target
   - For 20M coin scenarios, that's 200k coins of imprecision

### Current normalized PID parameters:
```java
private static final double kp_normalized = 0.5;     // EUR per 1% error
private static final double ki_normalized = 0.02;    // Integral gain
private static final double kd_normalized = 0.1;     // Derivative gain
private static final double maxPriceStep = 0.3;      // Max adjustment/iteration
private static final double minPriceStep = 0.01;     // Fine-tuning step
private static final double integralLimitPercent = 0.5; // ±50% anti-windup
// NO DEADBAND
```

