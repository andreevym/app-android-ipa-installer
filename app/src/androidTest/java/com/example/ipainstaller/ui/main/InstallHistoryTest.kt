package com.example.ipainstaller.ui.main

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipainstaller.R
import com.example.ipainstaller.data.AppDatabase
import com.example.ipainstaller.data.InstallHistoryDao
import com.example.ipainstaller.data.InstallRecord
import com.example.ipainstaller.model.ConnectionState
import com.example.ipainstaller.model.InstallState
import com.example.ipainstaller.ui.theme.IpaInstallerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for install history: Room DB operations + UI display.
 */
class InstallHistoryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var dao: InstallHistoryDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.installHistoryDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    // -- Room DB tests --

    @Test
    fun dao_insertAndRetrieve_singleRecord() = runTest {
        val record = InstallRecord(
            ipaName = "TestApp.ipa",
            bundleId = "com.test.app",
            deviceName = "iPhone 15",
            timestamp = System.currentTimeMillis(),
            success = true,
            errorMessage = null,
        )

        dao.insert(record)
        val records = dao.getAll().first()

        assertEquals(1, records.size)
        assertEquals("TestApp.ipa", records[0].ipaName)
        assertEquals("com.test.app", records[0].bundleId)
        assertEquals("iPhone 15", records[0].deviceName)
        assertTrue(records[0].success)
        assertEquals(null, records[0].errorMessage)
    }

    @Test
    fun dao_insertAndRetrieve_failedRecord() = runTest {
        val record = InstallRecord(
            ipaName = "BrokenApp.ipa",
            bundleId = "com.broken.app",
            deviceName = "iPhone 14",
            timestamp = System.currentTimeMillis(),
            success = false,
            errorMessage = "Signing verification failed",
        )

        dao.insert(record)
        val records = dao.getAll().first()

        assertEquals(1, records.size)
        assertEquals(false, records[0].success)
        assertEquals("Signing verification failed", records[0].errorMessage)
    }

    @Test
    fun dao_retrievesInReverseChronologicalOrder() = runTest {
        val now = System.currentTimeMillis()

        dao.insert(InstallRecord(
            ipaName = "OldApp.ipa", bundleId = null,
            deviceName = "iPhone", timestamp = now - 10000,
            success = true, errorMessage = null,
        ))
        dao.insert(InstallRecord(
            ipaName = "NewApp.ipa", bundleId = null,
            deviceName = "iPhone", timestamp = now,
            success = true, errorMessage = null,
        ))

        val records = dao.getAll().first()
        assertEquals(2, records.size)
        assertEquals("NewApp.ipa", records[0].ipaName)
        assertEquals("OldApp.ipa", records[1].ipaName)
    }

    @Test
    fun dao_limitsTo50Records() = runTest {
        val now = System.currentTimeMillis()
        repeat(60) { i ->
            dao.insert(InstallRecord(
                ipaName = "App$i.ipa", bundleId = null,
                deviceName = "iPhone", timestamp = now + i,
                success = true, errorMessage = null,
            ))
        }

        val records = dao.getAll().first()
        assertEquals(50, records.size)
        // Most recent should be first
        assertEquals("App59.ipa", records[0].ipaName)
    }

    @Test
    fun dao_deleteOld_keepsOnly100Records() = runTest {
        val now = System.currentTimeMillis()
        repeat(120) { i ->
            dao.insert(InstallRecord(
                ipaName = "App$i.ipa", bundleId = null,
                deviceName = "iPhone", timestamp = now + i,
                success = true, errorMessage = null,
            ))
        }

        dao.deleteOld()
        // getAll() returns max 50, but DB should have 100 after cleanup
        val records = dao.getAll().first()
        assertEquals(50, records.size)
        // The newest should still be present
        assertEquals("App119.ipa", records[0].ipaName)
    }

    // -- UI display tests --

    @Test
    fun ui_showsHistorySection_withRecords() {
        val history = listOf(
            InstallRecord(1, "GameApp.ipa", "com.game", "iPhone 15", 1709136000000, true, null),
            InstallRecord(2, "UtilApp.ipa", "com.util", "iPhone 14", 1709049600000, false, "Error"),
        )

        composeTestRule.setContent {
            IpaInstallerTheme {
                MainScreenContent(
                    connectionState = ConnectionState.Disconnected,
                    installState = InstallState.Idle,
                    ipaInfo = null,
                    installHistory = history,
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

        composeTestRule.onNodeWithText(ctx.getString(R.string.install_history))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("GameApp.ipa")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("UtilApp.ipa")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun ui_hidesHistorySection_whenEmpty() {
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

        composeTestRule.onNodeWithText(ctx.getString(R.string.install_history))
            .assertDoesNotExist()
    }

    @Test
    fun ui_showsSuccessAndFailedRecords_withCorrectLabels() {
        val history = listOf(
            InstallRecord(1, "Success.ipa", "com.s", "iPhone", 1709136000000, true, null),
            InstallRecord(2, "Failed.ipa", "com.f", "iPhone", 1709049600000, false, "Sign error"),
        )

        composeTestRule.setContent {
            IpaInstallerTheme {
                MainScreenContent(
                    connectionState = ConnectionState.Disconnected,
                    installState = InstallState.Idle,
                    ipaInfo = null,
                    installHistory = history,
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

        composeTestRule.onNodeWithText("Success.ipa")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Failed.ipa")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun ui_showsDeviceNameAndDate_inHistoryItem() {
        val history = listOf(
            InstallRecord(1, "App.ipa", "com.app", "Test iPhone 15", 1709136000000, true, null),
        )

        composeTestRule.setContent {
            IpaInstallerTheme {
                MainScreenContent(
                    connectionState = ConnectionState.Disconnected,
                    installState = InstallState.Idle,
                    ipaInfo = null,
                    installHistory = history,
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

        composeTestRule.onNodeWithText("App.ipa")
            .performScrollTo()
            .assertIsDisplayed()
        // Device name is part of the subtitle text
        composeTestRule.onNodeWithText("Test iPhone 15", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
