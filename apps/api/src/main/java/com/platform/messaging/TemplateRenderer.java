package com.platform.messaging;

import org.springframework.web.util.HtmlUtils;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {{variable}} substitution. Unknown variables render as empty strings; HTML output is escaped. */
public final class TemplateRenderer {
    static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");

    private TemplateRenderer() {}

    public static String render(String template, Map<String, Object> vars, boolean html) {
        if (template == null) return null;
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = vars.getOrDefault(m.group(1), "");
            String s = v == null ? "" : String.valueOf(v);
            m.appendReplacement(sb, Matcher.quoteReplacement(html ? HtmlUtils.htmlEscape(s) : s));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static boolean references(String template, Set<String> variableNames) {
        if (template == null) return false;
        Matcher m = VAR.matcher(template);
        while (m.find()) if (variableNames.contains(m.group(1))) return true;
        return false;
    }

    /** Minimal, safe HTML wrapper for plain-text bodies (line breaks → paragraphs). */
    public static String textToHtml(String text, String footerHtml) {
        StringBuilder sb = new StringBuilder("<!doctype html><html><body style=\"font-family:system-ui,Arial,sans-serif;line-height:1.5;color:#222\">");
        for (String p : text.split("\\n\\s*\\n")) sb.append("<p>").append(HtmlUtils.htmlEscape(p).replace("\n", "<br>")).append("</p>");
        if (footerHtml != null) sb.append("<hr style=\"border:none;border-top:1px solid #eee\"><p style=\"font-size:12px;color:#777\">").append(footerHtml).append("</p>");
        return sb.append("</body></html>").toString();
    }
}
