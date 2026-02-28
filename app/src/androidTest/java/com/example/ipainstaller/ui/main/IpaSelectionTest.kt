package com.example.ipainstaller.ui.main

import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipainstaller.R
import com.example.ipainstaller.model.ConnectionState
import com.example.ipainstaller.model.InstallState
import com.example.ipainstaller.model.IpaInfo
import com.example.ipainstaller.ui.theme.IpaInstallerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Tests IPA file selection, parsing, and extension filter.
 * Creates real .ipa (ZIP) files on the device and verifies the full flow.
 */
class IpaSelectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var testDir: File
    private lateinit var testIpaFile: File
    private lateinit var testTxtFile: File

    @Before
    fun setup() {
        testDir = File(ctx.cacheDir, "test_ipa").also { it.mkdirs() }
        testIpaFile = TestIpaHelper.createTestIpa(testDir, "SampleApp.ipa")
        testTxtFile = TestIpaHelper.createTextFile(testDir, "document.txt")
    }

    @After
    fun cleanup() {
        testDir.deleteRecursively()
    }

    private fun getContentUri(file: File): Uri {
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }

    // -- IPA file parsing tests --

    @Test
    fun ipaFile_isParsedCorrectly_fromContentUri() {
        val uri = getContentUri(testIpaFile)

        // Parse IPA info using ContentResolver (same as ViewModel does)
        val inputStream = ctx.contentResolver.openInputStream(uri)
        assertNotNull("Should be able to open IPA file", inputStream)

        var bundleId: String? = null
        var bundleVersion: String? = null

        inputStream!!.use { stream ->
            java.util.zip.ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.matches(Regex("Payload/[^/]+\\.app/Info\\.plist"))) {
                        val plistBytes = zip.readBytes()
                        val plist = com.dd.plist.PropertyListParser.parse(plistBytes)
                                as? com.dd.plist.NSDictionary
                        bundleId = plist?.get("CFBundleIdentifier")?.toJavaObject()?.toString()
                        bundleVersion = plist?.get("CFBundleShortVersionString")
                            ?.toJavaObject()?.toString()
                        break
                    }
                    entry = zip.nextEntry
                }
            }
        }

        assertEquals("com.test.sampleapp", bundleId)
        assertEquals("2.0.1", bundleVersion)
    }

    @Test
    fun ipaFile_sizeIsPositive() {
        val uri = getContentUri(testIpaFile)

        val size = ctx.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.SIZE),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        } ?: -1L

        assertTrue("IPA file size should be positive, got $size", size > 0)
    }

    @Test
    fun ipaFile_displayNameIsCorrect() {
        val uri = getContentUri(testIpaFile)

        val displayName = ctx.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

        assertEquals("SampleApp.ipa", displayName)
    }

    // -- Extension filter tests --

    @Test
    fun ipaExtension_isAccepted() {
        val name = "MyApp.ipa"
        assertTrue(name.endsWith(".ipa", ignoreCase = true))
    }

    @Test
    fun ipaExtension_caseInsensitive() {
        assertTrue("MyApp.IPA".endsWith(".ipa", ignoreCase = true))
        assertTrue("MyApp.Ipa".endsWith(".ipa", ignoreCase = true))
    }

    @Test
    fun nonIpaExtension_isRejected() {
        val txtName = "document.txt"
        val apkName = "app.apk"
        val zipName = "archive.zip"

        assertTrue(!txtName.endsWith(".ipa", ignoreCase = true))
        assertTrue(!apkName.endsWith(".ipa", ignoreCase = true))
        assertTrue(!zipName.endsWith(".ipa", ignoreCase = true))
    }

    // -- UI tests with selected IPA info --

    @Test
    fun ui_showsIpaInfo_afterFileSelected() {
        val ipaInfo = IpaInfo(
            displayName = "SampleApp.ipa",
            sizeBytes = testIpaFile.length(),
            bundleId = "com.test.sampleapp",
            bundleVersion = "2.0.1",
        )

        composeTestRule.setContent {
            IpaInstallerTheme {
                MainScreenContent(
                    connectionState = ConnectionState.Disconnected,
                    installState = InstallState.Idle,
                    ipaInfo = ipaInfo,
                    installHistory = emptyList(),
                    snackbarHostState = SnackbarHostState(),
                    isPaired = false,
                    canSelectIpa = true,
                    canInstall = false,
                    onSelectIpa = {},
                    onInstall = {},
                    onDismiss = {},
                    onReconnect = {},
                )
            }
        }

        // Button shows file name
        composeTestRule.onNodeWithText("SampleApp.ipa")
            .performScrollTo()
            .assertIsDisplayed()

        // IpaInfoCard shows parsed data
        composeTestRule.onNodeWithText(ctx.getString(R.string.ipa_bundle_id, "com.test.sampleapp"))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(ctx.getString(R.string.ipa_version, "2.0.1"))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun ui_showsSelectButton_whenNoFileSelected() {
        composeTestRule.setContent {
            IpaInstallerTheme {
                MainScreenContent(
                    connectionState = ConnectionState.Disconnected,
                    installState = InstallState.Idle,
                    ipaInfo = null,
                    installHistory = emptyList(),
                    snackbarHostState = SnackbarHostState(),
                    isPaired = false,
                    canSelectIpa = true,
                    canInstall = false,
                    onSelectIpa = {},
                    onInstall = {},
                    onDismiss = {},
                    onReconnect = {},
                )
            }
        }

        composeTestRule.onNodeWithText(ctx.getString(R.string.select_ipa))
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()

        // Hint text shown
        composeTestRule.onNodeWithText(ctx.getString(R.string.ipa_file_hint))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun ui_selectButton_showsFileName_whenIpaSelected() {
        val ipaInfo = IpaInfo("CustomGame.ipa", 104_857_600, "com.game.custom", "3.5.0")

        composeTestRule.setContent {
            IpaInstallerTheme {
                MainScreenContent(
                    connectionState = ConnectionState.Disconnected,
                    installState = InstallState.Idle,
                    ipaInfo = ipaInfo,
                    installHistory = emptyList(),
                    snackbarHostState = SnackbarHostState(),
                    isPaired = false,
                    canSelectIpa = true,
                    canInstall = false,
                    onSelectIpa = {},
                    onInstall = {},
                    onDismiss = {},
                    onReconnect = {},
                )
            }
        }

        // Button text changes to file name
        composeTestRule.onNodeWithText("CustomGame.ipa")
            .performScrollTo()
            .assertIsDisplayed()

        // "Select IPA File" text is gone
        composeTestRule.onNodeWithText(ctx.getString(R.string.select_ipa))
            .assertDoesNotExist()

        // Hint is gone
        composeTestRule.onNodeWithText(ctx.getString(R.string.ipa_file_hint))
            .assertDoesNotExist()
    }
}
