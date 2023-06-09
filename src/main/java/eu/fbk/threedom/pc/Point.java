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
package eu.fbk.threedom.pc;

import lombok.Getter;
import lombok.Setter;

import javax.vecmath.Vector3d;

public class Point extends Vector3d {

    @Setter @Getter private int r;
    @Setter @Getter private int g;
    @Setter @Getter private int b;

    @Getter @Setter private FileType type; // 0 photogrammetric, 1 lidar
    @Getter @Setter private PointClassification classification; // 0 1 2

    @Getter @Setter private double score;
    @Getter @Setter private float threshold;

    private double[] propertiesValues;
    private double[] propertiesNormValues;

    public Point(double x, double y, double z) {
        super.x = x; super.y = y; super.z = z;
        this.r = 0; this.g = 0; this.b = 0;
    }

    public Point(FileType type,  double x, double y, double z) {
        this.type = type;
        super.x = x; super.y = y; super.z = z;
        this.r = 0; this.g = 0; this.b = 0;

        propertiesValues = new double[type.getProps().length];
        propertiesNormValues = new double[type.getProps().length];
    }

    public Point(FileType type, double x, double y, double z, int r, int g, int b) {
        this.type = type;
        super.x = x; super.y = y; super.z = z;
        this.r = r; this.g = g; this.b = b;

        propertiesValues = new double[type.getProps().length];
        propertiesNormValues = new double[type.getProps().length];
    }

    public void move(double x, double y, double z){
        super.x = x; super.y = y; super.z = z;
    }

    public void move(Point p){
        super.x = p.x; super.y = p.y; super.z = p.z;
    }

    public String toStringOutput(boolean normalized, Point min){
        StringBuilder sb = new StringBuilder();

        if(this.type == FileType.PHOTOGRAMMETRIC) {
            sb.append(  String.valueOf(getX() + min.getX()) + " " +
                        String.valueOf(getY() + min.getY()) + " " +
                        String.valueOf(getZ() + min.getZ()) + " " +
                        String.valueOf(getR()) + " " +
                        String.valueOf(getG()) + " " +
                        String.valueOf(getB()) + " "    );
        }

        if(this.type == FileType.LIDAR) {
            sb.append(  String.valueOf(getX() + min.getX()) + " " +
                        String.valueOf(getY() + min.getY()) + " " +
                        String.valueOf(getZ() + min.getZ()) + " "    );
        }

        sb.append(classification.type + " ");

        if(normalized)
            for(double prop : propertiesNormValues)
                sb.append(String.valueOf(prop) + " ");
        else
            for(double prop : propertiesValues)
                sb.append(String.valueOf(prop) + " ");

        sb.append(score);

        return sb.toString();
    }

    public String toStringDoubleOutput(boolean normalized, Point min){
        StringBuilder sb = new StringBuilder();

        if(this.type == FileType.PHOTOGRAMMETRIC) {
            sb.append(  String.valueOf(getX() + min.getX()) + " " +
                    String.valueOf(getY() + min.getY()) + " " +
                    String.valueOf(getZ() + min.getZ()) + " " +
                    String.valueOf(getR()) + " " +
                    String.valueOf(getG()) + " " +
                    String.valueOf(getB()) + " "    );
        }

        if(this.type == FileType.LIDAR) {
            sb.append(  String.valueOf(getX() + min.getX()) + " " +
                    String.valueOf(getY() + min.getY()) + " " +
                    String.valueOf(getZ() + min.getZ()) + " "    );
        }

        if(normalized)
            for(double prop : propertiesNormValues)
                sb.append(String.valueOf(prop) + " ");
        else
            for(double prop : propertiesValues)
                sb.append(String.valueOf(prop) + " ");

        return sb.toString();
    }

    public String toString(Point min){
        return "point(" + (x + min.getX()) + ", " + (y + min.getY()) + ", "
                + (z + min.getZ()) + ")";
    }

    public void setProp(int propertyIndex, Double value){ propertiesValues[propertyIndex] = value;}
    public void setNormProp(int propertyIndex, float value){propertiesNormValues[propertyIndex] = value;    }
    public double getProp(int propertyIndex){return propertiesValues[propertyIndex];}
    public double getNormProp(int propertyIndex){return propertiesNormValues[propertyIndex];}

    public double length(Point p){
        return Math.sqrt(this.dot(p));
    }

    public Point addPoint(Point p){
        return  new Point(this.x + p.x, this.y + p.y, this.z + p.z);
    }

    public Point subPoint(Point p){
        return  new Point(this.x - p.x, this.y - p.y, this.z - p.z);
    }

    public Point mulPoint(double s){
        return  new Point(this.x * s, this.y * s, this.z * s);
    }

    public Point divPoint(double s){
        return  new Point(this.x * ( 1.0f / s ), this.y * ( 1.0f / s ), this.z * ( 1.0f / s ));
    }

    public static void main(String[] args){
        Point p = new Point(FileType.PHOTOGRAMMETRIC, 1, 2, 3);
        p.setProp(0, 666.0);

        System.out.println(p.toString());
        System.out.println("\tintensity: " + p.getProp(0));

        Point p1 = new Point(1, 1, 1);
        Point p2 = new Point(2, 2, 2);
        System.out.println("\n" + p1.toString());
        System.out.println(p2.toString());
        System.out.println("\tdist: " + p1.length(p2));
    }
}
