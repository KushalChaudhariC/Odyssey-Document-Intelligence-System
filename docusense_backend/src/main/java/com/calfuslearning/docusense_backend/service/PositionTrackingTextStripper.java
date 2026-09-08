package com.calfuslearning.docusense_backend.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * A PDFTextStripper that mirrors every character it writes into a parallel list of TextPosition,
 * so callers can later map a substring of the extracted text back to where it was drawn on the
 * page. Word/line/paragraph separators get a placeholder position (the nearest real character's)
 * since they don't correspond to an actual glyph.
 */
class PositionTrackingTextStripper extends PDFTextStripper {

    private final StringBuilder buffer = new StringBuilder();
    private final List<TextPosition> positions = new ArrayList<>();

    PositionTrackingTextStripper() throws IOException {
        super();
    }

    String text() {
        return buffer.toString();
    }

    List<TextPosition> positions() {
        return positions;
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
        buffer.append(text);
        positions.addAll(textPositions);
        super.writeString(text, textPositions);
    }

    @Override
    protected void writeWordSeparator() throws IOException {
        padWithPlaceholder(getWordSeparator());
        super.writeWordSeparator();
    }

    @Override
    protected void writeLineSeparator() throws IOException {
        padWithPlaceholder(getLineSeparator());
        super.writeLineSeparator();
    }

    private void padWithPlaceholder(String separator) {
        buffer.append(separator);
        TextPosition placeholder = positions.isEmpty() ? null : positions.get(positions.size() - 1);
        for (int i = 0; i < separator.length(); i++) {
            positions.add(placeholder);
        }
    }
}
