# Bavaria Mode Choice Calibration - Munich Parameters

This directory contains the calibrated mode choice parameters for Munich/Bavaria simulations.

## Overview

The calibrated parameters were implemented in the branch `munich_calibration` and include significant improvements over the previous default parameters. The calibration incorporates socio-demographic variables and trip-specific characteristics to better model mode choice behavior in Munich and Bavaria.

## Key Changes

### 1. **Mode Parameters (BavariaModeParameters.java)**

The calibrated version includes much more detailed parameters for each mode:

#### **Walk Parameters**
- High income effect
- Driving permit availability effect
- PT subscription effect
- Munich residency effect

#### **Bicycle Parameters**
- High income effect

#### **Car Parameters**
- High income effect
- PT subscription effect
- Work trip effect
- Shopping trip effect

#### **Car Passenger Parameters**
- High income effect
- Work trip effect
- Shopping trip effect

#### **PT Parameters**
- High income effect
- Waiting time interactions with:
  - High income
  - Munich residency
  - PT subscription
  - Shopping trips
- Work trip effect
- Shopping trip effect

### 2. **Person-Level Variables**

The system now tracks and uses the following person-level characteristics:
- `isHighIncome` - Whether the person has high income
- `isMunichResident` - Whether the person is a Munich resident
- `hasSubscription` - Whether the person has a PT subscription
- `hasDrivingPermit` - Whether the person has a driving permit

These must be defined as attributes in the population file.

### 3. **Calibrated Parameter Values**

Key calibrated values include:
- **Cost sensitivity** (`betaCost_u_MU`): -0.0448921788117033
- **Access time** (`betaAccessTime_u_min`): -0.0450421005231951
- **Walk constant** (`walk.alpha_u`): -0.1
- **Bicycle constant** (`bike.alpha_u`): -1.2
- **Car constant** (`car.alpha_u`): 0.0
- **PT constant** (`pt.alpha_u`): -0.36
- **Car Passenger constant** (`bavariaCarPassenger.alpha_u`): -1.75

### 4. **Configuration File**

The file `munich_calibrated_config.xml` contains the complete simulation configuration including:
- eqasim:raptor parameters calibrated for Munich
- Estimator configurations for all modes including walk
- Cost model configurations

## Usage

To use these calibrated parameters in your simulation:

1. Ensure your population file includes the required person attributes:
   - `highIncome` (Boolean)
   - `isMunichResident` (Boolean)
   - `hasPtSubscription` (Boolean)

2. Use the calibrated config file as a reference:
   ```bash
   java -jar bavaria-1.5.0.jar org.eqasim.bavaria.RunSimulation --config-path bavaria/config_examples/munich_calibrated_config.xml
   ```

3. The calibrated parameters are now the default in `BavariaModeParameters.buildDefault()`, so no additional configuration is needed unless you want to override specific values.

## Important Notes

- The `lambdaCostEuclideanDistance` parameter is set to 0.0 in the calibrated version, removing the distance-based cost interaction.
- The previous "Munich-specific" constants (previously in `parameters.munich.car_u`, etc.) have been removed and replaced with the socio-demographic and trip-purpose effects.
- Parking costs have been simplified (set to 0.0) in the car cost model. A Munich-specific parking cost model can be implemented if needed.

## Estimators

The following utility estimators are now registered and active:
- `BavariaWalkUtilityEstimator` - NEW!
- `BavariaCarUtilityEstimator` - Updated
- `BavariaBicycleUtilityEstimator` - Updated
- `BavariaCarPassengerUtilityEstimator` - Updated
- `BavariaPtUtilityEstimator` - Updated

## Files Modified

The following files were updated to implement the calibration:

1. **Parameters:**
   - `BavariaModeParameters.java` - Complete restructuring with new parameter classes

2. **Variables:**
   - `BavariaPersonVariables.java` - Replaced `isParisResident` with `isHighIncome` and `isMunichResident`

3. **Predictors:**
   - `BavariaPersonPredictor.java` - Updated to predict new variables
   - `BavariaPredictorUtils.java` - Added `isHighIncome()` and `isMunichResident()` methods

4. **Utility Estimators:**
   - `BavariaCarUtilityEstimator.java` - Added socio-demographic and trip purpose effects
   - `BavariaBicycleUtilityEstimator.java` - Added high income effect
   - `BavariaPtUtilityEstimator.java` - Added complex waiting time interactions
   - `BavariaCarPassengerUtilityEstimator.java` - Added socio-demographic and trip purpose effects
   - `BavariaWalkUtilityEstimator.java` - NEW! Complete implementation

5. **Cost Models:**
   - `BavariaCarCostModel.java` - Simplified parking cost logic

6. **Module Registration:**
   - `BavariaModeChoiceModule.java` - Registered walk estimator

## References

The calibrated parameters are based on mode choice estimation for Munich and Bavaria, incorporating local survey data and observed travel behavior patterns.

For questions or issues, please refer to the main eqasim documentation or contact the development team.




