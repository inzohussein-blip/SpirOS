// The SpirOS Engine's interface. Only apps signed with the SpirOS key may bind
// (permission com.spiros.app.permission.ENGINE, protectionLevel="signature").
package com.spiros.app.remote;

import android.os.ParcelFileDescriptor;
import com.spiros.app.remote.IEngineCallback;

interface IEngine {
    int version();

    /** JSON snapshot: linuxInstalled, desktopRunning, aiState, aiModel, frozen, memoryKb, arch. */
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
}
