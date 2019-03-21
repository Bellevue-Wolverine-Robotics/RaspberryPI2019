
/*----------------------------------------------------------------------------*/
/* Copyright (c) 2018 FIRST. All Rights Reserved.                             */
/* Open Source Software - may be modified and shared by FRC teams. The code   */
/* must be accompanied by the FIRST BSD license file in the root directory of */
/* the project.                                                               */
/*----------------------------------------------------------------------------*/

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;

import edu.wpi.cscore.CvSource;
import edu.wpi.cscore.MjpegServer;
import edu.wpi.cscore.UsbCamera;
import edu.wpi.cscore.VideoSource;
import edu.wpi.first.cameraserver.CameraServer;
import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.vision.VisionThread;

/*
   JSON format:
   {
       "team": <team number>,
       "ntmode": <"client" or "server", "client" if unspecified>
       "cameras": [
           {
               "name": <camera name>
               "path": <path, e.g. "/dev/video0">
               "pixel format": <"MJPEG", "YUYV", etc>   // optional
               "width": <video mode width>              // optional
               "height": <video mode height>            // optional
               "fps": <video mode fps>                  // optional
               "brightness": <percentage brightness>    // optional
               "white balance": <"auto", "hold", value> // optional
               "exposure": <"auto", "hold", value>      // optional
               "properties": [                          // optional
                   {
                       "name": <property name>
                       "value": <property value>
                   }
               ],
               "stream": {                              // optional
                   "properties": [
                       {
                           "name": <stream property name>
                           "value": <stream property value>
                       }
                   ]
               }
           }
       ]
   }
 */

public final class Main {
  private static String configFile = "/boot/frc.json";

  @SuppressWarnings("MemberName")
  public static class CameraConfig {
    public String name;
    public String path;
    public JsonObject config;
    public JsonElement streamConfig;
  }

  public static int team;
  public static boolean server;
  public static List<CameraConfig> cameraConfigs = new ArrayList<>();

  private static final Object visionlock = new Object();

  private Main() {
  }

  /**
   * Report parse error.
   */
  public static void parseError(String str) {
    System.err.println("config error in '" + configFile + "': " + str);
  }

  /**
   * Read single camera configuration.
   */
  public static boolean readCameraConfig(JsonObject config) {
    CameraConfig cam = new CameraConfig();

    // name
    JsonElement nameElement = config.get("name");
    if (nameElement == null) {
      parseError("could not read camera name");
      return false;
    }
    cam.name = nameElement.getAsString();

    // path
    JsonElement pathElement = config.get("path");
    if (pathElement == null) {
      parseError("camera '" + cam.name + "': could not read path");
      return false;
    }
    cam.path = pathElement.getAsString();

    // stream properties
    cam.streamConfig = config.get("stream");

    cam.config = config;

    cameraConfigs.add(cam);
    return true;
  }

  /**
   * Read configuration file.
   */
  @SuppressWarnings("PMD.CyclomaticComplexity")
  public static boolean readConfig() {
    // parse file
    JsonElement top;
    try {
      top = new JsonParser().parse(Files.newBufferedReader(Paths.get(configFile)));
    } catch (IOException ex) {
      System.err.println("could not open '" + configFile + "': " + ex);
      return false;
    }

    // top level must be an object
    if (!top.isJsonObject()) {
      parseError("must be JSON object");
      return false;
    }
    JsonObject obj = top.getAsJsonObject();

    // team number
    JsonElement teamElement = obj.get("team");
    if (teamElement == null) {
      parseError("could not read team number");
      return false;
    }
    team = teamElement.getAsInt();

    // ntmode (optional)
    if (obj.has("ntmode")) {
      String str = obj.get("ntmode").getAsString();
      if ("client".equalsIgnoreCase(str)) {
        server = false;
      } else if ("server".equalsIgnoreCase(str)) {
        server = true;
      } else {
        parseError("could not understand ntmode value '" + str + "'");
      }
    }

    // cameras
    JsonElement camerasElement = obj.get("cameras");
    if (camerasElement == null) {
      parseError("could not read cameras");
      return false;
    }
    JsonArray cameras = camerasElement.getAsJsonArray();
    for (JsonElement camera : cameras) {
      if (!readCameraConfig(camera.getAsJsonObject())) {
        return false;
      }
    }

    return true;
  }

  /**
   * Start running the camera.
   */
  public static VideoSource startCamera(CameraConfig config) {
    System.out.println("Starting camera '" + config.name + "' on " + config.path);
    CameraServer inst = CameraServer.getInstance();
    UsbCamera camera = new UsbCamera(config.name, config.path);
    MjpegServer server = inst.startAutomaticCapture(camera);
    Gson gson = new GsonBuilder().create();

    camera.setConfigJson(gson.toJson(config.config));
    camera.setConnectionStrategy(VideoSource.ConnectionStrategy.kKeepOpen);

    if (config.streamConfig != null) {
      server.setConfigJson(gson.toJson(config.streamConfig));
    }

    return camera;
  }

  /**
   * Main.
   */
  public static void main(String... args) {
    if (args.length > 0) {
      configFile = args[0];
    }

    // read configuration
    if (!readConfig()) {
      return;
    }

    // start NetworkTables
    NetworkTableInstance ntinst = NetworkTableInstance.getDefault();
    if (server) {
      System.out.println("Setting up NetworkTables server");
      ntinst.startServer();
    } else {
      System.out.println("Setting up NetworkTables client for team " + team);
      ntinst.startClientTeam(team);
    }

    NetworkTable nTable = ntinst.getTable("TestTable");

    NetworkTableEntry centerX = nTable.getEntry("centerX");
    NetworkTableEntry leftTarget = nTable.getEntry("leftTarget");
    NetworkTableEntry rightTarget = nTable.getEntry("rightTarget");
    NetworkTableEntry distanceTarget = nTable.getEntry("distanceTarget");

    centerX.setDouble(42);

    System.out.println(cameraConfigs.get(0).config);
    // start cameras
    List<VideoSource> cameras = new ArrayList<>();
    for (CameraConfig cameraConfig : cameraConfigs) {
      cameras.add(startCamera(cameraConfig));
    }
    int width = 320;
    int height = 240;

    cameras.get(0).setResolution(width, height);

    CvSource outputStream = CameraServer.getInstance().putVideo("HSV Binary", width, height);

    /*TODO
    1) Find angle
    2) filter targets (just steal nrg's code)
    3) create trajectory by joystick button click, then send trajectory back with network tables*/

    // start image processing on camera 0 if present

    System.out.println("Number of cameras: " + cameras.size());

    if (cameras.size() >= 1) {
      Thread t = new Thread(() -> {
      
        // loop forever
      });
      t.start();
      VisionThread visionThread = new VisionThread(cameras.get(0), new GreenReflectiveTape(), pipeline -> {
        if (pipeline.filterContoursOutput().size() > 1) {
          ArrayList<Rect> rects = new ArrayList<Rect>();
          ArrayList<Double> areas = new ArrayList<Double>();
          ArrayList<Target> targets = new ArrayList<Target>();
          for (MatOfPoint contour : pipeline.filterContoursOutput()) {
            rects.add(Imgproc.boundingRect(contour));
            areas.add(Imgproc.contourArea(contour));
            targets.add(new Target(contour));
          }
<<<<<<< HEAD
          rects.sort(new Comparator<Rect>() {

            @Override
            public int compare(Rect o1, Rect o2) {
              return (int) (o2.x - o1.x);
            }

          });

          Rect firstTape = rects.get(1);
          Rect secondTape = rects.get(0);
          int leftTargetX = firstTape.x + (firstTape.width / 2);
          int rightTargetX = secondTape.x + (secondTape.width / 2);
          int widthTarget = rightTargetX - leftTargetX;
          int centerTarget = leftTargetX + (widthTarget / 2);
          double distanceToRobotFT = (11.0 * ((88.0 * 43.0) / 11.0)) / widthTarget;
          double distanceToCenterFT = ((320/2) - centerTarget) * (11/widthTarget);
          //we are making a right triangle with the robot, the target, and the center of the camera frame. angle is found by dividing distance between target and center of camera frame by distance between robot and center of camera frame, then taking the inverse tan to find the angle. absolute value is taken because we're making a right triangle and triangles need positive side values.
          double angle = Math.atan(Math.abs((distanceToCenterFT/distanceToRobotFT)));
          
=======

          // rects.sort(new Comparator<Rect>() {

          //   @Override
          //   public int compare(Rect o1, Rect o2) {
          //     return (int) (o2.width - o1.width);
          //   }

          // });
          // ArrayList<Rect> targets = new ArrayList<Rect>();
          // for (int i = 0; i < rects.size(); i++){
          //   if (areas.get(i)/rects.get(i).area() > 0.5 && areas.get(i)/rects.get(i).area() < 0.75){
          //     targets.add(rects.get(i));
          //   }
          // }
          
          // Rect firstTape = rects.get(smallest);
          // Rect secondTape = rects.get(smallest + 1);
          // int widthTarget = (firstTape.x + (firstTape.width / 2)) + (secondTape.x + (secondTape.width / 2)) / 2;
          // int centerTarget = (firstTape.x + (firstTape.width / 2)) + (widthTarget / 2);
>>>>>>> e0776e229a2a3303264773e4146e8777aa64e410
          // String difference = "";
          // for (int i : differences) {
          // difference += i + ", ";
          // }
          // String rectsWidths = "";
          // for (Rect rect : rects) {
          // rectsWidths += rect.width + ", ";
          // }
          synchronized (visionlock) {
<<<<<<< HEAD
            // System.out.println("Next frame //////////");
            // System.out.println(widthTarget + "width px");
            // System.out.println(centerTarget + "center px");
            System.out.println(distanceToRobotFT + " distance between center and robot");
            System.out.println(distanceToCenterFT + " distance between target and center");
            System.out.println(angle + "angle between robot and target");
            
            // outputStream.putFrame(pipeline.hsvThresholdOutput());
            // centerX.setDouble(centerTarget);
            // leftTarget.setDouble(leftTargetX);
            // rightTarget.setDouble(rightTargetX);
            // distanceTarget.setDouble(distanceFT);
            // System.out.println(leftTarget.getDouble(0) + "left x");
            // System.out.println(rightTarget.getDouble(0) + "right x");
            System.out.println("I have at least one camera.");
=======
            // System.out.print("Rects: ");
            // System.out.println(rectsWidths);
            // System.out.print("Differences");
            // System.out.println(difference);
            //System.out.println(widthTarget + "px");
            System.out.println(rects.size());
            System.out.println(targets.size());
            outputStream.putFrame(pipeline.hsvThresholdOutput());
            //centerX.setDouble(centerTarget);
>>>>>>> e0776e229a2a3303264773e4146e8777aa64e410
          }
        } else {
          synchronized (visionlock) {
            System.out.println("I have at least one camera.");
            // outputStream.putFrame(pipeline.hsvThresholdOutput());
            // centerX.setDouble(width / 2);
          }
        }
      });
      visionThread.start();
    }

<<<<<<< HEAD

=======
    Thread t = new Thread(() -> {
    });
    t.start();
    // loop forever
>>>>>>> e0776e229a2a3303264773e4146e8777aa64e410
    for (;;) {
      try {
        Thread.sleep(10000);
      } catch (InterruptedException ex) {
        return;
      }
    }

  
}
}
