package com.clonemaster.manager

import android.content.Context
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * First experimental on-device APK patcher. It changes package-name strings in the binary
 * AndroidManifest string pool, removes old signing metadata, then signs the output with a fresh
 * temporary key. This is not a full decompiler/rebuilder; apps with package references in DEX,
 * split APKs, protected signatures, or special resources may not work.
 */
object ApkCloneEngine {
    data class Result(val output: File, val originalPackage: String, val targetPackage: String)

    fun createClone(context: Context, input: File, targetPackage: String): Result {
        require(input.isFile && input.extension.equals("apk", true)) { "اختَر ملف APK صالحًا" }
        require(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(targetPackage)) { "Package Name غير صالح" }
        val packageInfo = context.packageManager.getPackageArchiveInfo(input.absolutePath, 0)
            ?: throw IllegalArgumentException("تعذرت قراءة معلومات APK")
        val original = packageInfo.packageName ?: throw IllegalArgumentException("لم يتم العثور على Package Name")
        require(original != targetPackage) { "اختَر Package Name مختلفًا عن الأصل" }

        val dir = File(context.filesDir, "modified_apks").apply { mkdirs() }
        val safe = targetPackage.replace('.', '_')
        val unsigned = File(dir, "${safe}_${System.currentTimeMillis()}_unsigned.apk")
        val signed = File(dir, "${safe}_${System.currentTimeMillis()}.apk")
        try {
            rewriteZip(input, unsigned, original, targetPackage)
            signApk(unsigned, signed)
            unsigned.delete()
            val verifyInfo = context.packageManager.getPackageArchiveInfo(signed.absolutePath, 0)
                ?: throw IllegalStateException("تم إنشاء الملف لكن Android لم يتعرف عليه كـ APK")
            val actual = verifyInfo.packageName ?: ""
            if (actual != targetPackage) throw IllegalStateException("فشل التحقق: المعرّف داخل الملف هو $actual وليس $targetPackage")
            return Result(signed, original, targetPackage)
        } catch (e: Exception) {
            unsigned.delete()
            signed.delete()
            throw e
        }
    }

    private fun rewriteZip(input: File, output: File, oldPackage: String, newPackage: String) {
        ZipFile(input).use { zip ->
            val manifestEntry = zip.getEntry("AndroidManifest.xml")
                ?: throw IllegalArgumentException("AndroidManifest.xml غير موجود")
            val manifest = zip.getInputStream(manifestEntry).use { it.readBytes() }
            val changedManifest = BinaryManifestEditor.replacePackage(manifest, oldPackage, newPackage)
            var found = false
            ZipOutputStream(FileOutputStream(output)).use { out ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (isSignatureFile(entry.name)) continue
                    val newEntry = ZipEntry(entry.name)
                    newEntry.time = entry.time
                    out.putNextEntry(newEntry)
                    if (entry.name == "AndroidManifest.xml") {
                        out.write(changedManifest)
                        found = true
                    } else {
                        zip.getInputStream(entry).use { it.copyTo(out) }
                    }
                    out.closeEntry()
                }
            }
            if (!found) throw IllegalStateException("لم يتم تحديث AndroidManifest.xml")
        }
    }

    private fun isSignatureFile(name: String): Boolean {
        val upper = name.uppercase()
        if (!upper.startsWith("META-INF/")) return false
        val leaf = upper.substringAfterLast('/')
        return leaf == "MANIFEST.MF" || leaf.endsWith(".SF") || leaf.endsWith(".RSA") || leaf.endsWith(".DSA") || leaf.endsWith(".EC")
    }

    private fun signApk(input: File, output: File) {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=CloneMaster Local Signing")
        val certHolder = JcaX509v3CertificateBuilder(
            subject, BigInteger.valueOf(now), Date(now - 60_000L), Date(now + 10L * 365 * 24 * 60 * 60 * 1000), subject, pair.public
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
        val certificate = JcaX509CertificateConverter().getCertificate(certHolder)
        val signerConfig = ApkSigner.SignerConfig.Builder("CloneMaster", pair.private, listOf(certificate)).build()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }
}

/** Minimal Android binary XML string-pool editor. Preserves string indexes and styles. */
private object BinaryManifestEditor {
    private const val RES_XML = 0x0003
    private const val RES_STRING_POOL = 0x0001
    private const val UTF8_FLAG = 0x00000100

    fun replacePackage(xml: ByteArray, oldPackage: String, newPackage: String): ByteArray {
        if (xml.size < 8 || u16(xml, 0) != RES_XML) throw IllegalArgumentException("Manifest ليس بصيغة Android الثنائية المتوقعة")
        val rootSize = i32(xml, 4)
        if (rootSize !in 8..xml.size) throw IllegalArgumentException("حجم Manifest غير صالح")
        var offset = 8
        val chunks = ArrayList<ByteArray>()
        var replaced = false
        while (offset + 8 <= rootSize) {
            val type = u16(xml, offset)
            val size = i32(xml, offset + 4)
            if (size < 8 || offset + size > rootSize) throw IllegalArgumentException("بنية Manifest غير سليمة")
            val chunk = xml.copyOfRange(offset, offset + size)
            if (type == RES_STRING_POOL) {
                chunks += patchStringPool(chunk, oldPackage, newPackage)
                replaced = true
            } else chunks += chunk
            offset += size
        }
        if (!replaced) throw IllegalArgumentException("لم يتم العثور على String Pool داخل Manifest")
        val total = 8 + chunks.sumOf { it.size }
        val out = ByteArray(total)
        xml.copyInto(out, 0, 0, 8)
        putI32(out, 4, total)
        var dest = 8
        for (chunk in chunks) { chunk.copyInto(out, dest); dest += chunk.size }
        return out
    }

    private fun patchStringPool(chunk: ByteArray, old: String, new: String): ByteArray {
        if (chunk.size < 28) throw IllegalArgumentException("String Pool غير صالح")
        val headerSize = u16(chunk, 2)
        val stringCount = i32(chunk, 8)
        val styleCount = i32(chunk, 12)
        val flags = i32(chunk, 16)
        val stringsStart = i32(chunk, 20)
        val stylesStart = i32(chunk, 24)
        if (headerSize < 28 || stringCount < 0 || styleCount < 0 || stringsStart < headerSize || stringsStart > chunk.size) throw IllegalArgumentException("رؤوس String Pool غير صالحة")
        val offsetsStart = headerSize
        val offsetsEnd = offsetsStart + 4 * (stringCount + styleCount)
        if (offsetsEnd > chunk.size) throw IllegalArgumentException("جدول String Pool غير صالح")
        val stringOffsets = IntArray(stringCount) { i32(chunk, offsetsStart + it * 4) }
        val styleOffsets = IntArray(styleCount) { i32(chunk, offsetsStart + 4 * stringCount + it * 4) }
        val stringsEnd = if (stylesStart > 0) stylesStart else chunk.size
        if (stringsEnd < stringsStart || stringsEnd > chunk.size) throw IllegalArgumentException("حدود نصوص Manifest غير صالحة")
        val oldStringData = chunk.copyOfRange(stringsStart, stringsEnd)
        val strings = stringOffsets.map { decodeString(oldStringData, it, (flags and UTF8_FLAG) != 0) }
        var changed = false
        val updated = strings.map { value ->
            val replacement = when {
                value == old -> new
                value.startsWith("$old.") -> new + value.substring(old.length)
                else -> value
            }
            if (replacement != value) changed = true
            replacement
        }
        if (!changed) throw IllegalArgumentException("لم يتم العثور على Package Name الأصلي في Manifest؛ ربما الملف غير قياسي")
        val utf8 = (flags and UTF8_FLAG) != 0
        val stringData = java.io.ByteArrayOutputStream()
        val newOffsets = IntArray(updated.size)
        updated.forEachIndexed { index, value ->
            newOffsets[index] = stringData.size()
            stringData.write(encodeString(value, utf8))
        }
        while (stringData.size() % 4 != 0) stringData.write(0)
        val headerAndOffsetsSize = headerSize + 4 * (stringCount + styleCount)
        val newStringsStart = headerAndOffsetsSize
        val stringBytes = stringData.toByteArray()
        val styleBytes = if (stylesStart > 0) chunk.copyOfRange(stylesStart, chunk.size) else ByteArray(0)
        val newStylesStart = if (styleCount > 0 && stylesStart > 0) newStringsStart + stringBytes.size else 0
        val newSize = newStringsStart + stringBytes.size + styleBytes.size
        val out = ByteArray(newSize)
        chunk.copyInto(out, 0, 0, headerSize)
        putI32(out, 4, newSize)
        putI32(out, 20, newStringsStart)
        putI32(out, 24, newStylesStart)
        var pos = headerSize
        newOffsets.forEach { putI32(out, pos, it); pos += 4 }
        styleOffsets.forEach { putI32(out, pos, it); pos += 4 }
        stringBytes.copyInto(out, newStringsStart)
        if (styleBytes.isNotEmpty()) styleBytes.copyInto(out, newStylesStart)
        return out
    }

    private fun decodeString(data: ByteArray, offset: Int, utf8: Boolean): String {
        if (offset !in data.indices) throw IllegalArgumentException("String Pool offset غير صالح")
        return if (utf8) {
            var p = offset
            p += readLength8(data, p).second
            val (byteLength, used) = readLength8(data, p); p += used
            if (p + byteLength >= data.size) throw IllegalArgumentException("نص UTF-8 غير مكتمل")
            String(data, p, byteLength, Charsets.UTF_8)
        } else {
            val (length, used) = readLength16(data, offset)
            val p = offset + used
            if (p + length * 2 + 2 > data.size) throw IllegalArgumentException("نص UTF-16 غير مكتمل")
            String(data, p, length * 2, Charsets.UTF_16LE)
        }
    }

    private fun encodeString(value: String, utf8: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        if (utf8) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeLength8(out, value.length)
            writeLength8(out, bytes.size)
            out.write(bytes); out.write(0)
        } else {
            val bytes = value.toByteArray(Charsets.UTF_16LE)
            writeLength16(out, value.length)
            out.write(bytes); out.write(0); out.write(0)
        }
        return out.toByteArray()
    }

    private fun readLength8(b: ByteArray, p: Int): Pair<Int, Int> {
        val first = b[p].toInt() and 0xff
        return if ((first and 0x80) != 0) (((first and 0x7f) shl 8) or (b[p + 1].toInt() and 0xff)) to 2 else first to 1
    }
    private fun readLength16(b: ByteArray, p: Int): Pair<Int, Int> {
        val first = u16(b, p)
        return if ((first and 0x8000) != 0) (((first and 0x7fff) shl 16) or u16(b, p + 2)) to 4 else first to 2
    }
    private fun writeLength8(out: java.io.ByteArrayOutputStream, value: Int) {
        if (value > 0x7f) { out.write(((value shr 8) or 0x80) and 0xff); out.write(value and 0xff) } else out.write(value)
    }
    private fun writeLength16(out: java.io.ByteArrayOutputStream, value: Int) {
        if (value > 0x7fff) { out.write(((value shr 16) or 0x8000) and 0xff); out.write((value shr 8) and 0xff); out.write(value and 0xff) }
        else { out.write(value and 0xff); out.write((value shr 8) and 0xff) }
    }
    private fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8)
    private fun i32(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun putI32(b: ByteArray, p: Int, value: Int) { ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value) }
}
