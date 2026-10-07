package com.shilapi.xcertplay.tools;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs with a desktop JDK; fake setters throw if the diagnostic ever tries to call them. */
public final class BydAmbientLightProbeTest {
    public static final class Settings {
        public static final int SET_HAS_INTERIOR_ATMOSPHERE_LAMP = 1;
        public static final int SET_IAL_COLOR_CONFIG = 2;
        public static final int SET_IAL_BRIGHTNESS_CONFIG = 3;
        public static final int SET_IAL_AREA_CONFIG = 4;
        public static final int SET_INTERIOR_ATMOSPHERE_LAMP_AREA = 5;
        public static final int SET_IAL_FRONT_COLOR = 6;
        public static final int SET_IAL_BACK_COLOR = 7;
        public static final int SET_IAL_FRONT_BRIGHTNESS = 8;
        public static final int SET_IAL_BACK_BRIGHTNESS = 9;
        public static final int SET_INTERIOR_ATMOSPHERE_LAMP_COLOR_SET = 10;
        public static final int SET_INTERIOR_ATMOSPHERE_LAMP_BRIGHTNESS_SET = 11;
        public static final int SET_INTERIOR_ATMOSPHERE_LAMP_AREA_SET = 12;
        public static final int UNRELATED_VEHICLE_VALUE = 13;
    }

    public static class Manager {
        final List<Integer> reads = new ArrayList<>();
        int writes;
        int frontColor = 7;
        int frontBrightness = 4;
        int area = 3;
        boolean deny;
        public int getInt(int device, int feature) {
            check(device == 1023, "wrong device");
            check(feature >= 1 && feature <= 9, "read outside lamp allowlist");
            reads.add(feature);
            if (deny) throw new SecurityException("must-not-be-copied-to-report");
            switch (feature) {
                case 1: return 1;
                case 2: return -1; // A raw capability bitmask must not be mistaken for a color count.
                case 3: return 63;
                case 4: return 7;
                case 5: return area;
                case 6: return frontColor;
                case 7: return 11;
                case 8: return frontBrightness;
                case 9: return 2;
                default: throw new AssertionError();
            }
        }
        public int setIntArray(int device, int[] ids, int[] values) {
            writes++;
            throw new AssertionError("lamp setter invoked");
        }
        public int setInt(int device, int id, int value) {
            writes++;
            throw new AssertionError("lamp setter invoked");
        }
    }

    public static final class MissingSettings {}
    public static final class ReadOnlyManager {
        public int getInt(int device, int id) { return 1; }
    }

    private static String report(Class<?> settings, Object manager) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, "UTF-8");
        BydAmbientLightProbe.inspect(settings, manager.getClass(), manager, out);
        return bytes.toString("UTF-8");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--main-exit")) {
            new Thread(() -> {
                try { Thread.sleep(60_000); } catch (InterruptedException ignored) {}
            }, "simulated-framework-thread").start();
            BydAmbientLightProbe.main(new String[0]);
            return;
        }
        Manager manager = new Manager();
        String result = report(Settings.class, manager);
        check(manager.reads.size() == 9, "must inspect all nine lamp read fields");
        check(result.contains("pr345.snapshot_shape_matches=true"), "valid snapshot rejected");
        check(result.contains("pr345.write_api_present=true"), "setter signature missing");
        check(result.contains("read.SET_IAL_COLOR_CONFIG=-1 (0xffffffff)"), "lost raw capability bits");
        check(result.contains("vehicle_compatibility=unverified"), "must not claim tested compatibility");
        check(result.contains("write_authorization=untested"), "must not claim write permission");
        check(!result.contains("UNRELATED"), "unrelated vehicle field leaked");

        manager.frontColor = 32;
        check(report(Settings.class, manager).contains("pr345.snapshot_shape_matches=false"), "31-color limit guessed away");
        manager.frontColor = 7;
        manager.frontBrightness = 0;
        check(report(Settings.class, manager).contains("pr345.snapshot_shape_matches=false"), "unknown brightness accepted");
        manager.frontBrightness = 4;
        manager.area = 4;
        check(report(Settings.class, manager).contains("pr345.snapshot_shape_matches=false"), "unknown area accepted");

        manager.deny = true;
        result = report(Settings.class, manager);
        check(result.contains(".error=SecurityException"), "denial not reported");
        check(result.contains("pr345.snapshot_shape_matches=false"), "denied reads treated as valid");
        check(!result.contains("must-not-be-copied"), "SDK error message leaked");
        manager.reads.clear();
        result = report(MissingSettings.class, manager);
        check(manager.reads.isEmpty(), "missing IDs caused guessed reads");
        check(result.contains("pr345.write_api_present=false"), "missing write IDs accepted");
        check(manager.writes == 0, "must never invoke a lamp setter");
        result = report(Settings.class, new ReadOnlyManager());
        check(result.contains("pr345.write_api_present=false"), "missing setter signature accepted");
        Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java",
            "-cp", System.getProperty("java.class.path"), BydAmbientLightProbeTest.class.getName(),
            "--main-exit").redirectErrorStream(true).start();
        if (!child.waitFor(5, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("helper must exit despite surviving framework threads");
        }
        check(child.exitValue() == 0, "helper exit failed");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = child.getInputStream().read(buffer)) != -1) output.write(buffer, 0, count);
        check(output.toString("UTF-8").contains("probe.complete=true"), "helper exited before publishing its report");
        System.out.println("PASS: bounded lamp reads, no setters, raw capabilities, range/denial/missing-API handling, process exit");
    }
}
