package com.kumouri.kmodigipresbe.service.template;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTemplateRendererTest {

    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    @Test
    void simpleSubstitution() {
        String out = renderer.render("Hi {{firstName}}!", Map.of("firstName", "Ceryce"));
        assertThat(out).isEqualTo("Hi Ceryce!");
    }

    @Test
    void missingVariable_rendersEmpty() {
        String out = renderer.render("Hello {{firstName}} {{lastName}}",
                Map.of("firstName", "Ceryce"));
        assertThat(out).isEqualTo("Hello Ceryce ");
    }

    @Test
    void sectionBlock_iteratesList() {
        String out = renderer.render(
                "Items: {{#items}}- {{.}} {{/items}}",
                Map.of("items", java.util.List.of("a", "b", "c")));
        assertThat(out).isEqualTo("Items: - a - b - c ");
    }

    @Test
    void htmlNotEscaped() {
        String out = renderer.render("<b>{{name}}</b>", Map.of("name", "<script>"));
        assertThat(out).isEqualTo("<b><script></b>");
    }

    @Test
    void nullSource_rendersEmpty() {
        assertThat(renderer.render(null, Map.of())).isEqualTo("");
    }

    @Test
    void malformedTemplate_throwsTranslated() {
        // jmustache rejects a mismatched section close — `#open` followed by `/wrong`.
        assertThatThrownBy(() -> renderer.render("{{#open}}body{{/wrong}}", Map.of()))
                .isInstanceOf(DigiPresBeException.class);
    }
}
