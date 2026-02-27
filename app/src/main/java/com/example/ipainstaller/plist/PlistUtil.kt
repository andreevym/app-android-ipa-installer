package com.example.ipainstaller.plist

import com.dd.plist.NSDictionary
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser

/**
 * Utility helpers for working with Apple plist data.
 */
object PlistUtil {

    /** Parses plist bytes (XML or binary) into an NSDictionary. */
    fun parse(data: ByteArray): NSDictionary =
        PropertyListParser.parse(data) as NSDictionary

    /** Serializes an NSDictionary to XML plist bytes. */
    fun toXml(dict: NSDictionary): ByteArray =
        dict.toXMLPropertyList().toByteArray()

    /** Convenience to get a string value from a plist dictionary. */
    fun NSDictionary.getString(key: String): String? =
        (this[key] as? NSString)?.content

    /** Convenience to create a simple plist dictionary from key-value pairs. */
    fun dictOf(vararg pairs: Pair<String, String>): NSDictionary {
        val dict = NSDictionary()
        for ((key, value) in pairs) {
            dict[key] = NSString(value)
        }
        return dict
    }
}
