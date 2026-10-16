package com.amitjha.kosh

import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fragment 1.2.x (pulled in by biometric) made FragmentActivity.startActivityForResult reject request codes above
 * 16 bits, which the Activity Result API always uses, so Back up, Restore, Export, PDF reports and receipts all
 * crashed on opening the file picker. MainActivity is a FragmentActivity, so the old check must never come back.
 */
class FilePickerCompatTest {

    @Test
    fun fragmentActivityNoLongerRejectsActivityResultRequestCodes() {
        val methods = FragmentActivity::class.java.declaredMethods.map { it.name }
        assertTrue("fragment 1.2.x is on the classpath again", "checkForValidRequestCode" !in methods)
    }
}
