package com.spiros.app

import android.app.Application
import com.spiros.app.vault.Vault
import java.io.File

class SpirApp : Application() {
    // noBackupFilesDir: never part of any backup, even if backup rules change later.
    val vault: Vault by lazy { Vault(File(noBackupFilesDir, "vault")) }
}
