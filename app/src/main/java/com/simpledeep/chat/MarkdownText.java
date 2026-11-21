package com.simpledeep.chat;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.View;

import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.ListBlock;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MarkdownText {
    private static final Parser PARSER = Parser.builder()
            .extensions(Collections.singletonList(TablesExtension.create()))
            .build();
    private static final Pattern WEB_LINK = Pattern.compile(
            "(?i)https?://[^\\s<>\\[\\]{}\"'，。；、！？（）《》]+"
    );
    private static final int CODE_BACKGROUND = 0x177F8CA8;
    private static final int QUOTE_COLOR = 0xFF8291AF;
    private static final float[] HEADING_SIZES = {1.36f, 1.24f, 1.13f, 1.13f, 1.13f, 1.13f};

    private MarkdownText() { }

    static CharSequence render(String markdown, Consumer<String> openLink) {
        if (markdown == null || markdown.isEmpty()) return "";
        Renderer renderer = new Renderer(openLink);
        renderer.blocks(PARSER.parse(markdown), 0);
        return renderer.text;
    }

    private static final class Renderer {
        final SpannableStringBuilder text = new SpannableStringBuilder();
        private final Consumer<String> openLink;

        Renderer(Consumer<String> openLink) {
            this.openLink = openLink;
        }

        void blocks(Node container, int depth) {
            boolean first = true;
            for (Node block = container.getFirstChild(); block != null; block = block.getNext()) {
                if (block instanceof LinkReferenceDefinition) continue;
                if (!first) newlines(2);
                block(block, depth);
                first = false;
            }
        }

        private void block(Node block, int depth) {
            int start = text.length();
            if (depth > 40) {
                // Flatten exceptionally deep nesting while preserving the text.
                flatten(block);
            } else if (block instanceof Heading) {
                inlineChildren(block, true, depth);
                int level = ((Heading) block).getLevel();
                style(new RelativeSizeSpan(HEADING_SIZES[level - 1]), start);
                style(new StyleSpan(Typeface.BOLD), start);
            } else if (block instanceof ListBlock) {
                list((ListBlock) block, depth);
            } else if (block instanceof BlockQuote) {
                blocks(block, depth + 1);
                if (text.length() > start) {
                    newlines(1);
                    style(new QuoteSpan(QUOTE_COLOR), start);
                }
            } else if (block instanceof FencedCodeBlock) {
                code(withoutTrailingNewline(((FencedCodeBlock) block).getLiteral()));
            } else if (block instanceof IndentedCodeBlock) {
                code(withoutTrailingNewline(((IndentedCodeBlock) block).getLiteral()));
            } else if (block instanceof TableBlock) {
                table((TableBlock) block, depth);
            } else if (block instanceof ThematicBreak) {
                text.append("──────────");
            } else if (block instanceof HtmlBlock) {
                text.append(withoutTrailingNewline(((HtmlBlock) block).getLiteral()));
            } else {
                inlineChildren(block, true, depth);
            }
        }

        private void list(ListBlock list, int depth) {
            OrderedList ordered = list instanceof OrderedList ? (OrderedList) list : null;
            Integer start = ordered == null ? null : ordered.getMarkerStartNumber();
            int number = start == null ? 1 : start;
            boolean first = true;
            for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                if (!first) newlines(list.isTight() ? 1 : 2);
                for (int i = 0; i < depth; i++) text.append("  ");
                text.append(ordered == null ? "• " : (number++) + ". ");

                Node child = item.getFirstChild();
                if (child instanceof Paragraph) {
                    inlineChildren(child, true, depth + 1);
                    child = child.getNext();
                }
                while (child != null) {
                    newlines(child instanceof ListBlock || list.isTight() ? 1 : 2);
                    block(child, depth + 1);
                    child = child.getNext();
                }
                first = false;
            }
        }

        private void table(TableBlock table, int depth) {
            boolean firstRow = true;
            for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
                for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                    if (!firstRow) newlines(1);
                    int start = text.length();
                    boolean firstCell = true;
                    for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                        if (!(cell instanceof TableCell)) continue;
                        if (!firstCell) text.append("  │  ");
                        inlineChildren(cell, true, depth + 1);
                        firstCell = false;
                    }
                    if (section instanceof TableHead) style(new StyleSpan(Typeface.BOLD), start);
                    firstRow = false;
                }
            }
        }

        private void inlineChildren(Node parent, boolean linksAllowed, int depth) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
                int start = text.length();
                if (depth > 40) {
                    flatten(node);
                } else if (node instanceof Text) {
                    plainText(((Text) node).getLiteral(), linksAllowed);
                } else if (node instanceof Code) {
                    code(((Code) node).getLiteral());
                } else if (node instanceof Emphasis) {
                    inlineChildren(node, linksAllowed, depth + 1);
                    style(new StyleSpan(Typeface.ITALIC), start);
                } else if (node instanceof StrongEmphasis) {
                    inlineChildren(node, linksAllowed, depth + 1);
                    style(new StyleSpan(Typeface.BOLD), start);
                } else if (node instanceof Link) {
                    inlineChildren(node, false, depth + 1);
                    if (linksAllowed) addLink(start, text.length(), ((Link) node).getDestination());
                } else if (node instanceof Image) {
                    inlineChildren(node, false, depth + 1);
                    if (text.length() == start) text.append("图片");
                    style(new StyleSpan(Typeface.ITALIC), start);
                } else if (node instanceof HtmlInline) {
                    text.append(((HtmlInline) node).getLiteral());
                } else if (node instanceof HardLineBreak || node instanceof SoftLineBreak) {
                    text.append('\n');
                } else {
                    inlineChildren(node, linksAllowed, depth + 1);
                }
            }
        }

        private void flatten(Node root) {
            // Walk only this subtree, keeping fallback depth independent of the input.
            Node node = root;
            while (node != null) {
                if (node instanceof Text) text.append(((Text) node).getLiteral());
                else if (node instanceof Code) text.append(((Code) node).getLiteral());
                else if (node instanceof FencedCodeBlock) text.append(((FencedCodeBlock) node).getLiteral());
                else if (node instanceof IndentedCodeBlock) text.append(((IndentedCodeBlock) node).getLiteral());
                else if (node instanceof HtmlInline) text.append(((HtmlInline) node).getLiteral());
                else if (node instanceof HtmlBlock) text.append(((HtmlBlock) node).getLiteral());
                else if (node instanceof HardLineBreak || node instanceof SoftLineBreak) text.append('\n');

                if (node.getFirstChild() != null) {
                    node = node.getFirstChild();
                    continue;
                }
                while (node != root && node.getNext() == null) node = node.getParent();
                node = node == root ? null : node.getNext();
            }
        }

        private void plainText(String literal, boolean linksAllowed) {
            int start = text.length();
            text.append(literal);
            if (!linksAllowed || openLink == null || !literal.contains(":")) return;
            Matcher matcher = WEB_LINK.matcher(literal);
            while (matcher.find()) {
                int end = matcher.end();
                while (end > matcher.start() && ".,!?;:".indexOf(literal.charAt(end - 1)) >= 0) end--;
                if (end > matcher.start() && literal.charAt(end - 1) == ')' && matcher.start() > 0
                        && literal.charAt(matcher.start() - 1) == '(') {
                    end--;
                }
                addLink(start + matcher.start(), start + end, literal.substring(matcher.start(), end));
            }
        }

        private void addLink(int start, int end, String url) {
            if (start == end || openLink == null || !isWebLink(url)) return;
            text.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View view) {
                    openLink.accept(url);
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        private void code(String literal) {
            int start = text.length();
            text.append(literal);
            style(new TypefaceSpan("monospace"), start);
            style(new BackgroundColorSpan(CODE_BACKGROUND), start);
        }

        private void style(Object style, int start) {
            if (start < text.length()) {
                text.setSpan(style, start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        private void newlines(int count) {
            if (text.length() == 0) return;
            int existing = 0;
            int position = text.length() - 1;
            while (existing < count && position >= 0 && text.charAt(position--) == '\n') existing++;
            while (existing++ < count) text.append('\n');
        }
    }

    private static String withoutTrailingNewline(String text) {
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }

    private static boolean isWebLink(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null) return false;
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            int dot = host.lastIndexOf('.');
            // Open domain names only. Local host names and numeric addresses stay as plain text.
            return dot > 0 && !Character.isDigit(host.charAt(dot + 1))
                    && !host.endsWith(".localhost") && !host.endsWith(".local") && !host.endsWith(".internal");
        } catch (URISyntaxException error) {
            return false;
        }
    }
}
