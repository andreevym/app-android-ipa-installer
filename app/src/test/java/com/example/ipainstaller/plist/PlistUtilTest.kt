package com.example.ipainstaller.plist

import com.dd.plist.NSDictionary
import com.dd.plist.NSString
import com.example.ipainstaller.plist.PlistUtil.getString
import org.junit.Assert.*
import org.junit.Test

/** P2: PlistUtil parsing and serialization. */
class PlistUtilTest {

    @Test
    fun `parse XML plist`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Name</key>
    <string>TestApp</string>
    <key>Version</key>
    <string>1.0</string>
</dict>
</plist>"""

        val dict = PlistUtil.parse(xml.toByteArray())
        assertEquals("TestApp", (dict["Name"] as NSString).content)
        assertEquals("1.0", (dict["Version"] as NSString).content)
    }

    @Test
    fun `toXml produces valid XML plist`() {
        val dict = NSDictionary()
        dict["Key1"] = NSString("Value1")

        val bytes = PlistUtil.toXml(dict)
        val text = String(bytes)
        assertTrue(text.contains("<plist"))
        assertTrue(text.contains("Key1"))
        assertTrue(text.contains("Value1"))
    }

    @Test
    fun `toXml then parse round-trips`() {
        val original = NSDictionary()
        original["Foo"] = NSString("Bar")
        original["Num"] = NSString("42")

        val bytes = PlistUtil.toXml(original)
        val parsed = PlistUtil.parse(bytes)

        assertEquals("Bar", (parsed["Foo"] as NSString).content)
        assertEquals("42", (parsed["Num"] as NSString).content)
    }

    @Test
    fun `getString returns string value`() {
        val dict = NSDictionary()
        dict["Type"] = NSString("com.apple.mobile.lockdown")
        assertEquals("com.apple.mobile.lockdown", dict.getString("Type"))
    }

    @Test
    fun `getString returns null for missing key`() {
        val dict = NSDictionary()
        assertNull(dict.getString("NonExistent"))
    }

    @Test
    fun `getString returns null for non-string value`() {
        val dict = NSDictionary()
        dict["Number"] = com.dd.plist.NSNumber(42)
        assertNull(dict.getString("Number"))
    }

    @Test
    fun `dictOf creates dictionary with string pairs`() {
        val dict = PlistUtil.dictOf("A" to "1", "B" to "2", "C" to "3")
        assertEquals("1", (dict["A"] as NSString).content)
        assertEquals("2", (dict["B"] as NSString).content)
        assertEquals("3", (dict["C"] as NSString).content)
    }

    @Test
    fun `dictOf with no pairs creates empty dictionary`() {
        val dict = PlistUtil.dictOf()
        assertEquals(0, dict.count())
    }

    @Test
    fun `dictOf result serializes to valid plist`() {
        val dict = PlistUtil.dictOf("Request" to "QueryType", "Label" to "test")
        val bytes = PlistUtil.toXml(dict)
        val parsed = PlistUtil.parse(bytes)
        assertEquals("QueryType", (parsed["Request"] as NSString).content)
        assertEquals("test", (parsed["Label"] as NSString).content)
    }
}
