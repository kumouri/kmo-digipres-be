package com.kumouri.kmodigipresbe.module.proposals;

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
 * Renders a full Statement of Work to a PDF (AI Proposal / SOW generator, band 4620-4639): the priced
 * {@link Quote} (header + line-item table + totals) <strong>plus</strong> the four narrative
 * {@link SowDraft} sections (Scope / Deliverables / Assumptions / Timeline). "A SOW is a priced Quote
 * with prose" — this renderer fuses both halves into one client-facing document.
 *
 * <h2>A sibling of {@link com.kumouri.kmodigipresbe.service.quote.QuotePdfService} — NOT a modification</h2>
 * {@code QuotePdfService} stays empty-diff vs {@code main} (the reused-core acceptance bar). This is a
 * new, additive class in the proposals module that <em>mirrors</em> its OpenPDF (iText fork) idiom: the
 * same {@code com.lowagie.text.*} library, the same Helvetica font ladder, the same line-items /
 * totals {@link PdfPTable} construction, and — crucially — the same blocking-bridge pattern
 * ({@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())}) so the blocking iText render
 * never runs on the Netty event loop. The only delta is the four SOW prose sections appended after the
 * quote totals, and a SOW (vs QUOTE) title.
 *
 * <p>A render failure becomes {@code DigiPresBeException(..., 2100, 500)} — the same code
 * {@code QuotePdfService} uses for a PDF-generation failure (PDF-layer parity).
 */
@Component
public class SowPdfService {

    private static final Font H1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
    private static final Font H2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 10);
    private static final Font BODY_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Renders the SOW PDF bytes off the Netty loop. Mirrors
     * {@code QuotePdfService.render(Quote)}'s {@code boundedElastic} blocking bridge.
     *
     * @param quote    the priced DRAFT {@link Quote} (header + line items + totals)
     * @param sowDraft the prose half (Scope / Deliverables / Assumptions / Timeline); may be
     *                 {@code null} (a quote with no linked draft renders the quote half only)
     */
    public Mono<byte[]> render(Quote quote, SowDraft sowDraft) {
        return Mono.fromCallable(() -> renderBlocking(quote, sowDraft))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private byte[] renderBlocking(Quote quote, SowDraft sowDraft) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.LETTER, 54, 54, 54, 54);
            PdfWriter.getInstance(doc, out);
            doc.open();

            // ── header ──
            doc.add(new Paragraph("STATEMENT OF WORK", H1));
            doc.add(new Paragraph(" "));
            doc.add(new Paragraph(
                    "Quote: " + safe(quote.getQuoteNumber(), "(draft)")
                    + "    Status: " + (quote.getStatus() == null ? "DRAFT" : quote.getStatus().name())
                    + "    Issued: " + (quote.getIssuedAt() == null ? "-" : quote.getIssuedAt().format(DATE_FMT))
                    + "    Expires: " + (quote.getExpiresAt() == null ? "-" : quote.getExpiresAt().format(DATE_FMT)),
                    BODY));
            doc.add(new Paragraph(" "));

            // ── priced line items + totals (the quote half — mirrors QuotePdfService) ──
            doc.add(new Paragraph("Line items", H2));
            doc.add(linesTable(quote));
            doc.add(new Paragraph(" "));
            doc.add(totalsTable(quote));

            // ── prose sections (the SOW half) ──
            if (sowDraft != null) {
                proseSection(doc, "Scope", sowDraft.getScope());
                proseSection(doc, "Deliverables", sowDraft.getDeliverables());
                proseSection(doc, "Assumptions", sowDraft.getAssumptions());
                proseSection(doc, "Timeline", sowDraft.getTimeline());
            }

            doc.close();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new DigiPresBeException("SOW PDF generation failed: " + ex.getMessage(), 2100, 500);
        }
    }

    /** Adds a titled prose block, skipping a blank section so an empty draft renders cleanly. */
    private void proseSection(Document doc, String heading, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        doc.add(new Paragraph(" "));
        doc.add(new Paragraph(heading, H2));
        doc.add(new Paragraph(body, BODY));
    }

    // ── line-items + totals tables (mirror QuotePdfService's construction exactly) ──

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
