package com.example.uniremote.ui

import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.uniremote.R
import com.example.uniremote.network.PairingState
import com.example.uniremote.ui.navigation.GlobalPairingDialog
import org.junit.Rule
import org.junit.Test

class PairingDialogInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun waitingForPin_showsPairingDialog() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expectedTitle = context.getString(R.string.pairing_title)

        composeRule.setContent {
            GlobalPairingDialog(
                pairingState = PairingState.WAITING_FOR_PIN,
                onSubmitPin = {},
                onCancel = {}
            )
        }

        composeRule.onNodeWithText(expectedTitle).assertIsDisplayed()
    }

    @Test
    fun idle_doesNotShowPairingDialog() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expectedTitle = context.getString(R.string.pairing_title)

        composeRule.setContent {
            GlobalPairingDialog(
                pairingState = PairingState.IDLE,
                onSubmitPin = {},
                onCancel = {}
            )
        }

        composeRule.onNodeWithText(expectedTitle).assertDoesNotExist()
    }
}
