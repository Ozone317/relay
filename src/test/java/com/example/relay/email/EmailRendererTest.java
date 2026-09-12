package com.example.relay.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;

class EmailRendererTest {

    private EmailRenderer emailRenderer;
    private TemplateEngine htmlEngine;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver htmlResolver = new ClassLoaderTemplateResolver();
        htmlResolver.setPrefix("templates/");
        htmlResolver.setSuffix(".html");
        htmlResolver.setTemplateMode(TemplateMode.HTML);
        htmlResolver.setCharacterEncoding("UTF-8");
        htmlEngine = new TemplateEngine();
        htmlEngine.setTemplateResolver(htmlResolver);

        ClassLoaderTemplateResolver textResolver = new ClassLoaderTemplateResolver();
        textResolver.setPrefix("templates/");
        textResolver.setSuffix(".txt");
        textResolver.setTemplateMode(TemplateMode.TEXT);
        textResolver.setCharacterEncoding("UTF-8");
        TemplateEngine textEngine = new TemplateEngine();
        textEngine.setTemplateResolver(textResolver);

        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");

        emailRenderer = new EmailRenderer(htmlEngine, textEngine, messageSource);
    }

    @Test
    void render_producesSubjectHtmlAndText_withParamsInterpolated() {
        RenderedEmail email = emailRenderer.render(EmailTemplate.DEAD_LETTER_NOTIFICATION, Map.of("appName",
                "My App", "endpointUrl", "https://example.com/webhook", "lastError", "Connection refused"));

        assertThat(email.subject()).isEqualTo("Delivery failed permanently for My App");
        assertThat(email.html()).contains("My App");
        assertThat(email.html()).contains("https://example.com/webhook");
        assertThat(email.html()).contains("Relay");
        assertThat(email.text()).contains("My App");
        assertThat(email.text()).contains("Connection refused");
    }

    @Test
    void render_escapesHtmlUnsafeValues_inHtmlBody() {
        RenderedEmail email = emailRenderer.render(EmailTemplate.DEAD_LETTER_NOTIFICATION, Map.of("appName",
                "My App", "endpointUrl", "https://example.com/webhook", "lastError", "<script>alert(1)</script>"));

        assertThat(email.html()).doesNotContain("<script>alert(1)</script>");
        assertThat(email.html()).contains("&lt;script&gt;");
    }

    @Test
    void render_stripsCrLf_fromInterpolatedSubject() {
        RenderedEmail email = emailRenderer.render(EmailTemplate.DEAD_LETTER_NOTIFICATION,
                Map.of("appName", "Evil\r\nBcc: attacker@example.test", "endpointUrl", "https://example.com",
                        "lastError", "boom"));

        assertThat(email.subject()).doesNotContain("\r");
        assertThat(email.subject()).doesNotContain("\n");
    }

    @Test
    void thymeleafAttributeContext_escapesQuotesInAttributeValues() {
        // No production template needs an href yet, but password-reset (a later cycle) will embed a
        // reset URL in one - this proves Thymeleaf's own attribute-context escaping (th:href/th:attr)
        // is what's actually in effect, ahead of that need, using a throwaway inline template so no
        // unused production template file is required now. This proves QUOTE-escaping (an attacker
        // value cannot break out of the attribute) - it does NOT validate or restrict URL schemes
        // (e.g. "javascript:") - that is a distinct concern from attribute escaping and is not claimed
        // here.
        StringTemplateResolver stringResolver = new StringTemplateResolver();
        stringResolver.setTemplateMode(TemplateMode.HTML);
        TemplateEngine inlineEngine = new TemplateEngine();
        inlineEngine.setTemplateResolver(stringResolver);

        String template = "<a th:href=\"${url}\">link</a>";
        Context context = new Context();
        context.setVariable("url", "https://example.com/reset?token=\"><script>alert(1)</script>");

        String rendered = inlineEngine.process(template, context);

        assertThat(rendered).doesNotContain("\"><script>");
    }
}
