package com.example.pdfunlock

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import org.bouncycastle.asn1.ASN1ObjectIdentifier
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
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

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
 *  - the certificate chain ends in a CCA India root certificate (assets/cca_roots)
 * Anything else is amber (could not fully trust) or red (altered / broken).
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
                checkOne(s, fileBytes, trust, end == lastEnd)
            } catch (e: Exception) {
                SigEntry(
                    signer = s.name ?: "Unknown signer",
                    issuer = "",
                    signedAt = null,
                    reason = null,
                    location = null,
                    level = SigLevel.BAD,
                    checks = listOf(SigCheck(CheckState.BAD, "Could not read this signature: ${e.message ?: e.javaClass.simpleName}"))
                )
            }
        }
        val level = when {
            entries.any { it.level == SigLevel.BAD } -> SigLevel.BAD
            entries.any { it.level == SigLevel.WARN } -> SigLevel.WARN
            else -> SigLevel.VALID
        }
        return SigReport(level, entries, trust.anchors.size)
    }

    // ---------------- one signature ----------------

    private fun checkOne(sig: PDSignature, file: ByteArray, trust: Trust, isLast: Boolean): SigEntry {
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

        val validAt = try { signerCert.checkValidity(at); true } catch (_: Exception) { false }
        if (validAt) {
            checks.add(SigCheck(CheckState.OK, "Signer certificate was valid on the signing date"))
        } else {
            checks.add(SigCheck(CheckState.BAD, "Signer certificate was expired or not yet valid on the signing date"))
            bad()
        }

        // 4. chain to a trusted CCA India root
        if (trust.anchors.isEmpty()) {
            checks.add(SigCheck(CheckState.WARN, "CCA India root certificates are not installed in the app, so trust could not be checked"))
            warn()
        } else {
            val res = buildChain(signerCert, embedded + trust.pool, trust.anchors, at)
            if (res.trusted) {
                checks.add(SigCheck(CheckState.OK, "Certificate chain ends at a CCA India root (Government of India)"))
            } else {
                checks.add(SigCheck(CheckState.WARN, res.note))
                warn()
            }
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

    private class ChainRes(val trusted: Boolean, val note: String)

    private fun buildChain(
        leaf: X509Certificate,
        pool: List<X509Certificate>,
        anchors: List<X509Certificate>,
        at: Date
    ): ChainRes {
        var cur = leaf
        for (depth in 0 until 8) {
            if (anchors.any { it == cur }) {
                return try {
                    cur.checkValidity(at)
                    ChainRes(true, "")
                } catch (_: Exception) {
                    ChainRes(false, "The CCA India root was not valid on the signing date")
                }
            }
            val selfSigned = cur.subjectX500Principal == cur.issuerX500Principal
            if (selfSigned) {
                return ChainRes(false, "Chain ends at a root that is not a CCA India root")
            }
            var next: X509Certificate? = null
            for (c in anchors + pool) {
                if (c == cur) continue
                if (c.subjectX500Principal != cur.issuerX500Principal) continue
                try {
                    cur.verify(c.publicKey)
                    next = c
                    break
                } catch (_: Exception) {}
            }
            if (next == null) {
                return ChainRes(false, "Issuer certificate is missing from the PDF, so the chain to CCA India could not be completed")
            }
            if (next.basicConstraints < 0) {
                return ChainRes(false, "Issuer certificate is not a CA certificate")
            }
            try {
                next.checkValidity(at)
            } catch (_: Exception) {
                return ChainRes(false, "A CA certificate in the chain was not valid on the signing date")
            }
            cur = next
        }
        return ChainRes(false, "Certificate chain is too long")
    }

    // ---------------- trust store ----------------

    private class Trust(val anchors: List<X509Certificate>, val pool: List<X509Certificate>)

    /** Reads every certificate in assets/cca_roots (DER or PEM). Self-signed ones are trust anchors, the rest help build chains. */
    private fun loadTrust(ctx: Context): Trust {
        val all = ArrayList<X509Certificate>()
        val names: Array<String> = try {
            ctx.assets.list("cca_roots") ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }
        val cf = CertificateFactory.getInstance("X.509")
        for (n in names) {
            try {
                ctx.assets.open("cca_roots/$n").use { s ->
                    for (c in cf.generateCertificates(s)) if (c is X509Certificate) all.add(c)
                }
            } catch (_: Exception) {
                // not a certificate — ignore
            }
        }
        val anchors = ArrayList<X509Certificate>()
        val pool = ArrayList<X509Certificate>()
        for (c in all) {
            val self = c.subjectX500Principal == c.issuerX500Principal && try { c.verify(c.publicKey); true } catch (_: Exception) { false }
            if (self) anchors.add(c) else pool.add(c)
        }
        return Trust(anchors, pool)
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
