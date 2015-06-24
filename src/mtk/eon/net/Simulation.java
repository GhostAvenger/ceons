package mtk.eon.net;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Random;

import mtk.eon.ApplicationResources;
import mtk.eon.io.Logger;
import mtk.eon.jfx.tasks.SimulationTask;
import mtk.eon.net.algo.RMSAAlgorithm;
import mtk.eon.net.demand.AnycastDemand;
import mtk.eon.net.demand.Demand;
import mtk.eon.net.demand.DemandAllocationResult;
import mtk.eon.net.demand.generator.TrafficGenerator;

public class Simulation {

	Network network;
	RMSAAlgorithm algorithm;
	TrafficGenerator generator;
	Random linkCutter;
	
	double totalVolume, spectrumBlockedVolume, regeneratorsBlockedVolume, linkFailureBlockedVolume, regsPerAllocation, allocations, unhandledVolume;
	double modulationsUsage[] = new double[6];
	
	public Simulation(Network network, RMSAAlgorithm algorithm, TrafficGenerator generator) {
		this.network = network;
		this.algorithm = algorithm;
		this.generator = generator;
	}
	
	public void simulate(long seed, int demandsCount, double alpha, int erlang, boolean replicaPreservation, SimulationTask task) {
		generator.setErlang(erlang);
		generator.setSeed(seed);
		generator.setReplicaPreservation(replicaPreservation);
		network.setSeed(seed);
		linkCutter = new Random(seed);
		try {
			for (; generator.getGeneratedDemandsCount() < demandsCount;) {
				Demand demand = generator.next();
	
				if (linkCutter.nextDouble() < alpha / erlang)
					for (Demand reallocate : network.cutLink())
						if (reallocate.reallocate()) handleDemand(reallocate);
						else linkFailureBlockedVolume += reallocate.getVolume();
				else {
					handleDemand(demand);
					if (demand instanceof AnycastDemand) handleDemand(generator.next());
				}
				
				network.update();
				task.updateProgress(generator.getGeneratedDemandsCount(), demandsCount);
			}
		} catch (NetworkException e) {
			Logger.info("Network exception: " + e.getMessage());
			for (; generator.getGeneratedDemandsCount() < demandsCount;) {
				Demand demand = generator.next();
				unhandledVolume += demand.getVolume();
				if (demand instanceof AnycastDemand) unhandledVolume += generator.next().getVolume();
				task.updateProgress(generator.getGeneratedDemandsCount(), demandsCount);
			}
			totalVolume += unhandledVolume;
		}
		
		network.waitForDemandsDeath();
		
		Logger.info("Blocked Spectrum: " + (spectrumBlockedVolume / totalVolume) * 100 + "%");
		Logger.info("Blocked Regenerators: " + (regeneratorsBlockedVolume / totalVolume) * 100 + "%");
		Logger.info("Blocked Link Failure: " + (linkFailureBlockedVolume / totalVolume) * 100 + "%");
		File dir = new File("results");
		if (!dir.isDirectory()) dir.mkdir();
		File save = new File(dir, ApplicationResources.getProject().getName().toUpperCase() + "-" + generator.getName() + "-ERLANG" + erlang + "-ALPHA" + alpha + ".txt");
		try {
			PrintWriter out = new PrintWriter(save);
			out.println("Generator: " + generator.getName());
			out.println("Alpha: " + alpha);
			out.println("Demands count: " + demandsCount);
			out.println("Blocked Spectrum: " + (spectrumBlockedVolume / totalVolume) * 100 + "%");
			out.println("Blocked Regenerators: " + (regeneratorsBlockedVolume / totalVolume) * 100 + "%");
			out.println("Blocked Link Failure: " + (linkFailureBlockedVolume / totalVolume) * 100 + "%");
			out.println("Blocked Unhandled: " + (unhandledVolume / totalVolume) * 100 + "%");
			out.println("Blocked All: " + ((spectrumBlockedVolume / totalVolume) + (regeneratorsBlockedVolume / totalVolume) + (linkFailureBlockedVolume / totalVolume) + (unhandledVolume / totalVolume)) * 100 + "%");
			out.println("Average regenerators per allocation: " + (regsPerAllocation / allocations));
			out.close();
		} catch (IOException e) {
			Logger.debug(e);
		}
//		for (Modulation modulation : Modulation.values())
//			Logger.info(modulation.toString() + ": " + modulationsUsage[modulation.ordinal()]);
	}
	
	private void handleDemand(Demand demand) {
		DemandAllocationResult result = network.allocateDemand(demand);
		
		if (result.workingPath == null)
			switch (result.type) {
			case NO_REGENERATORS: regeneratorsBlockedVolume += demand.getVolume(); break;
			case NO_SPECTRUM: spectrumBlockedVolume += demand.getVolume(); break;
			}
		else {
			allocations++;
			regsPerAllocation += demand.getWorkingPath().getPartsCount() - 1;
			if (demand.getBackupPath() != null)
				regsPerAllocation += demand.getBackupPath().getPartsCount() - 1;
			double modulationsUsage[] = new double[6];
			for (PathPart part : result.workingPath) modulationsUsage[part.getModulation().ordinal()]++;
			for (int i = 0; i < 6; i++) {
				modulationsUsage[i] /= result.workingPath.getPartsCount();
				this.modulationsUsage[i] += modulationsUsage[i];
			}
		}
		totalVolume += demand.getVolume();
	}
}