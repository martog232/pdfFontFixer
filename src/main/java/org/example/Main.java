package org.example;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws IOException {

        if (args.length < 3) {
            printUsageAndExit();
        }

        Path inputPdf = Path.of(args[0]);
        Path outputPdf = Path.of(args[1]);
        Path fontTtf = Path.of(args[2]);

        if (!fontTtf.toString().toLowerCase().endsWith(".ttf")) {
            throw new IllegalArgumentException("Third argument must be a .ttf font file. Got: " + fontTtf);
        }
        if (!inputPdf.toString().toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("First argument must be a .pdf file. Got: " + inputPdf);
        }
        if (!outputPdf.toString().toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Second argument must be a .pdf file. Got: " + outputPdf);
        }

        float fontSize = 9f;
        if (args.length >= 4) {
            fontSize = Float.parseFloat(args[3]);
            if (fontSize <= 0) {
                throw new IllegalArgumentException("fontSize must be > 0");
            }
        }

        byte[] inputBytes = Files.readAllBytes(inputPdf);

        byte[] out;
        try (InputStream fontStream = Files.newInputStream(fontTtf)) {
            out = attachFontAndRewriteDA(inputBytes, fontStream, "CyrFont", fontSize);
        }

        Files.write(outputPdf, out);
        System.out.println("OK: wrote " + outputPdf.toAbsolutePath());
    }

    private static void printUsageAndExit() {
        System.err.println("""
                           Usage:
                             java -jar pdf-font-fixer-1.0.0.jar <input.pdf> <output.pdf> <font.ttf> [fontSize]
                           
                           Example:
                             java -jar target/pdf-font-fixer-1.0.0.jar template.pdf template_cyr.pdf DejaVuSans.ttf 9
                           """);
        System.exit(2);
    }

    /**
     * Embeds FULL Unicode font (not subset) and updates the DA for all text-like fields to use it.
     */
    public static byte[] attachFontAndRewriteDA(
        byte[] inputPdf,
        InputStream ttfFontStream,
        String fontResourceName, // used as /CyrFont
        float fontSize
    ) throws IOException {

        try (PDDocument doc = Loader.loadPDF(inputPdf)) {

            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
//            form.getField("person_issuername").setPartialName("person_issuerName");
//            form.getField("person_foreignIdCard").setPartialName("person_foreign");
//            form.getField("person_foreignIdCard2").setPartialName("person_foreignIdCard");
//            form.getField("person_curZip").setPartialName("person_currZip");
            if (form == null) {
                throw new IllegalStateException("PDF does not contain an AcroForm (no form fields).");
            }

            // Ensure default resources exist
            PDResources resources = form.getDefaultResources();
            if (resources == null) {
                resources = new PDResources();
                form.setDefaultResources(resources);
            }

            doc.getDocumentInformation().setTitle(null);
            doc.getDocumentInformation().setAuthor(null);

            // Embed FULL font so any future Cyrillic glyphs are available (not just a subset)
            PDFont font = PDType0Font.load(doc, ttfFontStream, false);

            COSName resName = COSName.getPDFName(fontResourceName);
            resources.put(resName, font);

            String newDA = "/" + fontResourceName + " " + fontSize + " Tf 0 g";
            String midNewDA = "/" + fontResourceName + " 7 Tf 0 g";
            String smallerNewDA = "/" + fontResourceName + " 6 Tf 0 g";
            String smallestNewDA = "/" + fontResourceName + " 5 Tf 0 g";

//             Update DA for text-like fields
            for (PDField field : form.getFieldTree()) {
                if (field instanceof PDTextField tf) {

                    //todo uncomment for the transfer request
                    if (tf.getFullyQualifiedName().equals("person_otherDocType") ||
                        tf.getFullyQualifiedName().equals("person_otherDocType1") ||
                        tf.getFullyQualifiedName().equals("employee_Name") ||
                        tf.getFullyQualifiedName().equals("employee_Pos") ||
                        tf.getFullyQualifiedName().equals("person_perm_phone") ||
                        tf.getFullyQualifiedName().equals("person_curr_phone")||
                        tf.getFullyQualifiedName().equals("person_issuerName")
                    ) {
                        tf.setDefaultAppearance(midNewDA);
                    } else if (tf.getFullyQualifiedName().equals("old_POK") ||
                        tf.getFullyQualifiedName().equals("old_fund") ||
                               tf.getFullyQualifiedName().equals("new_fund")) {
                        tf.setDefaultAppearance(smallerNewDA);
                    } else {
                        tf.setDefaultAppearance(newDA);
                    }


                    //todo uncomment for the contracts
//                    if (tf.getFullyQualifiedName().equals("person_permDistrict") ||
//                        tf.getFullyQualifiedName().equals("person_currDistrict") ||
//                        tf.getFullyQualifiedName().equals("person_permCity") ||
//                        tf.getFullyQualifiedName().equals("person_currCity") ||
//                        tf.getFullyQualifiedName().equals("person_permMin") ||
//                        tf.getFullyQualifiedName().equals("person_currMin")){
//                        tf.setDefaultAppearance(midNewDA);
//                    } else {
//                        tf.setDefaultAppearance(newDA);
//                    }





                } else if (field instanceof PDComboBox cb) {
                    cb.setDefaultAppearance(newDA);
                } else if (field instanceof PDListBox lb) {
                    lb.setDefaultAppearance(newDA);
                }
            }

            // Prefer not to rely on viewer regeneration once the template is fixed
            form.setNeedAppearances(false);

            // Save to bytes
            var baos = new java.io.ByteArrayOutputStream();
            doc.save(baos);

            PDResources res = doc.getDocumentCatalog()
                                 .getAcroForm()
                                 .getDefaultResources();

            res.getFontNames().forEach(name -> {
                try {
                    PDFont f = res.getFont(name);
                    System.out.println(name + " -> embedded = " +
                                       (f.getFontDescriptor() != null &&
                                        (f.getFontDescriptor().getFontFile() != null ||
                                         f.getFontDescriptor().getFontFile2() != null ||
                                         f.getFontDescriptor().getFontFile3() != null)));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });


            for (PDField field : form.getFieldTree()) {
                if (field instanceof PDTextField tf) {
                    System.out.println(tf.getFullyQualifiedName() + " DA = " + tf.getDefaultAppearance());
                }
            }

            return baos.toByteArray();
        }

    }
}