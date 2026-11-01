// Progress of a long engine task (installing Linux, downloading a model).
package com.spiros.app.remote;

oneway interface IEngineCallback {
    void onProgress(String stage, float fraction);
    /** error == null on success. */
    void onDone(String error);
}
