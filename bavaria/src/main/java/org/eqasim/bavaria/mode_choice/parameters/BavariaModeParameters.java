package org.eqasim.bavaria.mode_choice.parameters;

import org.eqasim.core.simulation.mode_choice.parameters.ModeParameters;

public class BavariaModeParameters extends ModeParameters {

	public class BavariaWalkParameters {
		public double isHighIncome;
		public double hasDrivingPermit;
		public double hasPtSubscription;
		public double isMunichResident;
	}

	public class BavariaBicycleParameters {
		public double isHighIncome;
	}

	public class BavariaCarParameters {
		public double isHighIncome;
		public double hasPtSubscription;
		public double isWorkTrip;
		public double isShoppingTrip;
	}

	public class BavariaCarPassengerParameters {
		public double alpha_u;
		public double betaInVehicleTravelTime_u_min;
		public double betaDrivingPermit_u;
		public double isHighIncome;
		public double isWorkTrip;
		public double isShoppingTrip;
	}

	public class BavariaPtParameters {
		public double betaDrivingPermit_u;
		public double onlyBus_u;
		public double isHighIncome;
		public double waitingTimeHighIncome;
		public double waitingTimeMunichResident;
		public double waitingTimeSubscription;
		public double waitingTimeShopping;
		public double isWorkTrip;
		public double isShoppingTrip;
	}

	public final BavariaWalkParameters bavariaWalk = new BavariaWalkParameters();

	public final BavariaBicycleParameters bavariaBicycle = new BavariaBicycleParameters();

	public final BavariaCarParameters bavariaCar = new BavariaCarParameters();

	public final BavariaCarPassengerParameters bavariaCarPassenger = new BavariaCarPassengerParameters();

	public final BavariaPtParameters bavariaPt = new BavariaPtParameters();

	
	public double betaAccessTime_u_min;

	public static BavariaModeParameters buildDefault() {
		BavariaModeParameters parameters = new BavariaModeParameters();

		// Access
		// not specifically estimated for Bavaria, using values from walk.betaTravelTime_u_min
		parameters.betaAccessTime_u_min = -0.0450421005231951; // IdF -0.031239;

		// Cost
		parameters.betaCost_u_MU = -0.0448921788117033; // IdF  -0.310998;
		parameters.lambdaCostEuclideanDistance = 0.0; // IdF -0.257501;
		parameters.referenceEuclideanDistance_km = 4.4;

		

		// Walk
		parameters.walk.alpha_u = -0.1; // uncalibrated 0.774228551231712; IdF 1.685152;
		parameters.walk.betaTravelTime_u_min = -0.0695442805909274; // IdF -0.162285;
		parameters.bavariaWalk.isHighIncome = -0.0217295300225373;
		parameters.bavariaWalk.hasDrivingPermit = -0.0318551303397705;
		parameters.bavariaWalk.hasPtSubscription = 0.0174505657228756;
		parameters.bavariaWalk.isMunichResident = 0.0105573744916928;


		// Bicycle
		parameters.bike.alpha_u = -1.2; // uncalibrated 0.318089528187347; IdF -2.927596;
		parameters.bike.betaTravelTime_u_min = -0.085880877435606; // IdF -0.093485;
		parameters.bavariaBicycle.isHighIncome = -0.0255287460190827;

		
		// Car
		parameters.car.alpha_u = 0.0; // IdF -0.201465;
		parameters.car.betaTravelTime_u_min = -0.0756368247750132; // IdF -0.042431;
		parameters.bavariaCar.isHighIncome = -0.0224982297973797;
		parameters.bavariaCar.hasPtSubscription = -0.0539508519243061;
		parameters.bavariaCar.isWorkTrip = 0.043746300263639;
		parameters.bavariaCar.isShoppingTrip = 0.0681864158380864;

		// Car passenger
		parameters.bavariaCarPassenger.alpha_u = -1.75; // uncalibrated -2.22497369171908; IdF -1.713201;
		parameters.bavariaCarPassenger.betaDrivingPermit_u = 0.0; // IdF -0.835542;
		parameters.bavariaCarPassenger.betaInVehicleTravelTime_u_min = -0.1; // uncalibrated -0.065198856305705; IdF -0.069976;
		parameters.bavariaCarPassenger.isHighIncome = -0.0398473945616337;
		parameters.bavariaCarPassenger.isWorkTrip = 0.0209967096068355;
		parameters.bavariaCarPassenger.isShoppingTrip = 0.0508046089649204;

		
		// PT
		parameters.pt.alpha_u = -0.36; // uncalibrated -0.284650026405347; IdF 0.0;
		parameters.pt.betaLineSwitch_u = -0.625046612374831; // IdF -0.417658;
		parameters.pt.betaInVehicleTime_u_min = -0.03603368; // uncalibrated -0.0450421005231951; -IdF 0.025501;
		parameters.pt.betaWaitingTime_u_min = -0.41123926919253; // IdF -0.021801;

		parameters.bavariaPt.betaDrivingPermit_u = 0.0; // IdF -0.531426;
		parameters.bavariaPt.onlyBus_u = 0.0; // IdF -1.416309;
		parameters.bavariaPt.isHighIncome = -0.0173904643343145;
		parameters.bavariaPt.isWorkTrip = 0.0425817408468503;
		parameters.bavariaPt.isShoppingTrip = 0.0441399147197244;
		parameters.bavariaPt.waitingTimeShopping = -0.105687502807207;
		parameters.bavariaPt.waitingTimeHighIncome = -0.152965571520264;
		parameters.bavariaPt.waitingTimeMunichResident = 0.0990869975359573;
		parameters.bavariaPt.waitingTimeSubscription = 0.413200821337636;


		return parameters;
	}
}

