package com.kumouri.kmodigipresbe.service.quote;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.model.quote.Quote;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * Renders a quote to a single-page PDF via OpenPDF. Pure CPU work but uses the
 * blocking iText API, so wrapped on {@code Schedulers.boundedElastic()} consistent
 * with every other blocking op in the codebase.
 */
@Component
public class QuotePdfService {

    private static final Font H1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
    private static final Font H2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 10);
    private static final Font BODY_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public Mono<byte[]> render(Quote quote) {
        return Mono.fromCallable(() -> renderBlocking(quote))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private byte[] renderBlocking(Quote quote) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.LETTER, 54, 54, 54, 54);
            PdfWriter.getInstance(doc, out);
            doc.open();
            doc.add(new Paragraph("QUOTE " + safe(quote.getQuoteNumber(), "(no number)"), H1));
            doc.add(new Paragraph(" "));
            doc.add(new Paragraph(
                    "Status: " + (quote.getStatus() == null ? "DRAFT" : quote.getStatus().name())
                    + "    Issued: " + (quote.getIssuedAt() == null ? "-" : quote.getIssuedAt().format(DATE_FMT))
                    + "    Expires: " + (quote.getExpiresAt() == null ? "-" : quote.getExpiresAt().format(DATE_FMT)),
                    BODY));
            doc.add(new Paragraph(" "));

            doc.add(new Paragraph("Line items", H2));
            doc.add(linesTable(quote));
            doc.add(new Paragraph(" "));

            doc.add(totalsTable(quote));
            if (quote.getNotes() != null && !quote.getNotes().isBlank()) {
                doc.add(new Paragraph(" "));
                doc.add(new Paragraph("Notes", H2));
                doc.add(new Paragraph(quote.getNotes(), BODY));
            }
            if (quote.getTerms() != null && !quote.getTerms().isBlank()) {
                doc.add(new Paragraph(" "));
                doc.add(new Paragraph("Terms", H2));
                doc.add(new Paragraph(quote.getTerms(), BODY));
            }
            doc.close();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new DigiPresBeException("PDF generation failed: " + ex.getMessage(), 2100, 500);
        }
    }

    private PdfPTable linesTable(Quote q) {
        PdfPTable t = new PdfPTable(new float[]{3, 1, 1, 1, 1, 1.5f});
        t.setWidthPercentage(100);
        header(t, "Description");
        header(t, "Qty");
        header(t, "Unit");
        header(t, "Disc %");
        header(t, "Tax %");
        header(t, "Line total");
        if (q.getLineItems() != null) {
            for (LineItem li : q.getLineItems()) {
                cell(t, safe(li.getDescription(), li.getSku()));
                cell(t, num(li.getQuantity()));
                cell(t, money(li.getUnitPrice(), q.getCurrency()));
                cell(t, num(li.getDiscountPercent()));
                cell(t, num(li.getTaxPercent()));
                cell(t, money(li.getLineTotal(), q.getCurrency()));
            }
        }
        return t;
    }

    private PdfPTable totalsTable(Quote q) {
        PdfPTable t = new PdfPTable(new float[]{4, 1});
        t.setWidthPercentage(50);
        t.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell(t, "Subtotal", true);
        cell(t, money(q.getSubtotal(), q.getCurrency()));
        cell(t, "Discount", true);
        cell(t, money(q.getDiscountTotal(), q.getCurrency()));
        cell(t, "Tax", true);
        cell(t, money(q.getTaxTotal(), q.getCurrency()));
        cell(t, "Total", true);
        cell(t, money(q.getTotal(), q.getCurrency()));
        return t;
    }

    private void header(PdfPTable t, String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, BODY_BOLD));
        c.setHorizontalAlignment(Element.ALIGN_LEFT);
        c.setPadding(4);
        t.addCell(c);
    }

    private void cell(PdfPTable t, String text) {
        cell(t, text, false);
    }

    private void cell(PdfPTable t, String text, boolean bold) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, bold ? BODY_BOLD : BODY));
        c.setPadding(4);
        t.addCell(c);
    }

    private String num(BigDecimal v) {
        return v == null ? "0" : v.toPlainString();
    }

    private String money(BigDecimal v, String currency) {
        String amt = v == null ? "0.00" : v.toPlainString();
        return (currency == null ? "" : currency + " ") + amt;
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
