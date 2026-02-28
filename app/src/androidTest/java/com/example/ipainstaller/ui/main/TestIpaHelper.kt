package com.example.ipainstaller.ui.main

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Creates test IPA and non-IPA files for instrumented tests.
 */
object TestIpaHelper {

    private const val INFO_PLIST = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleIdentifier</key>
    <string>com.test.sampleapp</string>
    <key>CFBundleShortVersionString</key>
    <string>2.0.1</string>
    <key>CFBundleName</key>
    <string>SampleApp</string>
    <key>CFBundleDisplayName</key>
    <string>Sample App</string>
</dict>
</plist>"""

    /** Creates a valid .ipa file (ZIP with Payload/App.app/Info.plist). */
    fun createTestIpa(dir: File, name: String = "TestApp.ipa"): File {
        val file = File(dir, name)
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("Payload/SampleApp.app/Info.plist"))
            zip.write(INFO_PLIST.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            // Add a dummy binary to make it look more realistic
            zip.putNextEntry(ZipEntry("Payload/SampleApp.app/SampleApp"))
            zip.write(ByteArray(1024))
            zip.closeEntry()
        }
        return file
    }

    /** Creates a non-IPA text file. */
    fun createTextFile(dir: File, name: String = "readme.txt"): File {
        val file = File(dir, name)
        file.writeText("This is not an IPA file")
        return file
    }
}
