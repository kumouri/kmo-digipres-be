package com.kumouri.kmodigipresbe.service.template;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;
import com.samskivert.mustache.Template;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EmailTemplateRenderer {

    private final Mustache.Compiler compiler = Mustache.compiler()
            .escapeHTML(false)        // body is already HTML or plain text; let template author own escaping
            .defaultValue("");        // missing variables render empty rather than throw

    public String render(String source, Map<String, Object> variables) {
        if (source == null) return "";
        try {
            Template t = compiler.compile(source);
            return t.execute(variables == null ? Map.of() : variables);
        } catch (MustacheException ex) {
            throw new DigiPresBeException(
                    "Mustache rendering failed: " + ex.getMessage(), 1600, 400);
        }
    }
}
