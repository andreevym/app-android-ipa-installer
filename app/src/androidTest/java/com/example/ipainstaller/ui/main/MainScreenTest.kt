package com.example.ipainstaller.ui.main

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipainstaller.R
import com.example.ipainstaller.data.InstallRecord
import com.example.ipainstaller.model.ConnectionState
import com.example.ipainstaller.model.DeviceInfo
import com.example.ipainstaller.model.InstallState
import com.example.ipainstaller.model.IpaInfo
import com.example.ipainstaller.ui.theme.IpaInstallerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val sampleDeviceInfo = DeviceInfo(
        udid = "00001111-AAAA2222BBBB3333",
        deviceName = "Test iPhone",
        productType = "iPhone15,2",
        productVersion = "17.4.1",
        buildVersion = "21E237",
    )

    private val sampleIpaInfo = IpaInfo(
        displayName = "MyApp.ipa",
        sizeBytes = 52_428_800,
        bundleId = "com.example.myapp",
        bundleVersion = "1.2.3",
    )

    private val sampleHistory = listOf(
        InstallRecord(1, "App1.ipa", "com.app1", "iPhone", 1709136000000, true, null),
        InstallRecord(2, "App2.ipa", "com.app2", "iPhone", 1709049600000, false, "Signing error"),
    )

    private fun setScreen(
        connectionState: ConnectionState = ConnectionState.Disconnected,
        installState: InstallState = InstallState.Idle,
        ipaInfo: IpaInfo? = null,
        installHistory: List<InstallRecord> = emptyList(),
        isPaired: Boolean = false,
        canSelectIpa: Boolean = true,
        canInstall: Boolean = false,
        onSelectIpa: () -> Unit = {},
        onInstall: () -> Unit = {},
        onDismiss: () -> Unit = {},
        onReconnect: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            IpaInstallerTheme {
                MainScreenContent(
                    connectionState = connectionState,
                    installState = installState,
                    ipaInfo = ipaInfo,
                    installHistory = installHistory,
                    snackbarHostState = SnackbarHostState(),
                    isPaired = isPaired,
                    canSelectIpa = canSelectIpa,
                    canInstall = canInstall,
                    onSelectIpa = onSelectIpa,
                    onInstall = onInstall,
                    onDismiss = onDismiss,
                    onReconnect = onReconnect,
                )
            }
        }
    }

    // Localized string helpers
    private fun str(id: Int) = ctx.getString(id)
    private fun str(id: Int, vararg args: Any) = ctx.getString(id, *args)

    // -- Select IPA button tests --

    @Test
    fun selectIpaButton_isEnabled_whenDisconnectedAndIdle() {
        setScreen()
        composeTestRule.onNodeWithText(str(R.string.select_ipa))
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun selectIpaButton_callsCallback_whenClicked() {
        var clicked = false
        setScreen(onSelectIpa = { clicked = true })
        composeTestRule.onNodeWithText(str(R.string.select_ipa))
            .performScrollTo()
            .performClick()
        assertTrue("onSelectIpa callback should be called", clicked)
    }

    @Test
    fun selectIpaButton_isDisabled_duringUpload() {
        setScreen(
            connectionState = ConnectionState.Paired(sampleDeviceInfo),
            installState = InstallState.Uploading(0.5f),
            ipaInfo = sampleIpaInfo,
            isPaired = true,
            canSelectIpa = false,
        )
        composeTestRule.onNodeWithText("MyApp.ipa")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    // -- IPA info display tests --

    @Test
    fun ipaInfoCard_showsDetails_afterSelection() {
        setScreen(ipaInfo = sampleIpaInfo)
        composeTestRule.onNodeWithText("MyApp.ipa")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.ipa_bundle_id, "com.example.myapp"))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.ipa_version, "1.2.3"))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun hintText_shown_whenNoIpaSelected() {
        setScreen()
        composeTestRule.onNodeWithText(str(R.string.ipa_file_hint))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun hintText_hidden_whenIpaSelected() {
        setScreen(ipaInfo = sampleIpaInfo)
        composeTestRule.onNodeWithText(str(R.string.ipa_file_hint))
            .assertDoesNotExist()
    }

    // -- Install button tests --

    @Test
    fun installButton_isEnabled_whenPairedAndIpaSelected() {
        setScreen(
            connectionState = ConnectionState.Paired(sampleDeviceInfo),
            ipaInfo = sampleIpaInfo,
            isPaired = true,
            canInstall = true,
        )
        composeTestRule.onNodeWithText(str(R.string.install))
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun installButton_isDisabled_whenNotPaired() {
        setScreen(ipaInfo = sampleIpaInfo)
        composeTestRule.onNodeWithText(str(R.string.install))
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    // -- Device status tests --

    @Test
    fun deviceStatus_showsDisconnectedMessage() {
        setScreen()
        composeTestRule.onNodeWithText(str(R.string.no_device))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun deviceStatus_showsDeviceName_whenPaired() {
        setScreen(
            connectionState = ConnectionState.Paired(sampleDeviceInfo),
            isPaired = true,
        )
        composeTestRule.onNodeWithText(str(R.string.device_connected, "Test iPhone"))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.ios_version, "17.4.1"))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun errorState_showsReconnectButton() {
        var reconnected = false
        setScreen(
            connectionState = ConnectionState.Error("USB permission denied"),
            onReconnect = { reconnected = true },
        )
        composeTestRule.onNodeWithText("USB permission denied")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.reconnect))
            .performScrollTo()
            .performClick()
        assertTrue("onReconnect callback should be called", reconnected)
    }

    // -- Install history tests --

    @Test
    fun installHistory_showsRecords() {
        setScreen(installHistory = sampleHistory)
        composeTestRule.onNodeWithText(str(R.string.install_history))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("App1.ipa")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("App2.ipa")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun installHistory_hidden_whenEmpty() {
        setScreen()
        composeTestRule.onNodeWithText(str(R.string.install_history))
            .assertDoesNotExist()
    }

    // -- Install progress tests --

    @Test
    fun installProgress_showsUploadingState() {
        setScreen(
            connectionState = ConnectionState.Paired(sampleDeviceInfo),
            installState = InstallState.Uploading(0.45f),
            ipaInfo = sampleIpaInfo,
            isPaired = true,
            canSelectIpa = false,
        )
        composeTestRule.onNodeWithText(str(R.string.uploading_ipa))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun installProgress_showsSuccessWithDismiss() {
        var dismissed = false
        setScreen(
            connectionState = ConnectionState.Paired(sampleDeviceInfo),
            installState = InstallState.Success,
            ipaInfo = sampleIpaInfo,
            isPaired = true,
            canSelectIpa = false,
            onDismiss = { dismissed = true },
        )
        composeTestRule.onNodeWithText(str(R.string.install_success))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.ok))
            .performScrollTo()
            .performClick()
        assertTrue("onDismiss callback should be called", dismissed)
    }

    @Test
    fun installProgress_showsFailedWithDismiss() {
        setScreen(
            connectionState = ConnectionState.Paired(sampleDeviceInfo),
            installState = InstallState.Failed("Signing verification failed"),
            ipaInfo = sampleIpaInfo,
            isPaired = true,
            canSelectIpa = false,
        )
        composeTestRule.onNodeWithText("Signing verification failed")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.dismiss))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
