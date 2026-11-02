// The SpirOS Engine's interface. Only apps signed with the SpirOS key may bind
// (permission com.spiros.app.permission.ENGINE, protectionLevel="signature").
package com.spiros.app.remote;

import android.os.ParcelFileDescriptor;
import com.spiros.app.remote.IEngineCallback;

interface IEngine {
    int version();

    /** JSON snapshot: linuxInstalled, distro, desktopRunning, aiState, aiModel, frozen, memoryKb, arch, task. */
    String status();

    // ---- Linux system ----
    void installLinux(IEngineCallback callback);
    void removeLinux();
    void startDesktop(String geometry, int dpi, boolean light, String singleApp);
    void stopDesktop();
    /** A connected socket to the desktop's VNC server, or null if it is not up yet. */
    ParcelFileDescriptor openDisplay();
    List<String> desktopLog();

    // ---- Linux accounts ----
    /** JSON array of {name, uid, admin}. */
    String users();
    String desktopUser();
    void setDesktopUser(String name);
    /** action: add | password | admin | delete. Returns null on success, else the error. */
    String account(String action, String name, String password, boolean admin);

    // ---- Private AI ----
    /** JSON array of downloaded model file names. */
    String downloadedModels();
    void downloadModel(String url, String fileName, IEngineCallback callback);
    void deleteModel(String fileName);
    void startAi(String fileName, int threads, int contextSize);
    void stopAi();
    String aiKey();
    int aiPort();
    List<String> aiLog();

    // ---- Power ----
    void freeze();
    void resume();
    void stopAll();

    // ---- Background installs (added in engine v2; keep new methods at the end) ----
    /**
     * Installs a Linux system in the background: desktop = xfce | mate | cinnamon, or
     * custom with an https url or a file (sha256 optional). Progress is in status().task.
     */
    void installDistro(String desktop, String url, String sha256, in ParcelFileDescriptor file);
    void pauseTask();
    void resumeTask();
    void cancelTask();
    /** Forget the last finished task's result. */
    void clearTask();

    // ---- Linux apps (engine v3) ----
    /** Opens a program inside the running desktop (as the desktop user). */
    void runInDesktop(String command);
}
