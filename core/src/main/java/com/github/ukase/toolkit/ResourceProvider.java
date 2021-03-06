/*
 * Copyright (c) 2015 Konstantin Lepa <konstantin+ukase@lepabox.net>
 *
 * This file is part of Ukase.
 *
 *  Ukase is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.ukase.toolkit;

import com.github.jknack.handlebars.Handlebars;
import com.github.ukase.config.UkaseSettings;
import com.github.ukase.toolkit.helpers.AbstractHelper;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.pdf.BaseFont;
import lombok.extern.log4j.Log4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.xhtmlrenderer.layout.SharedContext;
import org.xhtmlrenderer.pdf.ITextFontResolver;
import org.xhtmlrenderer.pdf.ITextRenderer;
import org.xhtmlrenderer.util.XRRuntimeException;
import org.xml.sax.SAXParseException;

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;

@Service
@Log4j
public class ResourceProvider {
    private static final String COMMON_DOCTYPE = "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\" " +
            "\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">";
    private static final String DOCTYPE_HTML5 = "<!DOCTYPE html>";
    private static final Pattern HTML_TAG = Pattern.compile("<html([^>]*)lang=\"([^\"]+)\"([^>]*)>");
    private static final String HTML_TAG_REPLACEMENT =
            "<html$1lang=\"$2\" xml:lang=\"$2\" xmlns=\"http://www.w3.org/1999/xhtml\"$3>";
    private static final String ENTITY_EQUAL = "&#x3D";
    private static final String ENTITY_EQUAL_ = "&#x3D;";
    private static final String ENTITY_QUOTE = "&#x27;";
    private static final String SYMBOL_QUOTE = "'";
    private static final String SYMBOL_EQUAL = "=";

    private final ApplicationContext context;
    private final CompoundSource source;
    private final CompoundTemplateLoader templateLoader;
    private final String defaultFont;
    private final String resourcesPath;

    @Autowired
    public ResourceProvider(ApplicationContext context,
                            CompoundSource source,
                            CompoundTemplateLoader templateLoader,
                            UkaseSettings settings) {
        this.context = context;
        this.source = source;
        this.templateLoader = templateLoader;
        this.resourcesPath = resolvePath(settings.getResources());
        this.defaultFont = this.source.getFontsUrls().stream()
                .filter(name -> !(name.contains("Bold")) && !(name.contains("Italic")))
                .findAny().orElse(null);
    }

    @Bean
    public Handlebars getEngine() {
        Handlebars engine = new Handlebars(templateLoader);

        if (source.hasHelpers()) {
            source.getHelpers().forEach(engine::registerHelper);
        }
        context.getBeansOfType(AbstractHelper.class).values().stream().forEach(
                helper -> engine.registerHelper(helper.getName(), helper)
        );

        return engine;
    }

    public ITextRenderer getRenderer(String htmlDocument) {
        ITextRenderer renderer = new ITextRenderer();
        initSharedContext(renderer);

        try {
            initFonts(renderer.getFontResolver());
            htmlDocument = filterHtml5Document(htmlDocument);
            renderer.setDocumentFromString(htmlDocument, resourcesPath);
            renderer.layout();
        } catch (IOException | DocumentException e) {
            log.error("Some problem in font loading");
        } catch (XRRuntimeException e) {
            if (e.getCause() instanceof SAXParseException) {
                SAXParseException parseException = (SAXParseException) e.getCause();
                String[] lines = htmlDocument.split("\r\n|\r|\n");
                log.error("Error in line -->\n" + lines[parseException.getLineNumber() - 1] + "\n<--", e);
            }
            throw new RenderException("Error in xhtml file processing", e);
        }

        return renderer;
    }

    public String getDefaultFont() {
        return defaultFont;
    }

    private void initFonts(ITextFontResolver fontResolver) throws IOException, DocumentException {
        for (String font: source.getFontsUrls()) {
            fontResolver.addFont(font, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        }
    }

    private void initSharedContext(ITextRenderer renderer) {
        SharedContext sharedContext = renderer.getSharedContext();
        sharedContext.setUserAgentCallback(new WrappedUserAgentCallback(source, sharedContext));
    }

    private String filterHtml5Document(String html5PossibleDocument) {
        String document = html5PossibleDocument;

        if (html5PossibleDocument.contains(DOCTYPE_HTML5)) {
            document = document.replace(DOCTYPE_HTML5, COMMON_DOCTYPE);
            document = HTML_TAG.matcher(document).replaceAll(HTML_TAG_REPLACEMENT);
        }

        return filterKnownEntities(document);
    }

    private String filterKnownEntities(String html) {
        return html.replace(ENTITY_QUOTE, SYMBOL_QUOTE)
                .replace(ENTITY_EQUAL_, SYMBOL_EQUAL)
                .replace(ENTITY_EQUAL, SYMBOL_EQUAL);
    }

    private String resolvePath(File resources) {
        if (resources != null && resources.isDirectory()) {
            return resources.toURI().toString();
        }
        return null;
    }
}
