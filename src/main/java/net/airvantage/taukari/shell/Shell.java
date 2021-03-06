package net.airvantage.taukari.shell;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import net.airvantage.taukari.dao.CsvInfo;
import net.airvantage.taukari.dao.CsvManager;
import net.airvantage.taukari.dao.RunManager;
import net.airvantage.taukari.dao.SampleWriter;
import net.airvantage.taukari.processor.Clusterer;
import net.airvantage.taukari.processor.HClusterer;
import net.airvantage.taukari.processor.Normalizer;
import net.airvantage.taukari.shell.ShellView.ReturnViewConvertor;

import org.apache.commons.io.IOUtils;

import asg.cliche.Command;
import asg.cliche.OutputConverter;
import asg.cliche.Param;
import asg.cliche.ShellFactory;

import com.google.common.collect.Sets;

/**
 * Interactive shell to load, process and in a limited fashion display data.
 */
public class Shell {

	public static final OutputConverter[] CLI_OUTPUT_CONVERTERS = { new ReturnViewConvertor() };

	private final RunManager runManager;

	private final CsvManager csvManager;

	private final Normalizer normalizer;

	private final Clusterer clusterer;

	private final HClusterer hClusterer;

	private String run;

	static String rootDirectory = "/tmp/taukari";

	public Shell(RunManager runManager, CsvManager csvManager, Normalizer normalizer, Clusterer clusterer,
			HClusterer hClusterer) {
		this.runManager = runManager;
		this.csvManager = csvManager;
		this.normalizer = normalizer;
		this.clusterer = clusterer;
		this.hClusterer = hClusterer;
	}

	public static void main(String[] args) throws IOException {
		Shell cli = new Shell(new RunManager(), new CsvManager(), new Normalizer(), new Clusterer(), new HClusterer());
		ShellFactory.createConsoleShell(
				"Taukari",
				"Welcome to Taukari. Current working directory is '" + rootDirectory
						+ "' use the 'root-set' command to change it.", cli).commandLoop();
	}

	@Command(abbrev = "rset", description = "Sets the  working directory")
	public void rootSet(String root) {
		rootDirectory = root;
	}

	@Command(abbrev = "rshow", description = "Shows the current working directory")
	public ShellView rootShow() {
		return new ShellView(rootDirectory);
	}

	@Command(abbrev = "ls", description = "lists existing runs")
	public ShellView list() {
		return new ShellView(runManager.list(rootDirectory));
	}

	@Command(abbrev = "lr", description = "list content of a run")
	public ShellView listRun() {
		ShellView rv = new ShellView();
		listRun(null, rv);
		return rv;
	}

	@Command(abbrev = "lr", description = "list content of a run")
	public ShellView listRun(@Param(name = "runName", description = "the run name") String runName) {
		ShellView rv = new ShellView();
		listRun(runName, rv);
		return rv;
	}

	private void listRun(String runName, ShellView rv) {
		if (runName == null || "@".equals(runName)) {
			if (run == null) {
				rv.addErr("no opened run, could not use relative notation");
			} else {
				rv.addMsg(runManager.list(rootDirectory + File.separator + run));
			}
		} else {
			rv.addMsg(runManager.list(rootDirectory + File.separator + runName));
		}
	}

	@Command(abbrev = "o", description = "opens a existing run")
	public ShellView open(@Param(name = "name", description = "the run name") String name) {
		ShellView rv = new ShellView();

		String path = rootDirectory + File.separator + name;

		unload(rv);

		if (runManager.exists(path)) {
			rv.addMsg("opened: " + name);
			run = name;
		} else {
			rv.addErr("failed to open run");
		}

		return rv;
	}

	@Command
	public ShellView close() {
		ShellView rv = new ShellView();
		unload(rv);
		return rv;
	}

	private void unload(ShellView rv) {
		if (run != null) {
			rv.addMsg("unloaded: " + run);
			run = null;
		}
	}

	@Command
	public ShellView show() {
		ShellView rv = new ShellView();

		if (run == null) {
			rv.addErr("no run to show!");
		} else {
			rv.addMsg(run);
		}

		return rv;
	}

	@Command
	public ShellView create(String name) {
		ShellView rv = new ShellView();

		String path = rootDirectory + File.separator + name;

		unload(rv);

		// check
		if (runManager.exists(path)) {
			rv.addErr("already existing run");
		} else {
			run = name;
			runManager.create(path);
			rv.addMsg("created: " + run);
			rv.addMsg("opened: " + run);
		}

		return rv;
	}

	// ----------------- normalize ---------------

	@Command(abbrev = "norm")
	public ShellView normalize() {
		ShellView rv = new ShellView();

		String input = rootDirectory + File.separator + run + File.separator + "input.csv";
		String norm = rootDirectory + File.separator + run + File.separator + "norm.csv";

		CsvInfo inputInfos = csvManager.inspectCSV(input, 0);

		try {
			normalizer.normalizeSamples(inputInfos.getColumns().length, //
					csvManager.getSampleIterable(input),//
					csvManager.getSampleWriter(inputInfos.getColumns(), norm));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			rv.addErr(e.getMessage());
		} catch (IOException e) {
			e.printStackTrace();
			rv.addErr(e.getMessage());
		}

		inspectCsv(norm, 5, rv);

		return rv;
	}

	// ----------------- cluster ---------------

	@Command(abbrev = "cluster")
	public ShellView cluster(int nb) {
		ShellView rv = new ShellView();

		cluster(rv, nb, null);

		return rv;
	}

	@Command(abbrev = "cluster")
	public ShellView cluster(int nb, String filter) {
		ShellView rv = new ShellView();

		String[] ff = null;
		if (filter != null) {
			ff = filter.split(",");
		}

		cluster(rv, nb, ff);

		return rv;
	}

	private void cluster(ShellView rv, int nb, String[] filter) {

		String norm = rootDirectory + File.separator + run + File.separator + "norm.csv";
		String clustered = rootDirectory + File.separator + run + File.separator + "cluster.csv";
		String centroids = rootDirectory + File.separator + run + File.separator + "centroids.csv";

		CsvInfo normInfos = csvManager.inspectCSV(norm, 0);
		if (normInfos == null) {
			rv.addErr("missing norm");
		} else {
			try {
				clusterer.clusterSamples(nb,//
						normInfos.getColumns(), //
						filter, //
						csvManager.getSampleIterable(norm),//
						csvManager.getSampleWriter(normInfos.getColumns(), clustered, true),// sample
						csvManager.getSampleWriter(normInfos.getColumns(), centroids, true)); // centroid
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				rv.addErr(e.getMessage());
			} catch (IOException e) {
				e.printStackTrace();
				rv.addErr(e.getMessage());
			}
		}

		inspectCsv(clustered, 0, rv);

	}

	// ----------------- hcluster ---------------

	@Command(abbrev = "hcluster")
	public ShellView hcluster(String filter) {
		ShellView rv = new ShellView();

		String[] ff = null;
		if (filter != null) {
			ff = filter.split(",");
		}

		hcluster(rv, ff);

		return rv;
	}

	private void hcluster(ShellView rv, String[] filter) {

		String norm = rootDirectory + File.separator + run + File.separator + "norm.csv";
		String clustered = rootDirectory + File.separator + run + File.separator + "hcluster.csv";

		CsvInfo normInfos = csvManager.inspectCSV(norm, 0);
		if (normInfos == null) {
			rv.addErr("missing norm");
		} else {
			List<String> clusteredColumns = new ArrayList<String>();
			for (String normCol : normInfos.getColumns()) {
				clusteredColumns.add(normCol);
			}
			clusteredColumns.add("cl_level");
			clusteredColumns.add("cl_nbLeaves");
			clusteredColumns.add("cl_radius");
			clusteredColumns.add("cl_isLeaf");

			SampleWriter sampleWriter = null;
			try {
				sampleWriter = csvManager.getSampleWriter(
						clusteredColumns.toArray(new String[clusteredColumns.size()]), clustered, true);
				hClusterer.clusterSamples(//
						normInfos.getColumns(), //
						filter, //
						csvManager.getSampleIterable(norm),//
						sampleWriter);
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				rv.addErr(e.getMessage());
			} catch (IOException e) {
				e.printStackTrace();
				rv.addErr(e.getMessage());
			} finally {
				IOUtils.closeQuietly(sampleWriter);
			}
		}

		inspectCsv(clustered, 0, rv);

	}

	// ----------------- inspect-csv ---------------

	@Command(abbrev = "inspect", description = "inspects a CSV file, giving some general informations")
	public ShellView inspectCsv(//
			@Param(name = "path", description = "path to the CSV file to inspect") String path) {
		ShellView rv = new ShellView();
		inspectCsv(path, 0, rv);
		return rv;
	}

	@Command(abbrev = "inspect", description = "inspects a CSV file, giving some general informations")
	public ShellView inspectCsv(//
			@Param(name = "path", description = "path to the CSV file to inspect") String path,//
			@Param(name = "naSamples", description = "number of samples to display") int nbSamples) {
		ShellView rv = new ShellView();
		inspectCsv(path, nbSamples, rv);
		return rv;
	}

	private void inspectCsv(String path, int nbSamples, ShellView rv) {
		String path_ = path(path, rv);
		if (path_ != null) {
			CsvInfo csvInfos = csvManager.inspectCSV(path_, nbSamples);
			if (csvInfos == null) {
				rv.addErr("could not inspect csv file");
			} else {
				rv.addMsg("Number of lines: " + csvInfos.getNbLines());
				rv.addMsg("Columns:  " + Arrays.toString(csvInfos.getColumns()));
				if (csvInfos.getSamples() != null) {
					for (String[] sample : csvInfos.getSamples()) {
						rv.addMsg("Sample:  " + Arrays.toString(sample));

					}
				}
			}
		}
	}

	// ----------------- load-csv ---------------

	@Command(abbrev = "load")
	public ShellView loadCsv(String name) {
		ShellView rv = new ShellView();
		loadCsv(name, null, null, rv);
		return rv;
	}

	@Command(abbrev = "load")
	public ShellView loadCsv(String name, double sampleRate) {
		ShellView rv = new ShellView();
		loadCsv(name, null, sampleRate, rv);
		return rv;
	}

	@Command(abbrev = "load")
	public ShellView loadCsv(//
			@Param(name = "fileName", description = "file name with full path") String name,//
			@Param(name = "variables") String variables, //
			@Param(name = "sampleRate") double sampleRate) {
		ShellView rv = new ShellView();

		Set<String> columns = null;
		if (variables != null) {
			String[] split = variables.split(",");
			if (split != null && split.length > 0) {
				columns = Sets.newHashSet(split);
			}
		}

		loadCsv(name, columns, sampleRate, rv);
		return rv;
	}

	private void loadCsv(String name, Set<String> columns, Double sampleRate, ShellView rv) {
		String destPath = rootDirectory + File.separator + run + File.separator + "input.csv";

		if (run == null) {
			rv.addErr("cannot load if no current run. Create or open one");
		} else {
			csvManager.copyCSV(name, destPath, columns, sampleRate);
			inspectCsv(destPath, 0, rv);
		}

	}

	// ----------------- load-random ---------------

	@Command(abbrev = "loadr")
	public ShellView loadRandom(int nbVariables, int nbSamples) {
		ShellView rv = new ShellView();

		String destPath = rootDirectory + File.separator + run + File.separator + "input.csv";

		if (run == null) {
			rv.addErr("cannot load if no current run. Create or open one");
		} else {
			csvManager.generateRandomCSV(destPath, nbVariables, nbSamples);
			inspectCsv(destPath, 0, rv);
		}

		return rv;
	}

	// ----------------- sample ---------------

	@Command
	public ShellView sample(String source, String dest, double rate) {
		ShellView rv = new ShellView();

		String sourcePath = path(source, rv);
		String destPath = path(dest, rv);

		if (run == null) {
			rv.addErr("cannot load if no current run. Create or open one");
		} else {
			csvManager.copyCSV(sourcePath, destPath, null, rate);
			inspectCsv(destPath, 0, rv);
		}

		return rv;
	}

	private String path(String path, ShellView rv) {
		String ret = path;

		if (path.startsWith("@")) {
			if (run == null) {
				ret = null;
				rv.addErr("relative path requires an open run");
			} else {
				ret = rootDirectory + File.separator + run + File.separator + path.substring(1, path.length());
			}
		}

		return ret;
	}
}
