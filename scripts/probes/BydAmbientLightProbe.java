package com.shilapi.xcertplay.tools;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** One-shot shell-UID inspection. No lamp setter, subscription or persistent service is invoked. */
public final class BydAmbientLightProbe {
    static final int PR345_DEVICE = 1023;
    static final String[] READS = {
        "SET_HAS_INTERIOR_ATMOSPHERE_LAMP", "SET_IAL_COLOR_CONFIG",
        "SET_IAL_BRIGHTNESS_CONFIG", "SET_IAL_AREA_CONFIG",
        "SET_INTERIOR_ATMOSPHERE_LAMP_AREA", "SET_IAL_FRONT_COLOR",
        "SET_IAL_BACK_COLOR", "SET_IAL_FRONT_BRIGHTNESS", "SET_IAL_BACK_BRIGHTNESS"
    };
    static final String[] WRITE_IDS = {
        "SET_INTERIOR_ATMOSPHERE_LAMP_COLOR_SET",
        "SET_INTERIOR_ATMOSPHERE_LAMP_BRIGHTNESS_SET",
        "SET_INTERIOR_ATMOSPHERE_LAMP_AREA_SET"
    };

    private BydAmbientLightProbe() {}

    public static void main(String[] args) {
        PrintStream out = System.out;
        out.println("DIPLAY_BYD_AMBIENT_PROBE_V1");
        out.println("lamp_writes_performed=false");
        out.println("vehicle_compatibility=unverified");
        if (args.length != 0) {
            out.println("probe.error=unexpected-arguments");
            out.println("probe.complete=false");
            return;
        }
        Thread deadline = new Thread(() -> {
            try { Thread.sleep(20_000); } catch (InterruptedException done) { return; }
            out.println("probe.error=deadline");
            out.println("probe.complete=false");
            out.flush();
            System.exit(2);
        }, "ambient-probe-deadline");
        deadline.setDaemon(true);
        deadline.start();
        try {
            Class<?> version = Class.forName("android.os.Build$VERSION");
            out.println("android.sdk=" + version.getField("SDK_INT").getInt(null));
            TreeMap<String, Integer> catalog = new TreeMap<>();
            String constantsName = "android.hardware.bydauto.BYDAutoConstants";
            try { collectConstants(Class.forName(constantsName), catalog, 0); }
            catch (ClassNotFoundException missing) { out.println("probe.constants.error=ClassNotFoundException"); }
            Class<?> settings = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds$Setting");
            collectConstants(settings, catalog, 0);
            for (Map.Entry<String, Integer> entry : catalog.entrySet()) {
                out.println("constant." + entry.getKey() + "=" + raw(entry.getValue()));
            }
            // Inspect the exact device used by PR #345, never substitute an arbitrary device id.
            Integer device = catalog.get(constantsName + ".BYDAUTO_DEVICE_SETTING");
            if (device != null && device != PR345_DEVICE) {
                out.println("pr345.device_matches=false");
                out.println("probe.error=setting-device-differs");
                return;
            }
            out.println("pr345.device=" + PR345_DEVICE);
            out.println("pr345.device_matches=" + (device == null ? "unknown" : "true"));
            Class<?> managerClass = Class.forName("android.hardware.bydauto.BYDAutoDeviceManager");
            Object manager = null;
            try { manager = manager(managerClass); }
            catch (Exception error) { out.println("manager.error=" + errorKind(error)); }
            inspect(settings, managerClass, manager, out);
        } catch (Exception error) {
            out.println("probe.error=" + errorKind(error));
        } finally {
            deadline.interrupt();
            // Completion means the diagnostic finished, not that lamp control is compatible.
            out.println("probe.complete=true");
            out.flush();
            // ActivityThread/BYD initialization can leave non-daemon threads alive under app_process.
            System.exit(0);
        }
    }

    private static Object manager(Class<?> managerClass) throws Exception {
        Class<?> looper = Class.forName("android.os.Looper");
        try { looper.getMethod("prepareMainLooper").invoke(null); }
        catch (InvocationTargetException ignored) { /* app_process may already have a main Looper. */ }
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object main = thread.getMethod("systemMain").invoke(null);
        Object systemContext = thread.getMethod("getSystemContext").invoke(main);
        Class<?> context = Class.forName("android.content.Context");
        Object shell = context.getMethod("createPackageContext", String.class, int.class)
            .invoke(systemContext, "com.android.shell", 0);
        return managerClass.getMethod("getInstance", context).invoke(null, shell);
    }

    static void inspect(Class<?> settings, Class<?> managerClass, Object manager, PrintStream out) {
        Map<String, Integer> ids = new LinkedHashMap<>();
        for (String name : READS) resolveId(settings, name, ids, out);
        for (String name : WRITE_IDS) resolveId(settings, name, ids, out);
        Method get = method(managerClass, "getInt", int.class, int.class);
        boolean batch = method(managerClass, "setIntArray", int.class, int[].class, int[].class) != null;
        boolean single = method(managerClass, "setInt", int.class, int.class, int.class) != null;
        out.println("method.getInt=" + (get != null));
        // Setter signatures are inspected only. Their presence does not prove write authorization.
        out.println("method.setIntArray=" + batch);
        out.println("method.setInt=" + single);
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String name : READS) {
            Integer id = ids.get(name);
            if (id == null || get == null || manager == null) {
                out.println("read." + name + ".error=unavailable");
                continue;
            }
            try {
                Object result = get.invoke(manager, PR345_DEVICE, id);
                if (!(result instanceof Integer)) throw new IllegalStateException("non-int-result");
                int value = (Integer) result;
                values.put(name, value);
                out.println("read." + name + "=" + raw(value));
            } catch (Exception error) {
                out.println("read." + name + ".error=" + errorKind(error));
            }
        }
        boolean snapshot = within(values, READS[0], 1, 1)
            && within(values, "SET_IAL_FRONT_COLOR", 1, 31)
            && within(values, "SET_IAL_BACK_COLOR", 1, 31)
            && within(values, "SET_IAL_FRONT_BRIGHTNESS", 1, 6)
            && within(values, "SET_IAL_BACK_BRIGHTNESS", 1, 6)
            && within(values, "SET_INTERIOR_ATMOSPHERE_LAMP_AREA", 1, 3);
        boolean writeIds = true;
        for (String name : WRITE_IDS) writeIds &= ids.containsKey(name);
        out.println("pr345.snapshot_shape_matches=" + snapshot);
        out.println("pr345.write_api_present=" + (writeIds && batch && single));
        out.println("capability_encoding=raw-uninterpreted");
        out.println("write_authorization=untested");
        out.println("vehicle_compatibility=unverified");
    }

    private static void resolveId(Class<?> settings, String name, Map<String, Integer> ids, PrintStream out) {
        try {
            Field field = settings.getField(name);
            if (field.getType() != int.class || !Modifier.isStatic(field.getModifiers())) {
                throw new IllegalStateException("unexpected-id-type");
            }
            int value = field.getInt(null);
            ids.put(name, value);
            out.println("id." + name + "=" + raw(value));
        } catch (Exception error) { out.println("id." + name + ".error=" + errorKind(error)); }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try {
            Method result = type.getMethod(name, parameters);
            return result.getReturnType() == int.class ? result : null;
        } catch (NoSuchMethodException missing) { return null; }
    }

    private static boolean within(Map<String, Integer> values, String key, int minimum, int maximum) {
        Integer value = values.get(key);
        return value != null && value >= minimum && value <= maximum;
    }

    private static void collectConstants(Class<?> type, Map<String, Integer> result, int depth) {
        if (depth > 3) return;
        for (Field field : type.getDeclaredFields()) {
            String name = field.getName();
            if (field.getType() != int.class || !Modifier.isPublic(field.getModifiers())
                || !Modifier.isStatic(field.getModifiers())) continue;
            if (!name.contains("INTERIOR_ATMOSPHERE_LAMP") && !name.contains("_IAL_")
                && !name.startsWith("IAL_") && !name.equals("BYDAUTO_DEVICE_SETTING")) continue;
            try { result.put(type.getName() + "." + name, field.getInt(null)); }
            catch (IllegalAccessException ignored) { /* Absence stays unknown, never guessed. */ }
        }
        for (Class<?> nested : type.getDeclaredClasses()) collectConstants(nested, result, depth + 1);
    }

    private static String raw(int value) {
        return value + " (0x" + String.format(Locale.ROOT, "%08x", value) + ")";
    }

    private static String errorKind(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        // Avoid copying SDK exception messages, which may contain unrelated private context.
        return error.getClass().getSimpleName();
    }
}
