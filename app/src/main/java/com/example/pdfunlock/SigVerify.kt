package com.example.pdfunlock

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.DERIA5String
import org.bouncycastle.asn1.x509.AccessDescription
import org.bouncycastle.asn1.x509.AuthorityInformationAccess
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.Time
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SigLevel { VALID, WARN, BAD }
enum class CheckState { OK, WARN, BAD, INFO }

class SigCheck(val state: CheckState, val text: String)

class SigEntry(
    val signer: String,
    val issuer: String,
    val signedAt: Date?,
    val reason: String?,
    val location: String?,
    val level: SigLevel,
    val checks: List<SigCheck>
)

class SigReport(val level: SigLevel, val entries: List<SigEntry>, val rootsLoaded: Int)

/**
 * Verifies the digital signatures of a PDF (Aadhaar, birth certificate, ration card...).
 * It is checked against the ORIGINAL file bytes, because the unlocked copy is re-written
 * and can never carry a valid signature.
 *
 * Green (VALID) only when ALL of these hold:
 *  - the signature maths verifies (file bytes match what was signed)
 *  - nothing was added after the (last) signature
 *  - the signer certificate was valid on the signing date
 *  - the certificate chain ends in a trusted root: a CCA India root (assets/cca_roots, covers
 *    Aadhaar, birth/death certificates, e-district, DigiLocker, GST, ration card... every Indian
 *    licensed CA) or a public root in the Android system store (private companies, universities...)
 * Missing intermediate certificates are downloaded from the certificate's own AIA link (needs internet)
 * and cached. Red (BAD) is used ONLY when the signature maths fails (file really changed).
 * Everything else that is not perfect is amber.
 */
object SigVerify {

    fun verify(ctx: Context, fileBytes: ByteArray, password: String): SigReport? {
        val doc = try {
            PDDocument.load(fileBytes, password)
        } catch (e: Exception) {
            return null
        }
        val sigs: List<PDSignature> = try {
            doc.signatureDictionaries
        } catch (e: Exception) {
            emptyList()
        } finally {
            try { doc.close() } catch (_: Exception) {}
        }

        val real = sigs.filter { s ->
            val r = try { s.byteRange } catch (_: Exception) { null }
            r != null && r.size == 4 && s.subFilter != "ETSI.RFC3161"
        }
        if (real.isEmpty()) return null

        val trust = loadTrust(ctx)
        val lastEnd = real.maxOf { it.byteRange[2] + it.byteRange[3] }

        val entries = real.map { s ->
            val end = s.byteRange[2] + s.byteRange[3]
            try {
                checkOne(ctx, s, fileBytes, trust, end == lastEnd)
            } catch (e: Exception) {
                SigEntry(
                    signer = s.name ?: "Unknown signer",
                    issuer = "",
                    signedAt = null,
                    reason = null,
                    location = null,
                    level = SigLevel.WARN,
                    checks = listOf(SigCheck(CheckState.WARN, "Could not read this signature: ${e.message ?: e.javaClass.simpleName}"))
                )
            }
        }
        val level = when {
            entries.any { it.level == SigLevel.BAD } -> SigLevel.BAD
            entries.any { it.level == SigLevel.WARN } -> SigLevel.WARN
            else -> SigLevel.VALID
        }
        return SigReport(level, entries, trust.cca.size + trust.sys.size)
    }

    // ---------------- one signature ----------------

    private fun checkOne(ctx: Context, sig: PDSignature, file: ByteArray, trust: Trust, isLast: Boolean): SigEntry {
        val checks = ArrayList<SigCheck>()
        var level = SigLevel.VALID
        fun warn() { if (level == SigLevel.VALID) level = SigLevel.WARN }
        fun bad() { level = SigLevel.BAD }

        val range = sig.byteRange
        val contents = sig.getContents(file)
        val signed = sig.getSignedContent(file)
        val cms = CMSSignedData(CMSProcessableByteArray(signed), contents)
        val si = cms.signerInfos.signers.firstOrNull() ?: throw IllegalStateException("no signer in signature")

        val conv = JcaX509CertificateConverter()
        val holders: List<X509CertificateHolder> = cms.certificates.getMatches(null).toList()
        val embedded: List<X509Certificate> = holders.map { conv.getCertificate(it) }
        val signerHolder = holders.firstOrNull { si.sid.match(it) }
            ?: throw IllegalStateException("signer certificate is not inside the PDF")
        val signerCert = conv.getCertificate(signerHolder)

        // 1. signature maths
        val cryptoOk = try {
            si.verify(JcaSimpleSignerInfoVerifierBuilder().build(signerCert))
        } catch (e: Exception) {
            false
        }
        if (cryptoOk) {
            checks.add(SigCheck(CheckState.OK, "Signature is mathematically valid"))
        } else {
            checks.add(SigCheck(CheckState.BAD, "Signature does not match — the file was changed after signing"))
            bad()
        }

        // 2. nothing added after signing
        val end = range[2] + range[3]
        val trailing = file.size - end
        val whole = trailing <= 0 || file.copyOfRange(end, file.size).all {
            it == 10.toByte() || it == 13.toByte() || it == 32.toByte() || it == 0.toByte()
        }
        if (whole || !isLast) {
            if (cryptoOk) checks.add(SigCheck(CheckState.OK, "Document unchanged since signing"))
        } else {
            checks.add(SigCheck(CheckState.WARN, "Extra content was added to the file after this signature"))
            warn()
        }

        // 3. signing time + certificate validity on that day
        var signAt: Date? = null
        try {
            val a = si.signedAttributes?.get(CMSAttributes.signingTime)
            if (a != null) signAt = Time.getInstance(a.attrValues.getObjectAt(0)).date
        } catch (_: Exception) {}
        if (signAt == null) signAt = sig.signDate?.time
        val at = signAt ?: Date()

        val nb = signerCert.notBefore.time
        val na = signerCert.notAfter.time
        val t = at.time
        val df = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
        if (t in nb..na) {
            checks.add(SigCheck(CheckState.OK, "Signer certificate was valid on the signing date"))
        } else if (t < nb && t >= nb - SKEW_MS) {
            // signing server clock slightly behind the certificate start time: common, harmless
            checks.add(SigCheck(CheckState.OK, "Signer certificate was valid on the signing date (within a day of its start time)"))
        } else {
            checks.add(
                SigCheck(
                    CheckState.WARN,
                    "Signer certificate was not valid on the signing date (valid ${df.format(Date(nb))} to ${df.format(Date(na))})"
                )
            )
            warn()
        }

        // 4. chain to a trusted root
        val res = buildChain(ctx, signerCert, embedded, trust, at)
        if (res.trusted) {
            checks.add(SigCheck(CheckState.OK, res.note))
        } else {
            checks.add(SigCheck(CheckState.WARN, res.note))
            warn()
        }

        checks.add(SigCheck(CheckState.INFO, "Revocation (CRL/OCSP) is not checked — it needs internet"))

        return SigEntry(
            signer = describe(signerCert),
            issuer = nameOf(signerCert.issuerX500Principal.name, signerCert, issuer = true),
            signedAt = signAt,
            reason = sig.reason,
            location = sig.location,
            level = level,
            checks = checks
        )
    }

    // ---------------- chain ----------------

    private const val SKEW_MS = 24L * 60 * 60 * 1000

    private class ChainRes(val trusted: Boolean, val note: String)

    private fun okAt(c: X509Certificate, at: Date): Boolean {
        val t = at.time
        return t <= c.notAfter.time && t >= c.notBefore.time - SKEW_MS
    }

    private fun isSelfSigned(c: X509Certificate): Boolean =
        c.subjectX500Principal == c.issuerX500Principal

    private fun findIssuer(cur: X509Certificate, from: List<X509Certificate>): X509Certificate? {
        for (c in from) {
            if (c == cur) continue
            if (c.subjectX500Principal != cur.issuerX500Principal) continue
            try {
                cur.verify(c.publicKey)
                return c
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun buildChain(
        ctx: Context,
        leaf: X509Certificate,
        embedded: List<X509Certificate>,
        trust: Trust,
        at: Date
    ): ChainRes {
        val pool = ArrayList<X509Certificate>()
        pool.addAll(embedded)
        pool.addAll(trust.pool)
        val anchors = trust.cca + trust.sys
        var fetchFailed = false
        var cur = leaf
        for (depth in 0 until 8) {
            val isCca = trust.cca.any { it == cur }
            val isSys = !isCca && trust.sys.any { it == cur }
            if (isCca || isSys) {
                return if (okAt(cur, at)) {
                    if (isCca) ChainRes(true, "Certificate chain ends at a CCA India root (Government of India)")
                    else ChainRes(true, "Certificate chain ends at a publicly trusted root CA (not a Government of India CA)")
                } else {
                    ChainRes(false, "The root certificate was not valid on the signing date")
                }
            }
            if (isSelfSigned(cur)) {
                return ChainRes(false, "Chain ends at a self-signed certificate that is not a trusted root")
            }
            var next = findIssuer(cur, anchors) ?: findIssuer(cur, pool)
            if (next == null) {
                // the PDF did not carry the issuer: download it from the certificate's own AIA link
                val fetched = fetchIssuers(ctx, cur)
                if (fetched.isEmpty()) fetchFailed = true
                else {
                    pool.addAll(fetched)
                    next = findIssuer(cur, fetched)
                }
            }
            if (next == null) {
                return ChainRes(
                    false,
                    if (fetchFailed)
                        "Issuer certificate is not in the PDF and could not be downloaded — connect to the internet and open the file again"
                    else
                        "Issuer certificate could not be found, so the chain to a trusted root could not be completed"
                )
            }
            if (next.basicConstraints < 0) {
                return ChainRes(false, "Issuer certificate is not a CA certificate")
            }
            if (!okAt(next, at)) {
                return ChainRes(false, "A CA certificate in the chain was not valid on the signing date")
            }
            cur = next
        }
        return ChainRes(false, "Certificate chain is too long")
    }

    // ---------------- AIA download (missing intermediate CA certificates) ----------------

    private fun aiaUrls(c: X509Certificate): List<String> {
        val out = ArrayList<String>()
        try {
            val ext = c.getExtensionValue("1.3.6.1.5.5.7.1.1") ?: return out
            val aia = AuthorityInformationAccess.getInstance(ASN1OctetString.getInstance(ext).octets)
            for (ad in aia.accessDescriptions) {
                if (ad.accessMethod != AccessDescription.id_ad_caIssuers) continue
                val loc = ad.accessLocation
                if (loc.tagNo != GeneralName.uniformResourceIdentifier) continue
                val u = DERIA5String.getInstance(loc.name).string
                if (u.startsWith("http://", true) || u.startsWith("https://", true)) out.add(u)
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun parseCerts(data: ByteArray): List<X509Certificate> {
        return try {
            CertificateFactory.getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(data))
                .filterIsInstance<X509Certificate>()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun download(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) return null
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            conn.inputStream.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 512 * 1024) return null
                }
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun sha256Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Certificates are checked by their own signature afterwards, so a bad download can never make a file look valid. */
    private fun fetchIssuers(ctx: Context, c: X509Certificate): List<X509Certificate> {
        val out = ArrayList<X509Certificate>()
        val dir = File(ctx.filesDir, "ca_cache")
        try { dir.mkdirs() } catch (_: Exception) {}
        for (u in aiaUrls(c).take(3)) {
            val f = File(dir, sha256Hex(u) + ".bin")
            var data: ByteArray? = try { if (f.exists() && f.length() > 0) f.readBytes() else null } catch (_: Exception) { null }
            if (data == null) {
                data = download(u) ?: continue
                if (parseCerts(data).isEmpty()) continue
                try { f.writeBytes(data) } catch (_: Exception) {}
            }
            out.addAll(parseCerts(data))
        }
        return out
    }

    // ---------------- trust store ----------------

    private class Trust(
        val cca: List<X509Certificate>,
        val sys: List<X509Certificate>,
        val pool: List<X509Certificate>
    )

    private fun isRoot(c: X509Certificate): Boolean =
        isSelfSigned(c) && try { c.verify(c.publicKey); true } catch (_: Exception) { false }

    /**
     * cca  = self-signed roots in assets/cca_roots (Government of India)
     * sys  = roots of the Android system store (public / commercial CAs)
     * pool = non-root certificates (intermediates) from assets and from earlier AIA downloads
     */
    private fun loadTrust(ctx: Context): Trust {
        val all = ArrayList<X509Certificate>()
        val cf = CertificateFactory.getInstance("X.509")
        val names: Array<String> = try {
            ctx.assets.list("cca_roots") ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }
        for (n in names) {
            try {
                ctx.assets.open("cca_roots/$n").use { s ->
                    for (c in cf.generateCertificates(s)) if (c is X509Certificate) all.add(c)
                }
            } catch (_: Exception) {
                // not a certificate — ignore
            }
        }
        val cca = ArrayList<X509Certificate>()
        val pool = ArrayList<X509Certificate>()
        for (c in all) if (isRoot(c)) cca.add(c) else pool.add(c)

        try {
            val dir = File(ctx.filesDir, "ca_cache")
            dir.listFiles()?.forEach { f ->
                try {
                    for (c in parseCerts(f.readBytes())) if (!isRoot(c)) pool.add(c)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }

        val sys = ArrayList<X509Certificate>()
        try {
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null, null)
            val en = ks.aliases()
            while (en.hasMoreElements()) {
                val c = ks.getCertificate(en.nextElement())
                if (c is X509Certificate) sys.add(c)
            }
        } catch (_: Exception) {
        }
        return Trust(cca, sys, pool)
    }

    // ---------------- names ----------------

    private fun rdn(c: X509Certificate, oid: ASN1ObjectIdentifier, issuer: Boolean): String? {
        val h = JcaX509CertificateHolder(c)
        val name = if (issuer) h.issuer else h.subject
        val first = name.getRDNs(oid).firstOrNull()?.first ?: return null
        return IETFUtils.valueToString(first.value)
    }

    private fun describe(c: X509Certificate): String {
        val cn = try { rdn(c, BCStyle.CN, false) } catch (_: Exception) { null }
        val o = try { rdn(c, BCStyle.O, false) } catch (_: Exception) { null }
        return when {
            cn != null && o != null && !cn.contains(o, ignoreCase = true) -> "$cn, $o"
            cn != null -> cn
            o != null -> o
            else -> c.subjectX500Principal.name
        }
    }

    private fun nameOf(fallback: String, c: X509Certificate, issuer: Boolean): String {
        val cn = try { rdn(c, BCStyle.CN, issuer) } catch (_: Exception) { null }
        return cn ?: fallback
    }
}
