package app.sentry;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;

/**
 * Best-effort control of the battery charging path for overcharge protection.
 *
 * <p><b>Important:</b> stock Android exposes no public API to stop/start charging. Real control is
 * only possible on rooted or vendor-customised devices that expose a <em>writable</em> sysfs node
 * (normally owned by root, so an unprivileged app cannot write it). This helper therefore only ever
 * attempts a write when the node reports as writable; on every other device it is a safe no-op and
 * the caller falls back to simply warning the user to unplug.
 *
 * <p>No root shell ({@code su}) is spawned — we only touch nodes the app can already write, which
 * keeps the behaviour safe and predictable.
 */
final class ChargeController {

    private static final String TAG = "ChargeController";

    /**
     * Candidate control nodes across common vendor kernels. Each entry pairs the node path with the
     * values that <em>enable</em> and <em>disable</em> charging respectively. Some nodes are phrased
     * as "charging enabled" (1=on) and some as "suspend input" (1=off), hence the per-node values.
     */
    private static final Node[] NODES = new Node[]{
            new Node("/sys/class/power_supply/battery/charging_enabled", "1", "0"),
            new Node("/sys/class/power_supply/battery/battery_charging_enabled", "1", "0"),
            new Node("/sys/class/power_supply/battery/input_suspend", "0", "1"),
            new Node("/sys/class/power_supply/battery/charge_enabled", "1", "0"),
    };

    private ChargeController() {
    }

    /** True if any known charging-control node is present and writable on this device. */
    static boolean isSupported() {
        return findWritableNode() != null;
    }

    /**
     * Attempts to enable or disable charging. Returns true only if a write actually succeeded.
     *
     * @param enable true to allow charging, false to pause it
     */
    static boolean setCharging(boolean enable) {
        Node node = findWritableNode();
        if (node == null) {
            return false;
        }
        String value = enable ? node.enableValue : node.disableValue;
        try (FileWriter writer = new FileWriter(node.path)) {
            writer.write(value);
            writer.flush();
            Log.i(TAG, "setCharging(" + enable + ") wrote '" + value + "' to " + node.path);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed writing charge node " + node.path, e);
            return false;
        }
    }

    private static Node findWritableNode() {
        for (Node node : NODES) {
            try {
                File f = new File(node.path);
                if (f.exists() && f.canWrite()) {
                    return node;
                }
            } catch (Exception ignored) {
                // Inaccessible; try the next candidate.
            }
        }
        return null;
    }

    private static final class Node {
        final String path;
        final String enableValue;
        final String disableValue;

        Node(String path, String enableValue, String disableValue) {
            this.path = path;
            this.enableValue = enableValue;
            this.disableValue = disableValue;
        }
    }
}
