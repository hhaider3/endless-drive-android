package com.endlessdrive.app;

import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * OnePlus phones keep their top refresh rate (165 Hz on the OnePlus 15) for
 * the apps they list as games, and hold every other app at 120 Hz whatever the
 * window asks for. Their own screen-mode service takes a per-app request for
 * a game rate from any app, with no permission: this asks it for the panel's
 * top rate for this app while the game is in front, and withdraws the request
 * when it leaves.
 *
 * None of it is public API. The service, its interface and the rate ids are
 * OnePlus's own (as found by the "165 Armer" project,
 * github.com/tryptz/Force165hz-Oneplus-15-NoRoot), and a firmware update may
 * change or close them. So every step may fail, and a failure only logs: on
 * other phones, or if OnePlus closes it, the app runs at what the window's
 * display mode request gets (120 Hz on the OnePlus 15).
 */
final class OnePlusRefresh {
    private static final String TAG = "EndlessDrive";
    private static final String SERVICE = "oplusscreenmode";
    private static final String INTERFACE = "com.oplus.screenmode.IOplusScreenMode";
    private static final String METHOD = "requestGameRefreshRate"; // (String package, int rateId)
    /** OnePlus's rate ids (not Hz): 7 is 165 Hz; a panel without it runs its top rate. 0 withdraws. */
    static final int RATE_165 = 7, RATE_NONE = 0;
    /**
     * The method's transaction number on the OnePlus 15's firmware, used only
     * there and only when the firmware's own interface class can't be read:
     * other builds number their methods differently.
     */
    private static final int ONEPLUS_15_CODE = 12;
    private static final String[] ONEPLUS_15_MODELS = { "CPH2747", "CPH2749", "PLK110" };

    private OnePlusRefresh() {}

    /** Asks for [rateId] for [packageName], off the main thread. */
    static void request(String packageName, int rateId) {
        new Thread(() -> {
            try {
                IBinder service = service();
                if (service == null) return; // not a OnePlus (or OPPO) phone
                Log.i(TAG, METHOD + "(" + rateId + ") " + (viaInterface(service, packageName, rateId) || viaCode(service, packageName, rateId) ? "sent" : "not available"));
            } catch (Throwable error) {
                Log.w(TAG, METHOD + " failed", error);
            }
        }, "refresh-rate").start();
    }

    private static IBinder service() {
        try {
            return (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, SERVICE);
        } catch (Throwable error) {
            return null;
        }
    }

    /** Through the firmware's own copy of the interface: its numbering and argument list, not ours. */
    private static boolean viaInterface(IBinder service, String packageName, int rateId) {
        try {
            Object proxy = Class.forName(INTERFACE + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, service);
            for (Method method : proxy.getClass().getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals(METHOD) && parameters.length == 2 && parameters[0] == String.class && parameters[1] == int.class) {
                    method.invoke(proxy, packageName, rateId);
                    return true;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, INTERFACE + " not usable here: " + error);
        }
        return false;
    }

    /** The raw call, on the one firmware whose numbering is known. */
    private static boolean viaCode(IBinder service, String packageName, int rateId) throws Exception {
        boolean known = false;
        for (String model : ONEPLUS_15_MODELS) known |= Build.MODEL.startsWith(model);
        if (!known) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INTERFACE);
            data.writeString(packageName);
            data.writeInt(rateId);
            service.transact(ONEPLUS_15_CODE, data, reply, 0);
            reply.readException();
            return true;
        } finally {
            data.recycle(); reply.recycle();
        }
    }
}
