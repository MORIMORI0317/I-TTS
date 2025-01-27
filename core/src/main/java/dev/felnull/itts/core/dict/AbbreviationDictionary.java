package dev.felnull.itts.core.dict;

import com.google.common.collect.ImmutableMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 省略辞書
 *
 * @author MORIMORI0317
 */
public class AbbreviationDictionary implements Dictionary {

    /**
     * コードブロックの正規表現
     */
    private static final Pattern CODE_BLOCK_REGEX = Pattern.compile("```(.|\n)*```");

    /**
     * 正規表現関係
     */
    private final RegexUtil regexUtil = new RegexUtil();

    @Override
    public @NotNull String apply(@NotNull String text, long guildId) {
        text = CODE_BLOCK_REGEX.matcher(text).replaceAll("コードブロックショウリャク");
        return regexUtil.replaceText(text);
    }

    @Override
    public boolean isBuiltIn() {
        return true;
    }

    @Override
    public @NotNull String getName() {
        return "省略辞書";
    }

    @Override
    public @NotNull String getId() {
        return "abbreviation";
    }

    @Override
    public @NotNull @Unmodifiable Map<String, String> getShowInfo(long guildId) {
        return ImmutableMap.of("https://...", "URL省略", "``` コードブロック ```", "コードブロック省略");
    }

    @Override
    public int getDefaultPriority() {
        return 1;
    }
}
