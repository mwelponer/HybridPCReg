/**
 * Hybrid Registration (C) 2019 is a command line software designed to
 * analyze, co-register and filter airborne point clouds acquired by LiDAR sensors
 * and photogrammetric algorithm.
 * Copyright (C) 2019  Michele Welponer, mwelponer@gmail.com (Fondazione Bruno Kessler)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.
 * If not, see <https://www.gnu.org/licenses/> and file GPL3.txt
 *
 * -------------
 * IntelliJ Program arguments:
 * $ContentRoot$/resources/f1.txt $ContentRoot$/resources/f2.txt 1f -w -v
 */
package eu.fbk.threedom.pcFilter;

import eu.fbk.threedom.pc.FileType;
import eu.fbk.threedom.pc.Point;
import eu.fbk.threedom.pc.PointClassification;
import eu.fbk.threedom.utils.Combinator;
import eu.fbk.threedom.utils.Stats;
import org.json.JSONArray;
import org.json.JSONObject;
import org.apache.commons.io.FilenameUtils;
import org.kohsuke.args4j.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public class Main {

    @Argument(index=0, required = true, metaVar = "photo_file") File inFile1;
    @Argument(index=1, required = true, metaVar = "lidar_file") File inFile2;
    @Argument(index=2, required = true, metaVar = "voxelSide") Float voxelSide;
    @Option(name = "-o", aliases = { "--output" }, metaVar = "output") String outFile;
    @Option(name = "-w", aliases = { "--overwrite" }, metaVar = "overWrite") Boolean overWrite;
    @Option(name = "-v", aliases = { "--verbose" }, metaVar = "verbose") Boolean verbose;

    public static boolean DEBUG;
    private static final int RANDOM_POINTS_NUMBER = 1000;
    private static final float RANDOM_POINTS_CUBE_SIZE = 100;
    private static final String RANDOM_FILE1_HEADER = "// X Y Z R G B Class NumberOfReturns PIntensity";
    private static final String RANDOM_FILE2_HEADER = "// X Y Z Class LIntensity dZVariance ScanAngleRank EchoRatio";

    private static String filePath, fileName, fn1, fn2;

    // timer
    private static long start;
    private File outFile1, outFile2;
    public static JSONObject config;

    private static PcFilter pcf;
    private HashMap<String, Float> voxelDensityStats;

    private static Set<Integer> intersectionSet;
    private static Set<Integer> filteredIntersectionSet;
    private static Set<Integer> scoredFilteredIntersectionSet;

    private Scanner scanner;
    private int menuLevel;
    private int backIndex;
    private Stack<Integer> history;

    private String getNotice(){
        String notice = "";

        notice += "Hybrid Registration (C) 2019 Michele Welponer - Fondazione Bruno Kessler\n";
        notice += "This program comes with ABSOLUTELY NO WARRANTY;\n";
        notice += "This is free software, and you are welcome to redistribute it\n";
        notice += "under certain conditions;\n\n";

        return notice;
    }

    private void parseArgs(String[] args) {
        CmdLineParser parser = new CmdLineParser(this);

        verbose = false;

        try {
            // parse the arguments.
            parser.parseArgument(args);

            DEBUG = verbose;
            config = null;
        } catch( CmdLineException e ) {
            // if there's a problem in the command line,
            // you'll getQTB this exception. this will report
            // an error message.
            System.err.print(getNotice() + "Usage: hyRe");
            parser.printSingleLineUsage(System.err);

            // print the list of available options
            parser.printUsage(System.err);
            System.err.println();
            System.err.print("  voxelSide: the lenght of the voxel cube\n");

            // print option sample. This is useful some time
            System.err.println("\nExample:\n  pcFilter photo_file lidar_file 1.0f -v" + parser.printExample(OptionHandlerFilter.ALL));

            System.err.println();
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private void createPcFilter(float voxelSide){
        ///////////////////////////////////////////////////////
        // create the structure
        ///////////////////////////////////////////////////////
        //start = System.currentTimeMillis();
        pcf = new PcFilter(inFile1, inFile2, voxelSide);
        //Stats.printElapsedTime(start, "..voxel grid created");
    }

    private void printStatistics(boolean verbose){
        ///////////////////////////////////////////////////////////////////////
        // PRINT PROPERTIES STATISTICS
        System.out.println("\n///////////////////////////////////////////////////////\n// PROPERTIES STATISTICS");

        ArrayList<Point> pointList;
        String[][] props = pcf.getProperties();

        // EVALUATE PROPERTY MED/MAD
        for(FileType ft : FileType.values()) {
            //System.out.println("\n.." + ft.name());
            start = System.currentTimeMillis();

            for(int p=0; p < props[ft.ordinal()].length; p++) {
                String prop = props[ft.ordinal()][p];

                List<Point> points = (voxelSide != 0) ? pcf.getPoints(ft, true) : pcf.getPoints(ft, false);

                // transform arrayList to array
                double[] values = new double[points.size()];

                if(values.length == 0) break;

                int n = 0;
                for (Point pnt : points)
                    values[n++] = pnt.getNormProp(p);

                if (verbose)
                    System.out.println(".." + prop + " values (normalized) " + Arrays.toString(values));
                else
                    System.out.println(".." + prop + " values (normalized) " + values.length + " values");

                double med = Stats.median(values, values.length);
                double mad = Stats.mad(values, values.length);
                System.out.println("....med: " + med + "\n....mad: " + mad
                        + "\n....sigmaM: " + (mad * 1.4826)
                        + "\n....3sigmaM: " + 3 * (mad * 1.4826));
            }
            Stats.printElapsedTime(start, "processed");
        }

        // EVALUATE PROPERTY MED/MAD (CLASS)
        for(FileType ft : FileType.values()) {
            System.out.println("\n.." + ft.name());
            start = System.currentTimeMillis();

            for(int k=0; k < props[ft.ordinal()].length; k++) {
                String prop = props[ft.ordinal()][k];
                System.out.println("...." + prop);

                for (PointClassification pc : PointClassification.values()) {

                    ArrayList propValues = new ArrayList();
                    if(voxelSide != 0) {
                        Set<Integer> voxelSet = pcf.getVGrid().getVoxels(ft, pc);

                        if(voxelSet == null) continue;

                        // extract values from voxels
                        for (int v : voxelSet) {
                            // list of points of fileType ft, in voxel v, of class pc
                            pointList = (ArrayList<Point>) pcf.getPoints(ft, v, pc);
                            for (Point p : pointList)
//                            propValues.add(p.getProp(prop));
                                propValues.add(p.getNormProp(k));

                        }
                    }else{
                        pointList = (ArrayList<Point>) pcf.getPoints(ft, pc);
                        for (Point p : pointList)
                            propValues.add(p.getNormProp(k));
                    }

                    if(propValues.isEmpty()) break;

                    if(verbose)
                        System.out.println("......" + pc.name() + " (normalized) " + propValues);
                    else
                        System.out.println("......" + pc.name() + " (normalized) " + propValues.size() + " values");

                    // transform arrayList to array
                    float[] values = new float[propValues.size()];
                    values = new float[propValues.size()];
                    int n = 0;
                    for (Object p : propValues)
                        values[n++] = (float) p;

                    float med = Stats.median(values, values.length);
                    float mad = Stats.mad(values, values.length);
                    System.out.println("........med: " + med + "\n........mad: " + mad
                            + "\n........sigmaM: " + (mad * 1.4826)
                            + "\n........3sigmaM: " + 3*(mad * 1.4826) );
                }
            }

            Stats.printElapsedTime(start, "processed");
        }
    }

    private void printVoxelDensity(boolean verbose){
        ///////////////////////////////////////////////////////
        // AVERAGE VOXEL DENSITY
        System.out.println("\n///////////////////////////////////////////////////////\n// VOXEL DENSITY");

        ArrayList<Point> pointList;
        voxelDensityStats = new HashMap<>();

        int numberOfPointsInVoxel_sum;

        // cycle on photogrammetry/lidar file
        for (FileType ft : FileType.values()) {
            start = System.currentTimeMillis();
            System.out.println(ft.name() + " cloud");

            ///////////////////////////////////////////////////////
            // evaluate average voxel density
            start = System.currentTimeMillis();
            Set<Integer> voxelSet = pcf.getVGrid().getVoxels(ft);
            if(voxelSet == null) continue;

            if (verbose)
                System.out.println("..points are contained in voxels " + voxelSet);

            numberOfPointsInVoxel_sum = 0;

            // cycle on voxel to evaluate mean
            for (int v : voxelSet) {
                pointList = (ArrayList<Point>) pcf.getPoints(ft, v);

                numberOfPointsInVoxel_sum += pointList.size();
                if (verbose) {
                    System.out.println("..voxel " + v);//+ " " + pointList);
                    for (Point p : pointList) System.out.println("...." + p.toString(pcf.getCoordShift()));
                }
            }
            float mean = (float)numberOfPointsInVoxel_sum / voxelSet.size();
            System.out.println("..mean of voxel point density -> " + mean);

            // cycle on voxel to evaluate std
            float std = 0;
            for (int v : voxelSet) {
                pointList = (ArrayList<Point>) pcf.getPoints(ft, v);
                std +=  (float)Math.pow((pointList.size() - mean), 2);
            }
            std = (float)Math.sqrt(std / voxelSet.size());
            System.out.println("..std of voxel point density -> " + std);

            voxelDensityStats.put(ft.name() + "_" + "density_mean", mean);
            voxelDensityStats.put(ft.name() + "_" + "density_std", std);

            Stats.printElapsedTime(start, "processed");



            ///////////////////////////////////////////////////////
            // evaluate average per class voxel density
            for (PointClassification pclass : PointClassification.values()) {
                numberOfPointsInVoxel_sum = 0;
                voxelSet = pcf.getVGrid().getVoxels(ft, pclass);

                if(voxelSet == null) continue;

                if (verbose)
                    System.out.println(".." + pclass.name() + " points are contained in voxels " + voxelSet);
                else
                    System.out.println(".." + pclass.name() + " points contained in " + voxelSet.size() + " voxels ");

                // cycle on voxel to evaluate mean
                mean = 0;
                for (int v : voxelSet) {
                    pointList = (ArrayList<Point>) pcf.getPoints(ft, v, pclass, false, verbose);
                    numberOfPointsInVoxel_sum += pointList.size();

                    // filetype_f class_i voxel_v density
                    voxelDensityStats.put(ft.name() + "_" + pclass.name() + "_v" + v + "_density", (float)pointList.size());

                    if (verbose) {
                        System.out.println("....voxel " + v);//+ " " + pointList);
                        for (Point p : pointList) System.out.println("......" + p.toString(pcf.getCoordShift()));
                    }
                }
                if(voxelSet.size() > 0)
                    mean = (float)numberOfPointsInVoxel_sum / voxelSet.size();
                System.out.println("....mean of voxel point density " + mean);

                // cycle on voxel to evaluate std
                std = 0;
                for (int v : voxelSet) {
                    pointList = (ArrayList<Point>) pcf.getPoints(ft, v, pclass, false, verbose);
                    std +=  Math.pow((pointList.size() - mean), 2);
                }
                if(voxelSet.size() > 0)
                    std = (float)Math.sqrt(std / voxelSet.size());
                System.out.println("....std of voxel point density " + std);

                voxelDensityStats.put(ft.name() + "_" + pclass.name() + "_density_mean", mean);
                voxelDensityStats.put(ft.name() + "_" + pclass.name() + "_density_std", std);
//                    System.out.println("C0_density_mean: " + voxelDensityStats.get("C0_density_mean"));
//                    System.out.println("C0_density_std: " + voxelDensityStats.get("C0_density_std"));
            }
            Stats.printElapsedTime(start, "processed");
        }

    }

    private void printMultiFileTypeVoxels(boolean verbose){
        ///////////////////////////////////////////////////////
        // PHOTO/LIDAR INTERSECTION IN EACH VOXEL
        System.out.println("\n///////////////////////////////////////////////////////\n// PHOTO/LIDAR INTERSECTION IN EACH VOXEL");

        start = System.currentTimeMillis();
        // initialize with all the voxels
        intersectionSet = pcf.getVGrid().getVoxels( new FileType[] {FileType.PHOTOGRAMMETRIC,FileType.LIDAR} );
        // cycle on photogrammetry/lidar file and find the intersection
        for (FileType ft : FileType.values()) {
            if(verbose)
                System.out.println(".." + ft + " set " + pcf.getVGrid().getVoxels(ft));
            else
                System.out.println(".." + ft + " " + pcf.getVGrid().getVoxels(ft).size() + " voxels");
            intersectionSet.retainAll(pcf.getVGrid().getVoxels(ft));
        }


        if(verbose)
            System.out.println("photo/lidar voxels sets intersection set " + intersectionSet.toString());
        else
            System.out.println("photo/lidar points are contained in " +
                    + intersectionSet.size() + " voxels");

        if(verbose)
            for (int v : intersectionSet) {
                System.out.println("..voxel " + v);
                //System.out.println("....points " + pcf.getVGrid().getPoints(v));
            }
        Stats.printElapsedTime(start, "processed");
    }

    private void printMultiClassVoxels(boolean verbose){
        ///////////////////////////////////////////////////////
        // MULTICLASS IN EACH INTERSECTION VOXEL
        System.out.println("\n///////////////////////////////////////////////////////\n// MULTICLASS IN EACH INTERSECTION VOXEL");

        String[] classes = Stream.of(PointClassification.values()).map(PointClassification::name).toArray(String[]::new);
        //for(String cls : classes) System.out.println(cls);

        // all two places combinations
        List<String[]> combinations = Combinator.generate(classes, 2);
        // add the 3 places case
        combinations.add(classes);

        // cycle on photogrammetry/lidar file
        for (FileType ft : FileType.values()) {
            System.out.println("...." + ft + " cloud");

            start = System.currentTimeMillis();

            for(String[] combination : combinations)
                System.out.println("......" + Arrays.toString(combination)
                        + " combination in " + pcf.getVGrid().getVoxels(ft, combination).size() + " voxels");

            Stats.printElapsedTime(start, "processed");
        }
    }

    private void printFilteredVoxels(boolean verbose){
        ////////////////////////////////////////////////////////////////////////////////
        // FILTERED INTERSECTION SET
        System.out.println("\n///////////////////////////////////////////////////////\n// FILTERED INTERSECTION SET");

        System.out.println("photo/lidar intersection voxels where " +
                "at least one class for both filetypes -> voxel density >= voxel density mean");

        filteredIntersectionSet = new TreeSet<>();
        start = System.currentTimeMillis();

        for (int v : intersectionSet) {
            if(verbose)
                System.out.println("..v" + v);
            boolean passed = true;
            for (PointClassification pclass : PointClassification.values()) {
                if(verbose)
                    System.out.println("...." + pclass);
                for (FileType ft : FileType.values()) {
                    if(verbose)
                        System.out.println("......" + ft);

                    float ftClVDensity = 0, ftClVDensityMean = 0;
                    if (voxelDensityStats.containsKey(ft + "_" + pclass + "_v" + v + "_density"))
                        ftClVDensity = voxelDensityStats.get(ft + "_" + pclass + "_v" + v + "_density");

                    if (voxelDensityStats.containsKey(ft + "_" + pclass + "_density_mean"))
                        ftClVDensityMean = voxelDensityStats.get(ft + "_" + pclass + "_density_mean");

                    //System.out.println("........ftClVDensity " + ftClVDensity);
                    //System.out.println("........ftClVDensityMean " + ftClVDensityMean);

                    if (ftClVDensityMean == 0 || ftClVDensity < ftClVDensityMean) {
                        passed = false; //System.out.println("one ft fails");
                        if(verbose)
                            System.out.println("........not passed");
                        break;
                    } else passed = true; //System.out.println("one ft succeed");

                    if(verbose)
                        System.out.println("........passed");
                }

                if(passed){
                    filteredIntersectionSet.add(v);
//                    if(verbose)
//                        System.out.println("....voxel passed");
                    break;
                }else{
//                    if(verbose)
//                        System.out.println("....voxel not passed");
                }
            }
        }

        if(verbose)
            System.out.println("..resulting voxel set -> " + filteredIntersectionSet.toString());
        else
            System.out.println("..resulting voxel count -> " + filteredIntersectionSet.size() + " voxels");

        Stats.printElapsedTime(start, "processed");
    }

    private void printScoredFilteredVoxels(boolean verbose){
        ////////////////////////////////////////////////////////////////////////////////
        // SCORED FILTERED INTERSECTION SET
        System.out.println("\n///////////////////////////////////////////////////////\n// SCORED FILTERED INTERSECTION SET");

        ArrayList<Point> pointList;
        scoredFilteredIntersectionSet = new TreeSet<>();
        start = System.currentTimeMillis();

        System.out.println("photo/lidar intersection voxels where " +
                "at least one class for both filetypes -> voxel density >= voxel density mean");
        for (Integer v : filteredIntersectionSet) {
            if(verbose)
                System.out.println("..v" + v);
            boolean passed = true;
            for (PointClassification pclass : PointClassification.values()) {
                if(verbose)
                    System.out.println("...." + pclass);
                for (FileType ft : FileType.values()) {
                    if(verbose)
                        System.out.println("......" + ft);
                    pointList = (ArrayList<Point>) pcf.getPoints(ft, v, pclass, true, verbose);
                    float density_mean = voxelDensityStats.get(ft + "_" + pclass + "_density_mean");
                    float density_std = voxelDensityStats.get(ft.name() + "_" + pclass.name() + "_density_std");

                    //System.out.println("........pointList.size() " + pointList.size());
                    //System.out.println("........density_mean - density_std " + (density_mean - density_std));

                    if (pointList.size() < (density_mean /*- density_std*/)) {
                        passed = false; //System.out.println("one ft fails");
                        if(verbose)
                            System.out.println("........not passed");
                        break;
                    } else passed = true; //System.out.println("one ft succeed");

                    if(verbose)
                        System.out.println("........passed");
                }

                if (passed) {
                    scoredFilteredIntersectionSet.add(v);
                    break;
                }
            }
        }

        if(verbose)
            System.out.println("..resulting voxel set -> " + scoredFilteredIntersectionSet.toString());
        else
            System.out.println("..resulting voxel count -> " + scoredFilteredIntersectionSet.size() + " voxels");

        Stats.printElapsedTime(start, "processed");
    }

    private void run() throws Exception {
        ///////////////////////////////////////////////////////
        // create an output file
        ///////////////////////////////////////////////////////
        filePath = FilenameUtils.getFullPath(inFile1.getPath());

        if(filePath.isEmpty())
            filePath = new File(Main.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI()).getParentFile().getPath();
        System.out.println("filePath:\n.." + filePath);

        fn1 = FilenameUtils.getBaseName(inFile1.getPath()); //System.out.println("fn1: " + fn1);
        fn2 = FilenameUtils.getBaseName(inFile2.getPath()); //System.out.println("fn2: " + fn2);

        ///////////////////////////////////////////////////////
        // read the config file
        File jsonfile = new File(filePath + File.separator + "config.json");
        readThresholdJson(jsonfile);

        ///////////////////////////////////////////////////////
        // use the random function to generate random points
        ///////////////////////////////////////////////////////
        if ((fn1.toString() + fn2.toString()).equals("rnd1rnd2")) {
            generateRandomData(RANDOM_POINTS_NUMBER, 0);
            generateRandomData(RANDOM_POINTS_NUMBER, 1);
        }

        ///////////////////////////////////////////////////////
        // create the structure
        ///////////////////////////////////////////////////////
        createPcFilter(voxelSide);

        ///////////////////////////////////////////////////////
        // SHOW DATA
        ///////////////////////////////////////////////////////
        ///////////////////////////////////////////////////////////////////////
        // PRINT PROPERTIES STATISTICS
        printStatistics(Main.DEBUG);

        /////////////////////////////////////////////
        // INTERACTIVE CONSOLE
        ////////////////////////////////////////////
        scanner = new Scanner(System.in);
        menuLevel = -1;
        backIndex = -1;
        int selected = 0;
        history = new Stack<>();

        do {
//            System.out.println("\n  .menuLevel : " + menuLevel);
//            System.out.println("  .backIndex : " + backIndex);
//            System.out.println("  .selected : " + selected);

            if (selected == backIndex) {
                menuLevel--;
                if(backIndex != -1) {
                    selected = history.pop();
                    backIndex = -1;
                }
            } else {
                menuLevel++;
                history.push(selected);
            }

//            System.out.println("  ..menuLevel : " + menuLevel);
//            System.out.println("  ..backIndex : " + backIndex);

            selected = printMenu(selected);
//            System.out.println("history: " + history);
//            System.out.println("  ...selected : " + selected);
        } while (true);
    }

    public void quit(){System.out.println("\n..Bye bye!"); System.exit(1);}

    public void printLocation(Point location){
        int voxel;

        voxel = pcf.getVoxelId(location.subPoint(pcf.getCoordShift()));

        if(voxel == -1)
            System.out.println("..the location " + location.toString() + " is outside of the bounding box");
        else
            System.out.println("..the location " + location.toString() + " falls into voxel " + voxel);
    }

    public void printPointsInVoxel(int voxel, boolean verbose){
        List<Point> points;

        for (FileType ft : FileType.values()) {
            System.out.println("\n.." + ft);

            if(verbose) {
                for (PointClassification pclass : PointClassification.values()) {
                    points = pcf.getPoints(ft, voxel, pclass);
                    //check if points is null
                    if (points == null || points.size() == 0) continue;

                    System.out.println("...." + pclass.name());

                    for (Point p : points)
                        System.out.println("......" + p.toString(pcf.getCoordShift()));
                }
            }else {
//                System.out.println("...." + points.size() + " points");
                for (PointClassification pclass : PointClassification.values()) {
                    points = pcf.getPoints(ft, voxel, pclass);
                    //check if points is null
                    if (points == null || points.size() == 0) continue;

                    System.out.println("...." + pclass.name() + " -> " + points.size() + " points");
                }
            }
        }
    }

    public void printPointsInClass(PointClassification pclass, boolean verbose){
        List<Point> points;

        for (FileType ft : FileType.values()) {
            System.out.println("\n.." + ft);

            if (verbose) {
                Set<Integer> voxelSet = pcf.getVGrid().getVoxels(ft, pclass);

                if(voxelSet == null || voxelSet.size() == 0) continue;

                // cycle on voxels
                for (int v : voxelSet) {
                    points = (ArrayList<Point>) pcf.getPoints(ft, v, pclass, false, verbose);

                    if(points == null || points.size() == 0) continue;

                    System.out.println("..voxel " + v);
                    for (Point p : points)
                        System.out.println("...." + p.toString(pcf.getCoordShift()));
                }
            }else {
                points = pcf.getPoints(ft, pclass);
                //check it points is null
                if (points == null || points.size() == 0 ) continue;

                System.out.println("...." + points.size() + " points");
            }
        }
    }

    public int printMenu(int selected){
        //System.out.println("  printMenu(" + selected + ")");
        int sel;
        boolean error = false;
        String yn;
        boolean verbose;

        switch(menuLevel) {
            case 0: System.out.println("\nmenu:\n 1. print info\n 2. options\n 3. quit");
                break;

            case 1:
                switch(selected){

                    /////////////////////////////////
                    // PRINT INFO
                    case 1:
                        while (true) {
                            //TODO: add menu voices: write points, show statistics, voxel density, etc..
                            System.out.println("\nprint info:\n 1. location\n 2. points in class\n 3. points in voxel\n 4. back");
                            if (!scanner.hasNextInt()) {
                                System.out.println("only integers allowed! ");
                                scanner.next(); // discard
                                continue;
                            }
                            sel = scanner.nextInt();

                            switch (sel) {

                                /////////////////////////////////
                                // LOCATION
                                case 1:
                                    String location;
                                    String[] coords;

                                    do {
                                        if(error)
                                            System.out.println("enter location (x,y,z) :");
                                        location = scanner.nextLine(); //System.out.println("location: " + location);

                                        error = false;
                                        if (location.isEmpty()) error = true;

                                        location = location.replaceAll("[^a-zA-Z0-9+ .,]|(?<!\\d)[.,]|[\\s+]", "");

                                        coords = location.split(",");
                                        if (coords.length != 3) error = true;
                                    } while (error);

                                    Point point = new Point(Double.parseDouble(coords[0]),
                                            Double.parseDouble(coords[1]),
                                            Double.parseDouble(coords[2]));

                                    printLocation(point);
                                    break;

                                /////////////////////////////////
                                // POINTS IN CLASS
                                case 2:
                                    int classType = -1;

                                    do {
                                        error = false;
                                        System.out.println("enter class (integer): ");
                                        // check integer
                                        if (!scanner.hasNextInt()) {
                                            System.out.println("only integers allowed! ");
                                            scanner.next(); // discard
                                            error = true; continue;
                                        } classType = scanner.nextInt();

                                        // check if class exsist
                                        if (PointClassification.parse(classType) == null) {error = true; continue;}

                                        break;
                                    } while (error);

                                    //TODO: write a function to ask user verbose
                                    do {
                                        System.out.println("print verbose (y/n): ");
                                        yn = scanner.next();

                                        error = false;
                                        if (!yn.equals("y") && !yn.equals("n")) error = true;
                                    } while (error);

                                    verbose = yn.equals("y") ? true : false;

                                    printPointsInClass(PointClassification.parse(classType), verbose);

                                    break;

                                /////////////////////////////////
                                // POINTS IN VOXEL
                                case 3:
                                    int voxel = -1;

                                    do{
                                        error = false;
                                        System.out.println("enter voxel (integer): ");
                                        // check integer
                                        if (!scanner.hasNextInt()) {
                                            System.out.println("only integers allowed! ");
                                            scanner.next(); // discard
                                            error = true; continue;
                                        }
                                        voxel = scanner.nextInt();

                                        // check if voxel exsist
                                        if (voxel < 0 || voxel >= pcf.getVGrid().getSize()) {
                                            System.out.println("the voxel doens't exsist!");
                                            error = true; continue;
                                        }
                                        break;
                                    }while(error);

                                    do {
                                        System.out.println("print verbose (y/n): ");
                                        yn = scanner.next();

                                        error = false;
                                        if (!yn.equals("y") && !yn.equals("n")) error = true;
                                    } while (error);

                                    verbose = yn.equals("y") ? true : false;
                                    printPointsInVoxel(voxel, verbose);
                                    break;

                                /////////////////////////////////
                                // BACK
                                case 4:
                                    history.clear(); menuLevel = -1;
                                    return selected;

                                default: System.out.println("print info: no menu selection available! "); continue;
                            }
                            break;
                        }

                        /////////////////////////////////
                        // FUNCTION COMPLETED
                        selected = history.pop();
                        menuLevel--;
                        return selected;

                    /////////////////////////////////
                    // OPTIONS
                    case 2:
                        while (true) {
                            System.out.println("\noptions:\n 1. set voxelSide\n 2. write files \n 3. back");
                            if (!scanner.hasNextInt()) {
                                System.out.println("only integers allowed!");
                                scanner.next(); // discard
                                continue;
                            }
                            sel = scanner.nextInt();

                            switch (sel) {

                                /////////////////////////////////
                                // VOXELSIDE
                                case 1:
                                    Float voxelSide;
                                    for(;;) {
                                        System.out.println("new voxelSide (float): ");
                                        if (!scanner.hasNextFloat()) {
                                            scanner.next(); // discard
                                            continue;
                                        }
                                        voxelSide = scanner.nextFloat();
                                        break;
                                    }

                                    //recompute VOXELGRID
                                    createPcFilter(voxelSide);
                                    break;

                                /////////////////////////////////
                                // COMPUTE STATISTICS & SAVE FILES
                                case 2:
                                    if(this.voxelSide != 0){
                                        do {
                                            System.out.println("print verbose (y/n): ");
                                            yn = scanner.next();

                                            error = false;
                                            if (!yn.equals("y") && !yn.equals("n")) error = true;
                                        } while (error);

                                        verbose = yn.equals("y") ? true : false;

                                        ///////////////////////////////////////////////////////
                                        // PRINT PROPERTIES STATISTICS
                                        printStatistics(verbose);
                                        ///////////////////////////////////////////////////////
                                        // AVERAGE VOXEL DENSITY
                                        printVoxelDensity(verbose);
                                        ///////////////////////////////////////////////////////
                                        // PHOTO/LIDAR INTERSECTION IN EACH VOXEL
                                        printMultiFileTypeVoxels(verbose);
                                        ///////////////////////////////////////////////////////
                                        // MULTICLASS IN EACH INTERSECTION VOXEL
                                        printMultiClassVoxels(verbose);
                                        ////////////////////////////////////////////////////////////////////////////////
                                        // FILTERED INTERSECTION SET
                                        printFilteredVoxels(verbose);
                                        ////////////////////////////////////////////////////////////////////////////////
                                        // SCORED FILTERED INTERSECTION SET
                                        printScoredFilteredVoxels(verbose);
                                    }

                                    /////////////////////////////////////////////
                                    // WRITE DATA
                                    ////////////////////////////////////////////
                                    String vs = this.voxelSide + "_";
                                    if(this.voxelSide == 0)
                                        writeOutput(pcf.getPoints(), vs+"out");
                                    else {
                                        // write in output files
                                        writeOutput(filteredIntersectionSet, false, vs+"filteredIntersection");
                                        writeOutput(scoredFilteredIntersectionSet, true, vs+"out");
                                    }

                                    break;

                                /////////////////////////////////
                                // BACK
                                case 3:
                                    history.clear(); menuLevel = -1;
                                    return selected;

                                default: System.out.println("options: no menu selection available! "); continue;
                            }
                            break;
                        }

                        /////////////////////////////////
                        // FUNCTION COMPLETED
                        selected = history.pop();
                        menuLevel--;
                        return selected;

                    case 3: quit();

                }

                history.pop();
                System.out.println("no menu selection available!");
                return -1;
        }

        while (true) {
            if (!scanner.hasNextInt()) {
                System.out.println("enter only integers! ");
                scanner.next(); // discard
                continue;
            }
            return scanner.nextInt();
        }
    }

    public void showVoxel(){
        System.out.println("\nat which coordinates [x, y, z] ?");
        int voxel;
        boolean error = true;
        String[] coords = null;

        while(error) {
            String choiche = scanner.nextLine();
            if(choiche.isEmpty()) continue;

            coords = choiche.split(",");
            if(coords.length != 3) {
                System.out.println("not a valid point");
                return;
            }

            error = false;
        }

        Point point = new Point(Double.parseDouble(coords[0]),
                            Double.parseDouble(coords[1]),
                            Double.parseDouble(coords[2]));

        voxel = pcf.getVoxelId(point.subPoint(pcf.getCoordShift()));

        if(voxel == -1)
            System.out.println("point is outside the bounding box");
        else
            System.out.println("in voxel " + voxel);
    }

    public void showPoints(boolean verbose){
        System.out.println("\nin which voxel?");

        List<Point> points;

        int choiche = scanner.nextInt();
        if(choiche < 0 || choiche > pcf.getVGrid().getSize()) {
            System.out.println("the voxel " + choiche + " doesn't exist");
            //showMainMenu();
        }

        for (FileType ft : FileType.values()) {
            System.out.println(ft);
            for (PointClassification pclass : PointClassification.values()) {

                points = pcf.getPoints(ft, choiche, pclass, false, verbose);

                if(points.size() > 0) {
                    if(verbose) {
                        System.out.println(".." + pclass);

                        for (Point p : points)
                            System.out.println("...." + p.toString(pcf.getCoordShift()));
                    }else{
                        System.out.println(".." + pclass + " -> " + points.size() + " points");
                    }
                }
            }
        }
    }

    public void writeOutput(List<Point> points, String label){
        Path out1 = null, out2 = null;

        try {
            if (outFile == null) {
                outFile1 = new File(filePath + File.separator + fn1 + "_" + label + ".txt");
                outFile2 = new File(filePath + File.separator + fn2 + "_" + label + ".txt");
            }
            //outFile = new File(filePath + File.separator + fn1 + "_" + fn2 + ".txt");
            System.out.println("\noutFile:\n.." + outFile1 + "\n.." + outFile2);

            out1 = Paths.get(outFile1.toURI()); Files.createFile(out1);
            out2 = Paths.get(outFile2.toURI()); Files.createFile(out2);

        } catch (IOException foee) {
            if (overWrite == null || !overWrite) {
                System.out.println("\nWARNING! the output file already exists");
                System.exit(1);
            }

            if(overWrite) System.out.println("..overWrite output file");
        }

        // write the output files
        BufferedWriter writer1 = null, writer2 = null;
        try {
            writer1 = new BufferedWriter(new FileWriter(outFile1, false));
            writer2 = new BufferedWriter(new FileWriter(outFile2, false));
        } catch (IOException e) {
            e.printStackTrace();
        }
        BufferedWriter bw;


        System.out.println("\nWrite files");
        start = System.currentTimeMillis();
        for(FileType ft : FileType.values()) {
            //System.out.println(".." + ft);

            String headerStr = Arrays.toString(pcf.getHeader(ft)).replaceAll(",", "");
            //headerStr = headerStr.substring(1, headerStr.length()-1); // remove square brackets []
            headerStr = headerStr.replace("[", "// ");
            headerStr = headerStr.replace("]", "");

            headerStr += " score";

            System.out.println(".." + ft + " header: " + headerStr);

            bw = (ft == FileType.PHOTOGRAMMETRIC) ? writer1 : writer2;
            try {
                bw.write(headerStr + "");
                bw.newLine();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (!points.isEmpty())
            for (Point p : points) {
//                        System.out.println("......point " + p.toString(pcf.getCoordShift()));
//                        System.out.println("........score " + p.getScore());
//                        System.out.println("........threshold " + p.getThreshold());

                try {
                    bw = (p.getType() == FileType.PHOTOGRAMMETRIC) ? writer1 : writer2;
                    // SELECT true if you want normalized values
                    bw.write(p.toStringOutput(false, pcf.getCoordShift()));
                    bw.newLine();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

        Stats.printElapsedTime(start, (label + " files written"));

        try {
            writer1.close();
            writer2.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void writeOutput(Set<Integer> voxels, boolean scoreCheck, String label){
        Path out1 = null, out2 = null;

        try {
            if (outFile == null) {
                outFile1 = new File(filePath + File.separator + fn1 + "_" + label + ".txt");
                outFile2 = new File(filePath + File.separator + fn2 + "_" + label + ".txt");
            }
            //outFile = new File(filePath + File.separator + fn1 + "_" + fn2 + ".txt");
            System.out.println("\noutFile:\n.." + outFile1 + "\n.." + outFile2);

            out1 = Paths.get(outFile1.toURI()); Files.createFile(out1);
            out2 = Paths.get(outFile2.toURI()); Files.createFile(out2);

        } catch (IOException foee) {
            if (overWrite == null || !overWrite) {
                System.out.println("\nWARNING! the output file already exists");
                System.exit(1);
            }

            if(overWrite) System.out.println("..overWrite output file");
        }

        // write the output files
        BufferedWriter writer1 = null, writer2 = null;
        try {
            writer1 = new BufferedWriter(new FileWriter(outFile1, false));
            writer2 = new BufferedWriter(new FileWriter(outFile2, false));
        } catch (IOException e) {
            e.printStackTrace();
        }
        BufferedWriter bw;


        System.out.println("\nWrite files");
        start = System.currentTimeMillis();
        for(FileType ft : FileType.values()) {
            //System.out.println(".." + ft);

            String headerStr = Arrays.toString(pcf.getHeader(ft)).replaceAll(",", "");
            //headerStr = headerStr.substring(1, headerStr.length()-1); // remove square brackets []
            headerStr = headerStr.replace("[", "// ");
            headerStr = headerStr.replace("]", "");

            headerStr += " score";

            System.out.println(".." + ft + " header: " + headerStr);

            bw = (ft == FileType.PHOTOGRAMMETRIC) ? writer1 : writer2;
            try {
                bw.write(headerStr + "");
                bw.newLine();
            } catch (IOException e) {
                e.printStackTrace();
            }

            List<Point> points = null;

            for(Integer v : voxels) {

                points = pcf.getPoints(ft, v, scoreCheck);

                if (!points.isEmpty())
                    for (Point p : points) {
//                        System.out.println("......point " + p.toString(pcf.getCoordShift()));
//                        System.out.println("........score " + p.getScore());
//                        System.out.println("........threshold " + p.getThreshold());

                        try {
                            // SELECT true if you want normalized values
                            bw.write(p.toStringOutput(false, pcf.getCoordShift()));
                            bw.newLine();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
            }
        }

        Stats.printElapsedTime(start, (label + " files written"));

        try {
            writer1.close();
            writer2.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void readThresholdJson(File file){
        System.out.println("\nreading " + file.getPath());
        StringBuilder sb = new StringBuilder();
        String str = null;
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            start = System.currentTimeMillis();
            while ((str = reader.readLine()) != null)
                sb.append(str);

            str = sb.toString();

            Stats.printElapsedTime(start, "file read");
        } catch (IOException e) {
            e.printStackTrace();
        }

        // read the input JSON file
        config = new JSONObject(str);
        JSONArray fileTypes = config.getJSONArray("fileTypes");

        for(Object ft : fileTypes) {
            JSONObject fileType = (JSONObject) ft;

            int typeId = fileType.getInt("typeId");
            String typeName = fileType.getString("name");
            System.out.println(".." + typeName + "(" + typeId + ")");

            JSONArray classTypes = fileType.getJSONArray("classTypes");

            for (Object cls : classTypes) {
                JSONObject classType = (JSONObject) cls;

                int classId = classType.getInt("classId");
                String className = classType.getString("name");
                float threshold = classType.getFloat("threshold");
                String formula = classType.getString("formula");
                System.out.println("...." + className + "(" + classId + ")\n"
                        + "......threashold: " + threshold+ "\n"
                        + "......formula: " + formula);
            }
        }
    }

    private void generateRandomData(int numberOfPoints, int type){
        System.out.println("\ngenerating random cloud file");
        start = System.currentTimeMillis();

        List<String> randomIn = new ArrayList<>();

        Random rn = new Random();

        randomIn.add(type == FileType.PHOTOGRAMMETRIC.ordinal() ? RANDOM_FILE1_HEADER : RANDOM_FILE2_HEADER);
        for (int i=0; i < numberOfPoints; i++){
            double rndFX = 0.0f + rn.nextDouble() * (RANDOM_POINTS_CUBE_SIZE - 0.0f);
            double rndFY = 0.0f + rn.nextDouble() * (RANDOM_POINTS_CUBE_SIZE / 10 - 0.0f);
            double rndFZ = 0.0f + rn.nextDouble() * (RANDOM_POINTS_CUBE_SIZE - 0.0f);

            int rndClassification = rn.nextInt(3);

//            randomIn.add(   String.valueOf(rndFX) + " " +
//                            String.valueOf(rndFY) + " " +
//                            String.valueOf(rndFZ) + " " +
//                            (type==0 ? "0 255 0 " : "") +
//                            String.valueOf(rndClassification) + " "
//            );

            if(type == FileType.PHOTOGRAMMETRIC.ordinal())

                randomIn.add(   String.valueOf(rndFX) + " " +
                                String.valueOf(rndFY) + " " +
                                String.valueOf(rndFZ) + " " +
                                (type==0 ? "0 255 0 " : "") +
                                String.valueOf(rndClassification) + " " +
                                String.valueOf(rn.nextInt(10)) + " " +
                                String.valueOf(rn.nextFloat())
                );
            else
                randomIn.add(   String.valueOf(rndFX) + " " +
                                String.valueOf(rndFY) + " " +
                                String.valueOf(rndFZ) + " " +
                                (type==0 ? "0 255 0 " : "") +
                                String.valueOf(rndClassification) + " " +
                                String.valueOf(rn.nextFloat()) + " " +
                                String.valueOf(rn.nextFloat()) + " " +
                                String.valueOf(rn.nextInt(359)) + " " +
                                String.valueOf(rn.nextFloat())
                );
        }

        fileName = type==0 ? "rnd1.txt" : "rnd2.txt";
        File rnd1 = new File(filePath + File.separator + fileName);
        Path rnd1_out = Paths.get(rnd1.toURI());

        try {
            if(!Files.exists(rnd1_out))
                Files.createFile(rnd1_out);
            Files.write(rnd1_out, randomIn);
        } catch (IOException e) {
            e.printStackTrace();
        }

        Stats.printElapsedTime(start, "processed " + fileName);
    }

    /**
     * Main method
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        Main main = new Main();
        main.DEBUG  = true;
        main.parseArgs(args);
        main.run();
    }
}
