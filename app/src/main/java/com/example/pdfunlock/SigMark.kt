package com.example.pdfunlock

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField

/**
 * The unlocked copy is re-written, so it can never carry a valid digital signature.
 * This removes the signature stamp from that copy: the yellow "?" and the
 * "Digitally signed by ... Date / Reason / Location" text, which belong to the signature field.
 * Everything else on the page (card details, QR, barcode, "Printed On") is untouched.
 * The ORIGINAL file is never changed, and the signature check is done on the original.
 */
object SigMark {

    private val SIG = COSName.getPDFName("Sig")

    private fun isSigWidget(a: PDAnnotation): Boolean {
        if (a !is PDAnnotationWidget) return false
        val d: COSDictionary = a.cosObject
        if (d.getCOSName(COSName.FT) == SIG) return true
        val parent = d.getDictionaryObject(COSName.PARENT) as? COSDictionary
        return parent?.getCOSName(COSName.FT) == SIG
    }

    fun removeStamp(doc: PDDocument) {
        try {
            for (page in doc.pages) {
                val all = page.annotations
                val keep = ArrayList<PDAnnotation>()
                for (a in all) if (!isSigWidget(a)) keep.add(a)
                if (keep.size != all.size) page.annotations = keep
            }
        } catch (_: Throwable) {
            // best effort: never block unlocking
        }
        try {
            val form = doc.documentCatalog.acroForm
            if (form != null) {
                val rest = form.fields.filter { it !is PDSignatureField }
                form.fields = rest
                form.cosObject.removeItem(COSName.getPDFName("SigFlags"))
            }
            doc.documentCatalog.cosObject.removeItem(COSName.getPDFName("Perms"))
        } catch (_: Throwable) {
        }
    }
}
