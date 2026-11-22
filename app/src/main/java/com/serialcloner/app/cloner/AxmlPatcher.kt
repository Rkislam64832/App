package com.serialcloner.app.cloner

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin Android Binary XML (AXML) patcher for cloning APKs.
 *
 * Updates:
 *  1. <manifest package="..."> -> newPackageName (e.g., com.example.app.clone1)
 *  2. <application android:label="..."> -> "AppName 1" (forces TYPE_STRING so home screen shows serial)
 *  3. Relative component class names (e.g., ".MainActivity") -> fully-qualified original class names
 *     so Android can still load classes from the original DEX after package rename.
 *  4. <provider android:authorities="..."> -> suffixed with ".cloneN" to avoid provider conflicts.
 *  5. Custom <permission> / <uses-permission> under oldPackageName -> remapped to newPackageName.
 */
object AxmlPatcher {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102

    private const val UTF8_FLAG = 0x00000100
    private const val TYPE_STRING = 0x03

    private val COMPONENT_TAGS = setOf(
        "application", "activity", "activity-alias", "service", "receiver", "provider"
    )

    fun patchManifest(
        axmlBytes: ByteArray,
        oldPackage: String,
        newPackage: String,
        newLabel: String,
        serialNumber: Int
    ): ByteArray {
        val buf = ByteBuffer.wrap(axmlBytes).order(ByteOrder.LITTLE_ENDIAN)
        val fileType = buf.short.toInt() and 0xFFFF
        val fileHeaderSize = buf.short.toInt() and 0xFFFF
        require(fileType == RES_XML_TYPE) { "Invalid AXML header: 0x${fileType.toString(16)}" }
        buf.int // original file size

        // Parse StringPool chunk immediately after AXML header
        val poolStart = fileHeaderSize
        buf.position(poolStart)
        val poolType = buf.short.toInt() and 0xFFFF
        val poolHeaderSize = buf.short.toInt() and 0xFFFF
        val poolChunkSize = buf.int
        require(poolType == RES_STRING_POOL_TYPE) { "Expected StringPool chunk at $poolStart" }

        val stringCount = buf.int
        val styleCount = buf.int
        val flags = buf.int
        val stringsStart = buf.int
        val stylesStart = buf.int
        val isUtf8 = (flags and UTF8_FLAG) != 0

        val stringOffsets = IntArray(stringCount) { buf.int }
        val styleOffsets = IntArray(styleCount) { buf.int }

        val strings = MutableList(stringCount) { index ->
            val offset = poolStart + stringsStart + stringOffsets[index]
            if (isUtf8) readUtf8String(axmlBytes, offset) else readUtf16String(axmlBytes, offset)
        }

        val rawStylesData = if (styleCount > 0 && stylesStart > 0) {
            val absStart = poolStart + stylesStart
            val absEnd = poolStart + poolChunkSize
            axmlBytes.copyOfRange(absStart, absEnd)
        } else {
            ByteArray(0)
        }

        // Copy all chunks after StringPool so we can patch element attributes in place
        val restStart = poolStart + poolChunkSize
        val restBytes = axmlBytes.copyOfRange(restStart, axmlBytes.size)
        val restBuf = ByteBuffer.wrap(restBytes).order(ByteOrder.LITTLE_ENDIAN)

        fun getOrAddString(value: String): Int {
            val existing = strings.indexOf(value)
            if (existing >= 0) return existing
            strings.add(value)
            return strings.lastIndex
        }

        val newPkgIndex = getOrAddString(newPackage)
        val newLabelIndex = getOrAddString(newLabel)

        var pos = 0
        while (pos + 8 <= restBytes.size) {
            val chunkType = restBuf.getShort(pos).toInt() and 0xFFFF
            val chunkHeaderSize = restBuf.getShort(pos + 2).toInt() and 0xFFFF
            val chunkSize = restBuf.getInt(pos + 4)
            if (chunkSize <= 0 || pos + chunkSize > restBytes.size) break

            if (chunkType == RES_XML_START_ELEMENT_TYPE) {
                val elemBase = pos + chunkHeaderSize
                val nameIdx = restBuf.getInt(elemBase + 4)
                val attrStart = restBuf.getShort(elemBase + 8).toInt() and 0xFFFF
                val attrSize = restBuf.getShort(elemBase + 10).toInt() and 0xFFFF
                val attrCount = restBuf.getShort(elemBase + 12).toInt() and 0xFFFF
                val tagName = strings.getOrNull(nameIdx) ?: ""

                val attrsBase = elemBase + attrStart
                for (i in 0 until attrCount) {
                    val attrPos = attrsBase + i * attrSize
                    val attrNameIdx = restBuf.getInt(attrPos + 4)
                    val attrName = strings.getOrNull(attrNameIdx) ?: ""
                    val rawValIdx = restBuf.getInt(attrPos + 8)
                    val valueType = restBuf.get(attrPos + 15).toInt() and 0xFF
                    val valueData = restBuf.getInt(attrPos + 16)

                    fun setStringAttr(newStrIdx: Int) {
                        restBuf.putInt(attrPos + 8, newStrIdx)
                        restBuf.put(attrPos + 15, TYPE_STRING.toByte())
                        restBuf.putInt(attrPos + 16, newStrIdx)
                    }

                    when {
                        // 1. Update manifest package name
                        tagName == "manifest" && attrName == "package" -> {
                            setStringAttr(newPkgIndex)
                        }

                        // 2. Update application label to "<AppName> <Serial>"
                        tagName == "application" && attrName == "label" -> {
                            setStringAttr(newLabelIndex)
                        }

                        // 3. Expand relative component class names so DEX classes still resolve
                        tagName in COMPONENT_TAGS && attrName == "name" && valueType == TYPE_STRING -> {
                            val current = strings.getOrNull(valueData) ?: strings.getOrNull(rawValIdx)
                            if (!current.isNullOrEmpty()) {
                                val expanded = when {
                                    current.startsWith(".") -> oldPackage + current
                                    !current.contains(".") -> "$oldPackage.$current"
                                    else -> null
                                }
                                if (expanded != null) {
                                    setStringAttr(getOrAddString(expanded))
                                }
                            }
                        }

                        // 4. Rewrite ContentProvider authorities to avoid INSTALL_FAILED_CONFLICTING_PROVIDER
                        tagName == "provider" && attrName == "authorities" && valueType == TYPE_STRING -> {
                            val current = strings.getOrNull(valueData) ?: strings.getOrNull(rawValIdx)
                            if (!current.isNullOrEmpty()) {
                                val updated = current.split(";").joinToString(";") { auth ->
                                    if (auth.startsWith(oldPackage)) {
                                        auth.replaceFirst(oldPackage, newPackage)
                                    } else {
                                        "$auth.clone$serialNumber"
                                    }
                                }
                                setStringAttr(getOrAddString(updated))
                            }
                        }

                        // 5. Remap custom app-owned permissions
                        (tagName == "permission" || tagName == "uses-permission") &&
                            attrName == "name" && valueType == TYPE_STRING -> {
                            val current = strings.getOrNull(valueData) ?: strings.getOrNull(rawValIdx)
                            if (current != null && current.startsWith("$oldPackage.")) {
                                val updated = current.replaceFirst(oldPackage, newPackage)
                                setStringAttr(getOrAddString(updated))
                            }
                        }
                    }
                }
            }

            pos += chunkSize
        }

        // Rebuild StringPool cleanly in UTF-16LE
        val rebuiltPool = buildUtf16StringPool(strings, styleOffsets, rawStylesData)

        // Assemble final AXML binary
        val totalSize = fileHeaderSize + rebuiltPool.size + restBytes.size
        val out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(RES_XML_TYPE.toShort())
        out.putShort(fileHeaderSize.toShort())
        out.putInt(totalSize)
        out.put(rebuiltPool)
        out.put(restBytes)
        return out.array()
    }

    private fun buildUtf16StringPool(
        strings: List<String>,
        styleOffsets: IntArray,
        rawStylesData: ByteArray
    ): ByteArray {
        val headerSize = 28
        val stringCount = strings.size
        val styleCount = styleOffsets.size

        val stringDataOut = ByteArrayOutputStream()
        val newStringOffsets = IntArray(stringCount)

        for (i in 0 until stringCount) {
            newStringOffsets[i] = stringDataOut.size()
            val s = strings[i]
            val len = s.length
            if (len >= 0x8000) {
                val high = ((len shr 16) or 0x8000) and 0xFFFF
                val low = len and 0xFFFF
                stringDataOut.write(high and 0xFF)
                stringDataOut.write((high shr 8) and 0xFF)
                stringDataOut.write(low and 0xFF)
                stringDataOut.write((low shr 8) and 0xFF)
            } else {
                stringDataOut.write(len and 0xFF)
                stringDataOut.write((len shr 8) and 0xFF)
            }
            val utf16 = s.toByteArray(StandardCharsets.UTF_16LE)
            stringDataOut.write(utf16)
            // Null terminator (UTF-16)
            stringDataOut.write(0)
            stringDataOut.write(0)
        }

        // Pad string data to 4-byte boundary
        while (stringDataOut.size() % 4 != 0) {
            stringDataOut.write(0)
        }

        val stringBytes = stringDataOut.toByteArray()
        val stringsStart = headerSize + (stringCount * 4) + (styleCount * 4)
        val stylesStart = if (styleCount > 0) stringsStart + stringBytes.size else 0
        val paddedStylesSize = if (rawStylesData.isNotEmpty()) {
            ((rawStylesData.size + 3) / 4) * 4
        } else {
            0
        }
        val chunkSize = stringsStart + stringBytes.size + paddedStylesSize

        val out = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(RES_STRING_POOL_TYPE.toShort())
        out.putShort(headerSize.toShort())
        out.putInt(chunkSize)
        out.putInt(stringCount)
        out.putInt(styleCount)
        out.putInt(0) // flags = 0 (UTF-16LE, unsorted)
        out.putInt(stringsStart)
        out.putInt(stylesStart)

        for (off in newStringOffsets) out.putInt(off)
        for (off in styleOffsets) out.putInt(off)
        out.put(stringBytes)
        if (rawStylesData.isNotEmpty()) {
            out.put(rawStylesData)
        }
        return out.array()
    }

    private fun readUtf8String(data: ByteArray, startOffset: Int): String {
        var pos = startOffset
        // UTF-16 length (1 or 2 bytes)
        val u16Len = data[pos++].toInt() and 0xFF
        if ((u16Len and 0x80) != 0) pos++
        // UTF-8 byte length (1 or 2 bytes)
        var byteLen = data[pos++].toInt() and 0xFF
        if ((byteLen and 0x80) != 0) {
            byteLen = ((byteLen and 0x7F) shl 8) or (data[pos++].toInt() and 0xFF)
        }
        return String(data, pos, byteLen, StandardCharsets.UTF_8)
    }

    private fun readUtf16String(data: ByteArray, startOffset: Int): String {
        var pos = startOffset
        var charLen = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        if ((charLen and 0x8000) != 0) {
            val low = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            charLen = ((charLen and 0x7FFF) shl 16) or low
        }
        return String(data, pos, charLen * 2, StandardCharsets.UTF_16LE)
    }
}
