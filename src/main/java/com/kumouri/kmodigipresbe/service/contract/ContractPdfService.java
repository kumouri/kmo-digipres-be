package com.kumouri.kmodigipresbe.service.contract;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * Renders a {@link Contract} to PDF bytes (Phase F — F.4).
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>jmustache expands {@code bodyTemplate} / {@code defaultTitle} from
 *       {@link Contract#getVariables()} — exactly the
 *       {@code EmailTemplateRenderer} compiler config
 *       ({@code escapeHTML(false).defaultValue("")});
 *       a {@link MustacheException} becomes {@code DigiPresBeException(…, 400)}.</li>
 *   <li>OpenPDF writes the expanded body as a plain-text paragraph into a
 *       {@link ByteArrayOutputStream} — exactly the
 *       {@code QuotePdfService.Mono.fromCallable(…).subscribeOn(Schedulers.boundedElastic())}
 *       blocking-bridge pattern; a failure becomes
 *       {@code DigiPresBeException(…, 500)}.</li>
 * </ol>
 *
 * <p>Blocking PDF work runs on {@code Schedulers.boundedElastic()} — never on the
 * Netty event loop (CLAUDE.md safety rule).
 */
@Component
public class ContractPdfService {

    /**
     * jmustache compiler — exact {@code EmailTemplateRenderer} config:
     * HTML escaping off (body is prose / the template author owns escaping),
     * missing variables render as empty string rather than throwing.
     */
    private static final Mustache.Compiler COMPILER = Mustache.compiler()
            .escapeHTML(false)
            .defaultValue("");

    private static final Font H1   = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 10);

    /**
     * Renders the contract's {@code bodyTemplate} (and optional {@code defaultTitle}
     * source on the template) against the contract's snapshotted {@code variables}
     * and returns the resulting PDF bytes.
     *
     * @param contract  the contract to render (must have a non-null {@code title};
     *                  body comes from the caller-supplied {@code bodyTemplate})
     * @param bodyTemplate  the jmustache source for the document body
     * @param titleTemplate optional jmustache source for the title; if {@code null}
     *                      or blank the contract's already-rendered {@code title} is
     *                      used as-is (no second expansion)
     * @return PDF bytes on the boundedElastic scheduler
     */
    public Mono<byte[]> render(Contract contract, String bodyTemplate, String titleTemplate) {
        return Mono.fromCallable(() -> renderBlocking(contract, bodyTemplate, titleTemplate))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private byte[] renderBlocking(Contract contract, String bodyTemplate, String titleTemplate) {
        Map<String, Object> vars = contract.getVariables() == null
                ? Map.of() : contract.getVariables();

        // 1. Expand bodyTemplate via jmustache
        String expandedBody;
        try {
            expandedBody = COMPILER.compile(bodyTemplate == null ? "" : bodyTemplate).execute(vars);
        } catch (MustacheException ex) {
            throw new DigiPresBeException(
                    "Contract template rendering failed: " + ex.getMessage(), 3702, 400);
        }

        // 2. Resolve title — expand titleTemplate if non-blank, else use contract.title
        String title = contract.getTitle();
        if (titleTemplate != null && !titleTemplate.isBlank()) {
            try {
                title = COMPILER.compile(titleTemplate).execute(vars);
            } catch (MustacheException ex) {
                throw new DigiPresBeException(
                        "Contract title template rendering failed: " + ex.getMessage(), 3702, 400);
            }
        }

        // 3. Write PDF via OpenPDF — exactly the QuotePdfService blocking pattern
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.LETTER, 54, 54, 72, 72);
            PdfWriter.getInstance(doc, out);
            doc.open();
            if (title != null && !title.isBlank()) {
                doc.add(new Paragraph(title, H1));
                doc.add(new Paragraph(" "));
            }
            doc.add(new Paragraph(expandedBody, BODY));
            doc.close();
            return out.toByteArray();
        } catch (DigiPresBeException dpe) {
            throw dpe;  // re-throw mustache errors that slipped through the catch above
        } catch (Exception ex) {
            throw new DigiPresBeException(
                    "Contract PDF generation failed: " + ex.getMessage(), 3799, 500);
        }
    }
}
