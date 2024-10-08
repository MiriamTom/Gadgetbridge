/*  Copyright (C) 2018-2024 Carsten Pfeiffer, Matthieu Baerts, Taavi Eomäe

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */

package nodomain.freeyourgadget.gadgetbridge.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import io.wax911.emojify.EmojiManager;
import io.wax911.emojify.contract.model.IEmoji;
import io.wax911.emojify.parser.EmojiParserKt;
import io.wax911.emojify.parser.candidate.UnicodeCandidate;
import io.wax911.emojify.parser.transformer.EmojiTransformer;
import io.wax911.emojify.serializer.gson.GsonDeserializer;

public class EmojiConverter {
    private static final Logger LOG = LoggerFactory.getLogger(EmojiConverter.class);

    private static final String[][] simpleEmojiMapping = {
            {"\uD83D\uDE00", ":-D"},  // grinning
            {"\uD83D\uDE01", ":-D"},  // grinning_face_with_smiling_eyes
            {"\uD83D\uDE02", ":'D"},  // face_with_tears_of_joy
            {"\uD83D\uDE03", ":-D"},  // smiling_face_with_open_mouth
            {"\uD83D\uDE04", ":-D"},  // smiling_face_with_open_mouth_and_smiling_eyes
            {"\uD83D\uDE05", ":'D"},  // smiling_face_with_open_mouth_and_cold_sweat
            {"\uD83D\uDE06", "X-D"},  // smiling_face_with_open_mouth_and_tightly-closed_eyes
            {"\uD83D\uDE07", "O:-)"}, // innocent
            {"\uD83D\uDE09", ";-)"},  // wink
            {"\uD83D\uDE0A", ":-)"},  // blush
            {"\uD83D\uDE0B", ":-p"},  // yum
            {"\uD83D\uDE0E", "B-)"},  // sunglasses
            {"\uD83D\uDE15", ":-/"},  // confused
            {"\uD83D\uDE16", ":-S"},  // confounded_face
            {"\uD83D\uDE17", ":*"},   // kissing_face
            {"\uD83D\uDE18", ";-*"},  // face_throwing_a_kiss
            {"\uD83D\uDE19", ":-*"},  // kissing_face_with_smiling_eyes
            {"\uD83D\uDE1A", ":-*"},  // kissing_closed_eyes
            {"\uD83D\uDE1B", ":-P"},  // stuck_out_tongue
            {"\uD83D\uDE1C", ";-P"},  // stuck_out_tongue_winking_eye
            {"\uD83D\uDE1D", "X-P"},  // stuck_out_tongue_and_tightly-closed_eyes
            {"\uD83D\uDE1E", ":-S"},  // disappointed
            {"\uD83D\uDE20", ":-@"},  // angry_face
            {"\uD83D\uDE21", ":-@"},  // pouting_face
            {"\uD83D\uDE22", ":'("},  // cry
            {"\uD83D\uDE23", ":-("},  // preserving_face
            {"\uD83D\uDE25", ":'("},  // disappointed_but_relieved_face
            {"\uD83D\uDE2D", ":'("},  // loudly_crying_face
            {"\uD83D\uDE2E", ":-O"},  // open_mouth
            {"\uD83D\uDE32", "X-o"},  // astonished_face
            {"\uD83D\uDE42", ":)"},   // slightly_smiling_face
            {"\uD83D\uDE43", "(-:"},  // upside_down_face
            {"\u2639", ":-("},        // frowning_face
            {"\u2764", "<3"},         // heart
    };

    private static EmojiManager emojiManagerInstance;

    /**
     * An emoji transformer that removes fitzpatrick modifiers. This partially reimplements
     * EmojiParser.parseToAliases since I was unable to call it directly with FitzpatrickAction.REMOVE
     */
    private static final EmojiTransformer EMOJI_TRANSFORMER_NO_FITZPATRICK = new EmojiTransformer() {
        @Nullable
        @Override
        public String invoke(@NonNull final UnicodeCandidate unicodeCandidate) {
            final IEmoji emoji = unicodeCandidate.getEmoji();
            if (emoji == null) {
                return null;
            }
            final List<String> aliases = emoji.getAliases();
            if (aliases == null || aliases.isEmpty()) {
                return null;
            }
            return String.format(":%s:", aliases.get(0));
        }
    };

    private static String convertSimpleEmojiToAscii(String text) {
        for (String[] emojiMap : simpleEmojiMapping) {
            text = text.replace(emojiMap[0], emojiMap[1]);
        }
        return text;
    }

    public static synchronized EmojiManager getEmojiManager(final Context context) {
        // Do a lazy initialisation not to slowdown the startup and when it is needed
        if (emojiManagerInstance == null) {
            emojiManagerInstance = EmojiManager.Companion.create(context, new GsonDeserializer());
        }
        return emojiManagerInstance;
    }

    private static String convertAdvancedEmojiToAscii(final String text, final Context context) {
        final EmojiManager emojiManager = getEmojiManager(context);
        try {
            return EmojiParserKt.parseFromUnicode(emojiManager, text, EMOJI_TRANSFORMER_NO_FITZPATRICK);
        } catch (final Exception e) {
            LOG.warn("An exception occurred when converting advanced emoji to ASCII: {}", text);
            return text;
        }
    }

    public static String convertUnicodeEmojiToAscii(String text, final Context context) {
        text = convertSimpleEmojiToAscii(text);

        text = convertAdvancedEmojiToAscii(text, context);

        return text;
    }
}
