package com.gccera.myai.engine.code

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Receives stdout/stderr from Python (called from the Python thread). */
fun interface PyOutput { fun onOutput(text: String, isErr: Boolean) }

/** Embedded CPython 3.13 (Chaquopy). Standard library only; runs fully offline. */
@Singleton
class PythonRunner @Inject constructor(@ApplicationContext private val context: Context) {
    private val module by lazy {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        Python.getInstance().getModule("runner")
    }

    /** Blocking until the script finishes; returns the exit code. */
    suspend fun run(code: String, fileName: String, cwd: String?, stdin: String, out: PyOutput): Int = withContext(Dispatchers.IO) {
        module.callAttr("run", code, fileName, cwd, stdin, out).toInt()
    }

    fun stop() { runCatching { module.callAttr("stop") } }
}
