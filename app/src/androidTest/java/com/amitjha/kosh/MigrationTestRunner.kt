package com.amitjha.kosh

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Database tests must not start KoshApp's background repairs or workers. */
class MigrationTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
