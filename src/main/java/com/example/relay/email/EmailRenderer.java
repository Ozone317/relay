package com.example.relay.email;

import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Component
public class EmailRenderer {

    private final TemplateEngine htmlTemplateEngine;
    private final TemplateEngine textTemplateEngine;
    private final MessageSource messageSource;

    public EmailRenderer(@Qualifier("templateEngine") TemplateEngine htmlTemplateEngine,
            @Qualifier("textTemplateEngine") TemplateEngine textTemplateEngine, MessageSource messageSource) {
        this.htmlTemplateEngine = htmlTemplateEngine;
        this.textTemplateEngine = textTemplateEngine;
        this.messageSource = messageSource;
    }

    public RenderedEmail render(EmailTemplate template, Map<String, Object> params) {
        Context context = new Context(Locale.ENGLISH, params);

        String rawSubject = messageSource.getMessage("email." + template.resourceName() + ".subject", null,
                Locale.ENGLISH);
        String subject = sanitizeSubject(interpolate(rawSubject, params));

        String html = htmlTemplateEngine.process("email/" + template.resourceName(), context);
        String text = textTemplateEngine.process("email/" + template.resourceName(), context);

        return new RenderedEmail(subject, html, text);
    }

    private String interpolate(String template, Map<String, Object> params) {
        String result = template;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    private String sanitizeSubject(String subject) {
        return subject.replaceAll("[\r\n]", " ").trim();
    }
}
